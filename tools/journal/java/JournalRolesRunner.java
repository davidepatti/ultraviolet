import misc.UVConfig;
import misc.json.*;
import network.FeePolicyLaw;
import network.UVNetwork;
import stats.*;
import topology.PathFinderFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static stats.JournalCaptureExporter.object;
import static stats.JournalCaptureExporter.sha256;

/**
 * Traffic-role study producer (capture only; no model, calibration or test entry point).
 *
 * <pre>
 *   bootstrap: role-linked genuine opening -> settled O export + private roles oracle -> common O snapshot
 *   branch:    strict O restore -> optional exact S50 -> initial fee-policy law -> scheduled segments,
 *              each ending in a settled checkpoint with public/truth/private, invoices and successful routes
 * </pre>
 * Run from a directory named {@code runtime} whose parent holds request.json and source-manifest.json;
 * every output is created exclusively (an existing file is an error).
 */
public final class JournalRolesRunner {
    static final String BOOT_SCHEMA = "traffic-roles-bootstrap-request-v1";
    static final String BRANCH_SCHEMA = "traffic-roles-branch-request-v1";
    static final String SCHEDULE_SCHEMA = "traffic-roles-schedule-v1";

    static JsonObject read(Path p) throws Exception { try (var r = Files.newBufferedReader(p)) { return Json.parseObject(r); } }
    static String hash(Path p) throws Exception { return sha256(Files.readAllBytes(p)); }
    static String create(Path p, Object value) throws Exception {
        byte[] bytes = (JournalCaptureExporter.canonical(value) + "\n").getBytes(StandardCharsets.UTF_8);
        Files.write(p, bytes, StandardOpenOption.CREATE_NEW);
        return sha256(bytes);
    }
    static void check(boolean x, String m) { if (!x) throw new IllegalStateException(m); }
    static int integer(Map<?, ?> row, String key) { return ((Number) row.get(key)).intValue(); }
    static long longValue(Map<?, ?> row, String key) { return ((Number) row.get(key)).longValue(); }
    static String opaque(String channelId) { return "jch-" + sha256(channelId.getBytes(StandardCharsets.UTF_8)); }

    /** Settled capture; public structure may change only at the fee step (expectedPublic null). */
    static JsonObject capture(UVNetwork n, Path out, String topology, String cp, long cumulative, JsonObject provenance,
                              String expectedPublic, JsonObject batch, List<Map<String, Object>> routes) throws Exception {
        Path d = out.resolve("checkpoints").resolve(cp);
        Files.createDirectories(d.getParent());
        Files.createDirectory(d);
        var first = JournalTrafficExporter.build(n, topology, cp, provenance);
        var again = JournalTrafficExporter.build(n, topology, cp, provenance);
        check(first.equals(again), "Paused capture changed");
        String pub = (String) first.truth().get("public_fingerprint");
        check(expectedPublic == null || pub.equals(expectedPublic), "Public structure/policies changed after the fee step");
        var files = object("public.json", create(d.resolve("public.json"), first.publicGraph()),
                "truth.json", create(d.resolve("truth.json"), first.truth()),
                "private.json", create(d.resolve("private.json"), first.privateState()));
        if (batch != null) files.put("invoices.json", create(d.resolve("invoices.json"), batch));
        if (routes != null) {
            var rows = new JsonArray();
            for (var r : routes) {
                var row = new JsonObject(); row.putAll(r);
                var hops = new JsonArray();
                for (Object h : (List<?>) r.get("hops")) { var hop = (List<?>) h; hops.add(List.of(opaque((String) hop.get(0)), hop.get(1), hop.get(2))); }
                row.put("hops", hops);
                rows.add(row);
            }
            files.put("routes.json", create(d.resolve("routes.json"), object("schema", "traffic-roles-routes-v1",
                    "note", "successful invoices only; sender's planned path, edges as recorded by UV Path (direction per edge)",
                    "routes", rows)));
        }
        var receipt = object("schema", "traffic-roles-checkpoint-v1", "topology_id", topology, "checkpoint", cp,
                "cumulative_requests", cumulative, "files_sha256", files, "public_fingerprint", pub,
                "structural_fingerprint", first.truth().get("structural_fingerprint"));
        create(d.resolve("capture.json"), receipt);
        return receipt;
    }

