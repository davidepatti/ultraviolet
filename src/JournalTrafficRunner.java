import misc.UVConfig;
import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.UVNetwork;
import stats.JournalCaptureExporter;
import stats.JournalTrafficExporter;
import topology.PathFinderFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static stats.JournalCaptureExporter.object;
import static stats.JournalCaptureExporter.sha256;

/** One fresh O/T trajectory. No saved-state load, F/U mutation or learner entry point. */
public final class JournalTrafficRunner {
    private JournalTrafficRunner() {}

    private static JsonObject read(Path file) throws Exception {
        try (var reader = Files.newBufferedReader(file)) { return Json.parseObject(reader); }
    }

    private static byte[] bytes(Object value) {
        return (JournalCaptureExporter.canonical(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String create(Path path, Object value) throws Exception {
        byte[] content = bytes(value);
        Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        return sha256(content);
    }

    private static void complete(Path path, Object value) throws Exception {
        Path temporary = path.resolveSibling(path.getFileName() + ".partial");
        create(temporary, value);
        if (Files.exists(path)) throw new IllegalStateException("Completion already exists");
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
    }

    private static int integer(JsonObject object, String key) { return ((Number) object.get(key)).intValue(); }

    private static JsonObject checkpoint(UVNetwork network, Path output, String topology, String name,
            long cumulative, JsonObject provenance, String expectedStructure) throws Exception {
        if (!name.matches("[a-z0-9][a-z0-9-]{0,79}")) throw new IllegalArgumentException("Invalid checkpoint name");
        Path directory = output.resolve("checkpoints").resolve(name);
        Files.createDirectory(directory);
        var first = JournalTrafficExporter.build(network, topology, name, provenance);
        var again = JournalTrafficExporter.build(network, topology, name, provenance);
        if (!first.equals(again)) throw new IllegalStateException("Paused state changed during export");
        String structure = (String) first.truth().get("structural_fingerprint");
        if (expectedStructure != null && !structure.equals(expectedStructure)) {
            throw new IllegalStateException("Profiles, onchain funding, topology or policies changed");
        }
        JsonObject hashes = object("public.json", create(directory.resolve("public.json"), first.publicGraph()),
                "truth.json", create(directory.resolve("truth.json"), first.truth()),
                "private.json", create(directory.resolve("private.json"), first.privateState()));
        if (network.getLastJournalBatch() != null) {
            hashes.put("invoices.json", create(directory.resolve("invoices.json"), network.getLastJournalBatch().snapshot()));
        }
        JsonObject receipt = object("schema", "journal-ot-checkpoint-v1", "topology_id", topology,
                "checkpoint", name, "cumulative_requests", cumulative, "files_sha256", hashes,
                "public_fingerprint", first.truth().get("public_fingerprint"),
                "structural_fingerprint", structure, "repeated_capture_identical", true,
                "settled", true, "block_height", network.getTimechain().getCurrentBlockHeight());
        complete(directory.resolve("capture.json"), receipt);
        return receipt;
    }

    public static void main(String[] args) {
        int code = 1;
        UVNetwork network = null;
        Path output = null;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Run through the journal O/T launcher");
            Path runtime = Path.of("").toRealPath();
            output = runtime.getParent();
            Path group = output.getParent();
            Path b = group.getFileName().toString().equals("incoming") ? group.getParent()
                    : group.getFileName().toString().equals("captures") && group.getParent().getFileName().toString().equals("validation")
                    ? group.getParent().getParent() : null;
            if (!runtime.getFileName().toString().equals("runtime") || b == null || !b.getFileName().toString().equals("b")
                    || !b.getParent().getFileName().toString().equals("gnn-lightning-journal")
                    || !Files.isRegularFile(b.resolve("minimal-o-t-pilot.md"))) {
                throw new IllegalArgumentException("Destination is not the journal O/T capture space");
            }
            JsonObject request = read(output.resolve("request.json")), source = read(output.resolve("source-manifest.json"));
            if (!"journal-ot-request-v1".equals(request.get("schema"))) throw new IllegalArgumentException("Wrong O/T request");
            String topology = (String) request.get("topology_id");
            JsonObject traffic = (JsonObject) request.get("traffic");
            JsonArray batches = (JsonArray) traffic.get("batches");
            long drainMillis = integer(traffic, "drain_timeout_ms");
            UVConfig config = new UVConfig("capture.properties");
            if (!config.logfile.equals("simulator.log")) throw new IllegalArgumentException("Nonlocal logfile");
            String configHash = create(output.resolve("expanded-config.json"), config.getEffectiveSettings());
            UVNetwork.Log = ignored -> { };
            network = new UVNetwork(config);
            network.bootstrapNetwork();
            network.stopForJournalCapture(drainMillis);
            JournalCaptureExporter.validateRecords(new ArrayList<>(network.getUVNodeList().values()));
            String cleanupHash = create(output.resolve("opening-cleanup.json"), object("schema", "journal-ot-opening-cleanup-v1",
                    "cancelled_unaccepted_requests", network.getJournalOpeningCleanup(),
                    "funded_channels_changed", false, "onchain_balances_changed", false));
            for (var node : network.getUVNodeList().values()) {
                node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, config));
            }
            network.beginJournalTraffic();
            Files.createDirectory(output.resolve("checkpoints"));
            JsonObject provenance = object("capture_id", UUID.randomUUID().toString(), "bootstrap_seed", config.getMasterSeed(),
                    "uv_commit", source.get("base_commit"), "source_tree_sha256", source.get("source_tree_sha256"),
                    "source_dirty", source.get("dirty"), "source_manifest_sha256", sha256(Files.readAllBytes(output.resolve("source-manifest.json"))),
                    "request_sha256", sha256(Files.readAllBytes(output.resolve("request.json"))),
                    "expanded_config_sha256", configHash, "opening_cleanup_sha256", cleanupHash,
                    "capture_boundary", "journal-ot-joined-settled-v1", "pending_checked", true,
                    "pathfinder", "lnd", "created_at_utc", Instant.now().toString());
            JsonArray checkpoints = new JsonArray();
            var opening = checkpoint(network, output, topology, "o", 0, provenance, null);
            String structural = (String) opening.get("structural_fingerprint");
            checkpoints.add(object("checkpoint", "o", "receipt_sha256", sha256(Files.readAllBytes(output.resolve("checkpoints/o/capture.json")))));
            long cumulative = 0;
            for (Object entry : batches) {
                JsonObject batch = (JsonObject) entry;
                String name = (String) batch.get("id");
                network.resumeJournalTraffic();
                var result = network.generateJournalInvoiceBatch(name, ((Number) traffic.get("node_events_per_block")).doubleValue(),
                        integer(batch, "blocks"), integer(traffic, "min_amount_sat"), integer(traffic, "max_amount_sat"),
                        integer(traffic, "max_fees_sat"), drainMillis);
                JsonObject summary = (JsonObject) result.snapshot().get("summary");
                int submitted = integer(summary, "requested");
                if (submitted != integer(batch, "requested")) throw new IllegalStateException("Requested exposure differs from plan");
                cumulative += submitted;
                checkpoint(network, output, topology, name, cumulative, provenance, structural);
                checkpoints.add(object("checkpoint", name, "receipt_sha256", sha256(Files.readAllBytes(output.resolve("checkpoints/" + name + "/capture.json")))));
            }
            complete(output.resolve("trajectory.json"), object("schema", "journal-ot-trajectory-v1", "topology_id", topology,
                    "provenance", provenance, "checkpoints", checkpoints, "total_requests", cumulative, "settled", true));
            System.out.println("Journal O/T trajectory complete: " + topology + "; requests=" + cumulative);
            code = 0;
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            if (output != null && Files.isRegularFile(output.resolve("request.json"))) {
                try {
                    create(output.resolve("failure.json"), object("schema", "journal-ot-failure-v1", "error", error.toString(),
                            "unsettled_state", network == null ? null : network.journalUnsettledState(),
                            "last_batch", network == null || network.getLastJournalBatch() == null ? null : network.getLastJournalBatch().snapshot()));
                } catch (Exception recordingError) { recordingError.printStackTrace(System.err); }
            }
        } finally {
            if (network != null) network.shutdown();
        }
        System.exit(code);
    }
}