    static FeePolicyLaw.Spec feeSpec(JsonObject spec) {
        Map<String, FeePolicyLaw.Group> groups = new TreeMap<>();
        var g = (Map<?, ?>) spec.getOrDefault("groups", new JsonObject());
        for (var e : g.entrySet()) {
            var row = (Map<?, ?>) e.getValue(); var ppm = (Map<?, ?>) row.get("ppm");
            var base = new ArrayList<Integer>(); for (Object b : (List<?>) row.get("base_msat_set")) base.add(((Number) b).intValue());
            groups.put((String) e.getKey(), new FeePolicyLaw.Group(new FeePolicyLaw.Distribution(integer(ppm, "min"), integer(ppm, "max"),
                    ((Number) ppm.get("median")).doubleValue(), ((Number) ppm.get("mean")).doubleValue()), base));
        }
        Object sigma = spec.get("jitter_sigma");
        return new FeePolicyLaw.Spec((String) spec.get("mode"), longValue(spec, "seed"), groups,
                sigma == null ? 0.0 : ((Number) sigma).doubleValue(), (String) spec.get("calibration_id"));
    }

    public static void main(String[] args) {
        int code = 1;
        UVNetwork n = null;
        try {
            check(args.length == 1 && Set.of("bootstrap", "branch").contains(args[0]), "Mode required: bootstrap|branch");
            String mode = args[0];
            Path runtime = Path.of("").toRealPath();
            check(runtime.getFileName().toString().equals("runtime"), "Runtime directory required");
            Path out = runtime.getParent();
            var request = read(out.resolve("request.json"));
            var source = read(out.resolve("source-manifest.json"));
            check("development-capture-only".equals(request.get("role")), "Wrong capture role");
            String topology = (String) request.get("topology_id"), sourceHash = (String) source.get("source_tree_sha256");
            check(sourceHash != null && sourceHash.matches("[0-9a-f]{64}"), "Source manifest lacks a tree hash");
            long drain = longValue(request, "drain_timeout_ms");
            var config = new UVConfig("capture.properties");
            check(config.logfile.equals("simulator.log"), "Capture log must be runtime/simulator.log");
            var configHash = create(out.resolve("expanded-config.json"), config.getEffectiveSettings());
            UVNetwork.Log = x -> {};
            n = new UVNetwork(config);
            var provenance = object("capture_id", UUID.randomUUID().toString(), "parent_id", topology, "mode", mode,
                    "bootstrap_seed", config.getMasterSeed(), "source_tree_sha256", sourceHash,
                    "source_manifest_sha256", hash(out.resolve("source-manifest.json")), "request_sha256", hash(out.resolve("request.json")),
                    "expanded_config_sha256", configHash, "created_at_utc", Instant.now().toString(), "pathfinder", "lnd");
            long start = System.nanoTime();

            if (mode.equals("bootstrap")) {
                check(BOOT_SCHEMA.equals(request.get("schema")), "Wrong request schema");
                n.bootstrapNetwork();
                n.pauseAndDrain(drain);
                var roles = n.getPaymentRoles();
                check(roles != null, "Study bootstrap requires journal.roles.enabled=true");
                for (var node : n.getUVNodeList().values()) node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, config));
                var assignment = new JsonObject();
                for (var node : new TreeMap<>(n.getUVNodeList()).values())
                    assignment.put(node.getPubKey(), object("profile", node.getProfile().getName(), "role", roles.roleOf(node.getPubKey())));
                var rolesHash = create(out.resolve("roles.json"), object("schema", "traffic-roles-private-roles-v1",
                        "topology_id", topology, "access", "oracle-only; never a model input",
                        "model", roles.description(), "nodes", assignment));
                var opening = capture(n, out, topology, "o-bootstrap", 0, provenance, null, null, null);
                String fingerprint = n.initialOFingerprint();
                create(out.resolve("initial-o-description.json"), n.initialODescription());
                n.saveInitialOBranch(out.resolve("initial-o.dat").toString(), sourceHash);
                check(fingerprint.equals(n.initialOFingerprint()), "Save changed initial O state");
                create(out.resolve("complete.json"), object("schema", "traffic-roles-common-v1", "topology_id", topology,
                        "provenance", provenance, "initial_state_fingerprint", fingerprint,
                        "snapshot_sha256", hash(out.resolve("initial-o.dat")), "roles_sha256", rolesHash,
                        "opening_public_fingerprint", opening.get("public_fingerprint"),
                        "structural_fingerprint", opening.get("structural_fingerprint"),
                        "nodes", n.getUVNodeList().size(), "native_seconds", (System.nanoTime() - start) / 1e9));
            } else {
                check(BRANCH_SCHEMA.equals(request.get("schema")), "Wrong request schema");
                Path common = Path.of((String) request.get("common_path"));
                Path schedulePath = Path.of((String) request.get("schedule_path"));
                var parent = read(common.resolve("complete.json"));
                var schedule = read(schedulePath);
                check(SCHEDULE_SCHEMA.equals(schedule.get("schema")), "Wrong schedule schema");
                check(topology.equals(parent.get("topology_id")) && topology.equals(schedule.get("topology_id")), "Parent identity mismatch");
                check(hash(common.resolve("initial-o.dat")).equals(parent.get("snapshot_sha256")), "Snapshot changed");
                check(hash(common.resolve("roles.json")).equals(parent.get("roles_sha256")), "Roles oracle changed");
                check(hash(schedulePath).equals(request.get("schedule_sha256")), "Schedule changed");
                check(sourceHash.equals(((Map<?, ?>) parent.get("provenance")).get("source_tree_sha256")), "Parent source mismatch");
                check(n.loadInitialOBranch(common.resolve("initial-o.dat").toString(), sourceHash), "Strict O branch rejected: " + n.getLastLoadStatusError());
                String initial = n.initialOFingerprint();
                check(initial.equals(parent.get("initial_state_fingerprint")), "Branch operative state mismatch");
                check(n.getConfig().getEffectiveSettings().equals(config.getEffectiveSettings()), "Restored config differs");
                provenance.put("common_snapshot_sha256", parent.get("snapshot_sha256"));
                provenance.put("common_state_fingerprint", initial);
                provenance.put("schedule_sha256", hash(schedulePath));
                provenance.put("branch_id", request.get("branch_id"));

                // 1. allocation
                String allocation = (String) request.get("allocation");
                check(Set.of("o", "s50").contains(allocation), "allocation must be o or s50");
                Map<String, Object> allocationReceipt = allocation.equals("s50")
                        ? n.initializeExactS50(longValue(request, "s50_traffic_seed"))
                        : object("allocation", "genuine-opening", "note", "native RNG unused under saved-schedule replay");
                // 2. fee law (roles from the bound private oracle)
                var rolesFile = read(common.resolve("roles.json"));
                Map<String, String> roles = new TreeMap<>();
                for (var e : ((Map<?, ?>) rolesFile.get("nodes")).entrySet()) roles.put((String) e.getKey(), (String) ((Map<?, ?>) e.getValue()).get("role"));
                var spec = (JsonObject) request.get("fee_spec");
                check(!String.valueOf(spec.get("calibration_id")).startsWith("UNCALIBRATED"), "Fee template placeholder: record the D5 calibration first");
                var feeReceipt = n.reassignInitialFeePolicies(feeSpec(spec), roles);
                provenance.put("fee_spec_sha256", sha256(JournalCaptureExporter.canonical(spec).getBytes(StandardCharsets.UTF_8)));
                create(out.resolve("branch-initialization.json"), object("schema", "traffic-roles-branch-init-v1",
                        "allocation", allocation, "allocation_receipt", allocationReceipt,
                        "fee_receipt_public", object("mode", feeReceipt.get("mode"), "seed", feeReceipt.get("seed"),
                                "directed_policy_table_sha256", feeReceipt.get("directed_policy_table_sha256"), "channels", feeReceipt.get("channels"),
                                "graph_edges_replaced", feeReceipt.get("graph_edges_replaced"), "calibration_id", feeReceipt.get("calibration_id"))));
                create(out.resolve("node-policies.json"), object("schema", "traffic-roles-private-node-policies-v1",
                        "access", "oracle-only; never a model input", "topology_id", topology, "nodes", feeReceipt.get("node_policies")));
                var startCp = capture(n, out, topology, "start", 0, provenance, null, null, null);
                String publicFingerprint = (String) startCp.get("public_fingerprint");

                // 3. scheduled segments
                n.enableRouteLog();
                var rows = (List<?>) schedule.get("requests");
                var segments = (List<?>) schedule.get("segments");
                double rate = ((Number) schedule.get("rate_per_node_per_block")).doubleValue();
                int defaultMaxFee = integer(schedule, "default_max_fee_sat");
                long cumulative = 0;
                var summaries = new JsonArray();
                for (Object s : segments) {
                    var seg = (Map<?, ?>) s;
                    String name = (String) seg.get("name");
                    int count = integer(seg, "count"), blocks = integer(seg, "blocks");
                    check(longValue(seg, "first_index") == cumulative, "Segments must be contiguous");
                    var planned = new ArrayList<UVNetwork.ScheduledInvoice>();
                    for (long i = cumulative; i < cumulative + count; i++) {
                        var row = (Map<?, ?>) rows.get((int) i);
                        check(name.equals(row.get("segment")), "Wrong segment for row " + i);
                        planned.add(new UVNetwork.ScheduledInvoice(longValue(row, "position"), integer(row, "segment_block"),
                                (String) row.get("sender"), (String) row.get("destination"), integer(row, "amount_sat"), integer(row, "max_fee_sat")));
                    }
                    var batch = n.generateScheduledInvoiceBatch(name, rate, blocks, planned, defaultMaxFee, drain).snapshot();
                    var actual = (List<?>) batch.get("requests");
                    check(actual.size() == count, "Replay count differs");
                    int origin = integer((Map<?, ?>) actual.getFirst(), "submitted_height");
                    for (int j = 0; j < count; j++) {
                        @SuppressWarnings("unchecked") var a = (Map<String, Object>) actual.get(j);
                        var q = planned.get(j);
                        check(integer(a, "submitted_height") == origin + q.relativeBlock(), "Realized timing differs");
                        check(longValue(a, "request_id") == q.position() && q.sender().equals(a.get("sender"))
                                && q.destination().equals(a.get("destination")) && q.amountSat() == integer(a, "amount_sat"), "Realized request differs");
                        a.put("planned_max_fee_sat", q.maxFeeSat());
                    }
                    batch.put("replay_pacing", n.getLastScheduledTiming());
                    batch.put("schema", "traffic-roles-invoices-v1");
                    batch.put("sampling_mode", "saved-scheduled-replay");
                    batch.put("replay_origin_height", origin);
                    batch.put("schedule_sha256", hash(schedulePath));
                    cumulative += count;
                    capture(n, out, topology, name, cumulative, provenance, publicFingerprint, batch, n.drainSettledRoutes());
                    summaries.add(batch.get("summary"));
                }
                check(cumulative == rows.size(), "Schedule not fully replayed");
                create(out.resolve("complete.json"), object("schema", "traffic-roles-trajectory-v1", "topology_id", topology,
                        "branch_id", request.get("branch_id"), "provenance", provenance, "initial_state_fingerprint", initial,
                        "public_fingerprint_after_fees", publicFingerprint, "total_requests", cumulative, "summaries", summaries,
                        "settled", true, "native_seconds", (System.nanoTime() - start) / 1e9));
            }
            code = 0;
            System.out.println("Completed " + mode + " " + topology);
        } catch (Throwable e) {
            e.printStackTrace(System.err);
        } finally {
            if (n != null) try { n.shutdown(); } catch (Throwable ignored) {}
        }
        System.exit(code);
    }
}
