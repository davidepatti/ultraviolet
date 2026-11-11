package tests;

import misc.UVConfig;
import network.*;
import stats.InitialS50State;
import stats.JournalCaptureExporter;
import stats.JournalTrafficExporter;
import topology.PathFinderFactory;

import java.nio.file.*;
import java.util.*;

/** Stage 0 checks for the traffic-role study producer: roles, fee laws, O branching, per-invoice fee cap, route log. */
public final class TrafficRolesTest {
    static int passed = 0;
    static void check(boolean b, String m) { if (!b) throw new AssertionError(m); passed++; }
    interface Action { void run() throws Exception; }
    static void rejects(String m, Action a) throws Exception {
        try { a.run(); } catch (IllegalStateException | IllegalArgumentException e) { passed++; return; }
        throw new AssertionError("Expected rejection: " + m);
    }

    static final String ROLE_SETTINGS = String.join("\n",
            "journal.roles.enabled=true", "journal.roles.seed=424242",
            "journal.roles.open_weight.payer=1.0", "journal.roles.open_weight.merchant=3.0", "journal.roles.open_weight.router=1.0",
            "profile.small.role_payer=0.75", "profile.small.role_merchant=0.20", "profile.small.role_router=0.05",
            "profile.medium.role_payer=0.20", "profile.medium.role_merchant=0.50", "profile.medium.role_router=0.30",
            "profile.hub.role_payer=0.0", "profile.hub.role_merchant=0.20", "profile.hub.role_router=0.80") + "\n";

    static UVConfig config(Path tmp, String name, String extra) throws Exception {
        Path p = tmp.resolve(name + ".properties");
        String values = "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nseed=71\nbootstrap_nodes=12\nbootstrap_blocks=8\nmax_threads=16\nblocktime_ms=10\nnode_services_tick_ms=2\ngossip_flush_period_ms=2\nminimum_depth=1\nlogfile=" + tmp.resolve(name + ".log") + "\n";
        for (String profile : List.of("small", "medium", "hub"))
            values += "profile." + profile + ".min_channels=1\nprofile." + profile + ".max_channels=3\nprofile." + profile + ".min_funding=20000000\nprofile." + profile + ".max_funding=30000000\n";
        Files.writeString(p, values + extra);
        return new UVConfig(p.toString());
    }

    static void roleModel(Path tmp) throws Exception {
        check(PaymentRoles.fromConfig(config(tmp, "roles-off", "")) == null, "roles disabled by default");
        rejects("missing seed", () -> PaymentRoles.fromConfig(config(tmp, "roles-noseed", ROLE_SETTINGS.replace("journal.roles.seed=424242\n", ""))));
        rejects("bad sum", () -> PaymentRoles.fromConfig(config(tmp, "roles-sum", ROLE_SETTINGS.replace("profile.small.role_router=0.05", "profile.small.role_router=0.15"))));
        rejects("negative", () -> PaymentRoles.fromConfig(config(tmp, "roles-neg", ROLE_SETTINGS.replace("profile.hub.role_payer=0.0", "profile.hub.role_payer=-0.1").replace("profile.hub.role_router=0.80", "profile.hub.role_router=0.90"))));
        rejects("missing role key", () -> PaymentRoles.fromConfig(config(tmp, "roles-miss", ROLE_SETTINGS.replace("profile.medium.role_router=0.30\n", ""))));
        rejects("bad enabled", () -> PaymentRoles.fromConfig(config(tmp, "roles-en", ROLE_SETTINGS.replace("journal.roles.enabled=true", "journal.roles.enabled=yes"))));
        var cfg = config(tmp, "roles-on", ROLE_SETTINGS);
        var a = PaymentRoles.fromConfig(cfg); var b = PaymentRoles.fromConfig(cfg);
        var small = cfg.getNodeProfile("small"); var hub = cfg.getNodeProfile("hub");
        Map<String, Integer> counts = new TreeMap<>();
        for (int i = 0; i < 40000; i++) {
            String ra = a.assign("s" + i, small), rb = b.assign("s" + i, small);
            check(ra.equals(rb), "same seed, same roles");
            counts.merge(ra, 1, Integer::sum);
        }
        check(Math.abs(counts.get("payer") / 40000.0 - 0.75) < 0.01 && Math.abs(counts.get("merchant") / 40000.0 - 0.20) < 0.01
                && Math.abs(counts.get("router") / 40000.0 - 0.05) < 0.006, "role frequencies match the table: " + counts);
        for (int i = 0; i < 2000; i++) check(!a.assign("h" + i, hub).equals("payer"), "hub never payer (P=0)");
        rejects("double assignment", () -> a.assign("s0", small));
        check(a.retryMultiplier() == 3, "retry multiplier = max/min weight");
        String merchant = null, payer = null;
        for (var e : a.assignments().entrySet()) { if (e.getValue().equals("merchant")) merchant = e.getKey(); if (e.getValue().equals("payer")) payer = e.getKey(); }
        check(a.openAcceptance(merchant) == 1.0 && Math.abs(a.openAcceptance(payer) - 1.0 / 3) < 1e-12, "opening acceptance ∝ weight");
    }

    /** Every failure reason the producer emits has its own sender-side category (none silently becomes "unknown"). */
    static void failureCategories() throws Exception {
        var expected = new java.util.LinkedHashMap<String, String>();
        expected.put("expiry_too_soon", "expiry_too_soon");
        expected.put("temporary_channel_failure", "temporary_channel");
        expected.put("missing local liquidity", "local_liquidity");
        expected.put("attempt_timeout", "timeout");
        expected.put("fee_insufficient", "fee_insufficient");
        expected.put("incorrect_cltv_expiry", "incorrect_cltv_expiry");
        expected.put("incorrect_or_unknown_payment_details", "incorrect_payment_details");
        expected.forEach((reason, category) -> check(UVNode.attemptFailureCategory(reason).equals(category), "category of " + reason));
        check(UVNode.attemptFailureCategory(null).equals("unknown") && UVNode.attemptFailureCategory("something new").equals("unknown"), "unknown fallback");
        // Source scan: every literal reason passed to failHTLC / failure_reason / FAILED outcomes is mapped.
        var emitted = new TreeSet<String>();
        var pattern = java.util.regex.Pattern.compile("(?:failHTLC\\([^,]+,\\s*|failure_reason\\.put\\([^,]+,\\s*|State\\.FAILED,\\s*)\"([^\"]+)\"");
        for (String file : List.of("src/network/UVNode.java", "src/network/HTLCResolution.java")) {
            var m = pattern.matcher(Files.readString(Path.of(file)));
            while (m.find()) emitted.add(m.group(1));
        }
        check(!emitted.isEmpty() && emitted.stream().noneMatch(r -> UVNode.attemptFailureCategory(r).equals("unknown")), "emitted reasons all categorized: " + emitted);
        // Report fields reach the JSON export (record components) and the CSV (appended after success).
        var names = Arrays.stream(stats.GlobalStats.NodeStats.InvoiceReport.class.getRecordComponents()).map(java.lang.reflect.RecordComponent::getName).toList();
        check(names.subList(names.size() - 4, names.size()).equals(List.of("success", "attempt_failed_fee_insufficient",
                "attempt_failed_incorrect_cltv_expiry", "attempt_failed_incorrect_payment_details")), "new counters appended after success");
        String header = stats.GlobalStats.NodeStats.InvoiceReport.generateInvoiceReportHeader();
        check(header.split(",").length == names.size() && header.endsWith("success,attempt_failed_fee_insufficient,attempt_failed_incorrect_cltv_expiry,attempt_failed_incorrect_payment_details"), "CSV header aligned");
    }

    static void feeLaw(Path tmp) throws Exception {
        var cfg = config(tmp, "fees", "");
        Map<String, String> profiles = new TreeMap<>(Map.of("a", "small", "b", "medium", "c", "hub", "d", "small"));
        Map<String, String> roles = new TreeMap<>(Map.of("a", "payer", "b", "merchant", "c", "router", "d", "merchant"));
        Map<String, String[]> ch = new TreeMap<>();
        ch.put("x1", new String[]{"a", "b", "40", "40"}); ch.put("x2", new String[]{"b", "c", "40", "40"});
        ch.put("x3", new String[]{"a", "c", "40", "40"}); ch.put("x4", new String[]{"c", "d", "40", "40"});
        var perEdge = new FeePolicyLaw.Spec("per_edge", 9, Map.of(), 0, null);
        var r1 = FeePolicyLaw.draw(perEdge, cfg, profiles, null, ch);
        var r2 = FeePolicyLaw.draw(perEdge, cfg, profiles, null, ch);
        check(table(r1).equals(table(r2)), "per_edge deterministic");
        check(!table(r1).equals(table(FeePolicyLaw.draw(new FeePolicyLaw.Spec("per_edge", 10, Map.of(), 0, null), cfg, profiles, null, ch))), "seed changes per_edge draw");
        var dist = new FeePolicyLaw.Distribution(0, 3000, 125, 700);
        var groups = Map.of("router", new FeePolicyLaw.Group(new FeePolicyLaw.Distribution(100, 2000, 400, 600), List.of(1000)),
                "default", new FeePolicyLaw.Group(dist, List.of(0, 100, 1000)));
        var flat = FeePolicyLaw.draw(new FeePolicyLaw.Spec("node_policy", 9, groups, 0.0, "test-cal"), cfg, profiles, roles, ch);
        for (var e : flat.directed().entrySet()) for (var side : e.getValue().entrySet()) {
            var node = flat.nodePolicies().get(side.getKey());
            check(side.getValue().getFeePpm() == (int) node.get("ppm") && side.getValue().getBaseFee() == (int) node.get("base_msat"), "sigma 0: every direction equals node policy");
        }
        check((int) flat.nodePolicies().get("c").get("base_msat") == 1000 && (int) flat.nodePolicies().get("c").get("ppm") >= 100, "role group used before default");
        var jit = FeePolicyLaw.draw(new FeePolicyLaw.Spec("node_policy", 9, groups, 0.2, "test-cal"), cfg, profiles, roles, ch);
        java.util.function.Function<Map<String, Map<String, Object>>, Map<String, String>> draws0 = t -> { var m = new TreeMap<String, String>(); t.forEach((k, v) -> m.put(k, v.get("base_msat") + ":" + v.get("ppm"))); return m; };
        check(draws0.apply(jit.nodePolicies()).equals(draws0.apply(flat.nodePolicies())), "jitter draws after node policies: same node draws");
        for (var e : jit.directed().entrySet()) for (var side : e.getValue().entrySet()) {
            int ppm = side.getValue().getFeePpm(), base = (int) jit.nodePolicies().get(side.getKey()).get("ppm");
            check(ppm >= 0 && ppm <= 3000 && Math.abs(Math.log1p(ppm) - Math.log1p(base)) < 0.2 * 6 + 0.7, "jitter acts on log1p(ppm) and is bounded");
        }
        rejects("node_policy needs calibration id", () -> new FeePolicyLaw.Spec("node_policy", 9, groups, 0.2, ""));
        rejects("node_policy needs roles", () -> FeePolicyLaw.draw(new FeePolicyLaw.Spec("node_policy", 9, groups, 0.2, "c"), cfg, profiles, null, ch));
        rejects("unknown mode", () -> new FeePolicyLaw.Spec("dynamic", 9, groups, 0.2, "c"));
        rejects("missing group", () -> FeePolicyLaw.draw(new FeePolicyLaw.Spec("node_policy", 9, Map.of("router", groups.get("router")), 0.2, "c"), cfg, profiles, roles, ch));
        rejects("bad distribution", () -> new FeePolicyLaw.Distribution(10, 5, 7, 7));
        // Empirical quantile groups, weighted base fees, per-group sigma.
        var table = new FeePolicyLaw.QuantileTable(List.of(new double[]{0, 0}, new double[]{0.5, 1}, new double[]{0.9, 450}, new double[]{1, 10000}));
        var rngT = new Random(3); int[] draws = new int[20000];
        for (int i = 0; i < draws.length; i++) draws[i] = table.sample(rngT);
        Arrays.sort(draws);
        check(draws[0] >= 0 && draws[draws.length - 1] <= 10000 && Math.abs(draws[10000] - 1) <= 1 && Math.abs(draws[18000] - 450) <= 25, "quantile table reproduces its knots");
        var weighted = new FeePolicyLaw.Group(null, table, null, new TreeMap<>(Map.of(0, 0.25, 1000, 0.75)), 0.0);
        var wspec = new FeePolicyLaw.Spec("node_policy", 9, Map.of("default", weighted), 1.0, "test-cal");
        Map<String, String> big = new TreeMap<>(), bigRoles = new TreeMap<>();
        for (int i = 0; i < 4000; i++) { big.put("n" + i, "small"); bigRoles.put("n" + i, "payer"); }
        var wres = FeePolicyLaw.draw(wspec, cfg, big, bigRoles, Map.of());
        long zeros = wres.nodePolicies().values().stream().filter(r -> (int) r.get("base_msat") == 0).count();
        check(Math.abs(zeros / 4000.0 - 0.25) < 0.03, "weighted base law");
        check(wres.nodePolicies().values().stream().allMatch(r -> (double) r.get("jitter_sigma") == 0.0), "group sigma overrides spec sigma");
        rejects("two ppm laws", () -> new FeePolicyLaw.Group(dist, table, List.of(0), null, null));
        rejects("weights not summing to 1", () -> new FeePolicyLaw.Group(null, table, null, new TreeMap<>(Map.of(0, 0.5)), null));
        rejects("decreasing knots", () -> new FeePolicyLaw.QuantileTable(List.of(new double[]{0, 5}, new double[]{1, 1})));
    }

    static Map<String, String> table(FeePolicyLaw.Result r) {
        var t = new TreeMap<String, String>();
        r.directed().forEach((id, s) -> s.forEach((src, p) -> t.put(id + "/" + src, p.getBaseFee() + ":" + p.getFeePpm())));
        return t;
    }

    static List<String> adjacencyOrder(UVNetwork n) {
        var out = new ArrayList<String>();
        for (var node : new TreeMap<>(n.getUVNodeList()).values())
            for (var e : new TreeMap<>(node.getChannelGraph().getAdjMap()).entrySet())
                for (var edge : e.getValue()) out.add(node.getPubKey() + ":" + e.getKey() + ":" + edge.id() + ":" + edge.destination());
        return out;
    }
    static Map<String, String> balances(UVNetwork n) {
        var out = new TreeMap<String, String>();
        for (var node : n.getUVNodeList().values()) for (var c : node.getChannels().values())
            out.put(c.getChannelId(), c.getBalance(c.getNode1PubKey()) + "/" + c.getBalance(c.getNode2PubKey()));
        return out;
    }

    static final String SRC = "a".repeat(64);
    static final FeePolicyLaw.Spec NODE_SPEC = new FeePolicyLaw.Spec("node_policy", 77,
            Map.of("default", new FeePolicyLaw.Group(new FeePolicyLaw.Distribution(0, 3000, 125, 700), List.of(0, 100, 1000))), 0.2, "stage0-uncalibrated-fixture");

    static void liveBranching(Path tmp) throws Exception {
        UVNetwork.Log = x -> {};
        var cfg = config(tmp, "live", ROLE_SETTINGS);
        Path snap = tmp.resolve("initial-o.dat");
        Map<String, String> roles; String fingerprint; List<String> order;
        var boot = new UVNetwork(cfg);
        try {
            boot.bootstrapNetwork();
            boot.pauseAndDrain(5000);
            check(boot.isBootstrapCompleted() && boot.isPaused() && boot.unsettledState().isEmpty(), "bootstrap settled");
            roles = boot.getPaymentRoles().assignments();
            check(roles.keySet().equals(boot.getUVNodeList().keySet()), "every node has one role");
            for (var node : boot.getUVNodeList().values()) node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, cfg));
            JournalTrafficExporter.build(boot, "fixture", "o", JournalCaptureExporter.object("fixture", true));
            fingerprint = boot.initialOFingerprint();
            boot.saveInitialOBranch(snap.toString(), SRC);
            check(fingerprint.equals(boot.initialOFingerprint()), "save leaves O state unchanged");
            rejects("O snapshot is not an S50 branch", () -> boot.saveInitialS50Branch(tmp.resolve("x.dat").toString(), SRC));
        } finally { boot.shutdown(); }

        // Independent restores are identical; wrong source or contract is rejected.
        var o1 = new UVNetwork(cfg); var o2 = new UVNetwork(cfg); var s50 = new UVNetwork(cfg);
        try {
            check(o1.loadInitialOBranch(snap.toString(), SRC) && o2.loadInitialOBranch(snap.toString(), SRC) && s50.loadInitialOBranch(snap.toString(), SRC), "strict O restore");
            check(o1.initialOFingerprint().equals(fingerprint) && o2.initialOFingerprint().equals(fingerprint), "restored fingerprint equals saved");
            var wrong = new UVNetwork(cfg);
            try { check(!wrong.loadInitialOBranch(snap.toString(), "b".repeat(64)), "wrong producer hash rejected"); } finally { wrong.shutdown(); }
            var asS50 = new UVNetwork(cfg);
            try { check(!asS50.loadInitialS50Branch(snap.toString(), SRC), "O snapshot rejected by S50 contract"); } finally { asS50.shutdown(); }
            // Byte-level integrity is the runner's job (snapshot SHA-256 bound in complete.json); the loader
            // must still reject a structurally corrupt file rather than restore part of it.
            byte[] bytes = Files.readAllBytes(snap); Path bad = tmp.resolve("truncated.dat"); Files.write(bad, Arrays.copyOf(bytes, bytes.length * 2 / 3));
            var tampered = new UVNetwork(cfg);
            try { check(!tampered.loadInitialOBranch(bad.toString(), SRC), "truncated snapshot rejected"); } catch (RuntimeException e) { passed++; } finally { tampered.shutdown(); }

            order = adjacencyOrder(o1);
            var before = balances(o1);
            // O-branch cell A: per-edge law; cell B: node-level law; S50 branch: allocation then node-level law.
            var ra = o1.reassignInitialFeePolicies(new FeePolicyLaw.Spec("per_edge", 77, Map.of(), 0, null), roles);
            var rb = o2.reassignInitialFeePolicies(NODE_SPEC, roles);
            check(!ra.get("directed_policy_table_sha256").equals(rb.get("directed_policy_table_sha256")), "fee laws differ");
            check(adjacencyOrder(o1).equals(order) && adjacencyOrder(o2).equals(order), "routing iteration order preserved");
            check(balances(o1).equals(before) && balances(o2).equals(before), "fees do not move liquidity");
            for (var node : o2.getUVNodeList().values()) for (var c : node.getChannels().values()) {
                String src = node.getPubKey();
                for (var viewer : o2.getUVNodeList().values()) {
                    if (!viewer.getChannelGraph().hasChannel(c.getChannelId())) continue;
                    for (var edge : viewer.getChannelGraph().getAdjMap().getOrDefault(src, Set.of()))
                        if (edge.id().equals(c.getChannelId())) check(edge.policy() == c.getPolicy(src), "every gossip view carries the new policy");
                }
            }
            JournalTrafficExporter.build(o2, "fixture", "start", JournalCaptureExporter.object("fixture", true));
            rejects("fee reassignment is initial-only", () -> o2.reassignInitialFeePolicies(NODE_SPEC, roles));
            rejects("O branch save after fee step", () -> o2.saveInitialOBranch(tmp.resolve("y.dat").toString(), SRC));
            var receipt = s50.initializeExactS50(1234);
            check(receipt.get("invoice_requests").equals(0), "S50 from the O branch");
            s50.reassignInitialFeePolicies(NODE_SPEC, roles);
            Map<String, String> t2 = new TreeMap<>(), t3 = new TreeMap<>();
            for (var node : o2.getUVNodeList().values()) for (var c : node.getChannels().values()) t2.put(c.getChannelId() + node.getPubKey(), c.getPolicy(node.getPubKey()).getBaseFee() + ":" + c.getPolicy(node.getPubKey()).getFeePpm());
            for (var node : s50.getUVNodeList().values()) for (var c : node.getChannels().values()) t3.put(c.getChannelId() + node.getPubKey(), c.getPolicy(node.getPubKey()).getBaseFee() + ":" + c.getPolicy(node.getPubKey()).getFeePpm());
            check(t2.equals(t3), "same fee seed and law -> identical policies on O and S50 branches");

            // Scheduled replay with per-invoice fee caps and the route log.
            o2.enableRouteLog();
            var nodes = new ArrayList<>(new TreeSet<>(o2.getUVNodeList().keySet()));
            var rows = new ArrayList<UVNetwork.ScheduledInvoice>();
            var rng = new Random(5);
            for (int i = 0; i < 12; i++) {
                String s = nodes.get(rng.nextInt(nodes.size())), d; do { d = nodes.get(rng.nextInt(nodes.size())); } while (d.equals(s));
                int amount = 1000 + rng.nextInt(20000);
                rows.add(new UVNetwork.ScheduledInvoice(i + 1, i / 3, s, d, amount, i == 0 ? 0 : Math.max(1000, amount / 100)));
            }
            var batch = o2.generateScheduledInvoiceBatch("seg1", 0.25, 4, rows, 1000, 20000).snapshot();
            var summary = (Map<?, ?>) batch.get("summary");
            int successes = ((Number) summary.get("successful")).intValue();
            var routes = o2.drainSettledRoutes();
            check(routes.size() == successes, "one route per success: " + routes.size() + " vs " + successes);
            for (var r : routes) {
                long fee = ((Number) r.get("planned_fee_sat")).longValue();
                int amount = ((Number) r.get("amount_sat")).intValue();
                check(fee <= Math.max(1000, amount / 100), "route respects its invoice fee cap");
                check(!((List<?>) r.get("hops")).isEmpty(), "route has hops");
            }
            var after = JournalTrafficExporter.build(o2, "fixture", "seg1", JournalCaptureExporter.object("fixture", true));
            check(after.publicGraph().equals(JournalTrafficExporter.build(o2, "fixture", "seg1b", JournalCaptureExporter.object("fixture", true)).publicGraph()), "settled public graph stable");
            rejects("fee reassignment after invoices", () -> o1.reassignInitialFeePolicies(NODE_SPEC, roles) /* o1 already reassigned */);
            rejects("bad fee cap", () -> o1.generateScheduledInvoiceBatch("bad", 0.25, 1,
                    List.of(new UVNetwork.ScheduledInvoice(1, 0, nodes.get(0), nodes.get(1), 1000, -2),
                            new UVNetwork.ScheduledInvoice(2, 0, nodes.get(1), nodes.get(2), 1000, -1),
                            new UVNetwork.ScheduledInvoice(3, 0, nodes.get(2), nodes.get(3), 1000, -1)), 1000, 20000));
            System.out.println("  live fixture: " + nodes.size() + " nodes, " + balances(o2).size() + " channels, " + successes + "/12 scheduled successes, roles " + new TreeMap<>(countRoles(roles)));
        } finally { o1.shutdown(); o2.shutdown(); s50.shutdown(); }
    }
    static Map<String, Integer> countRoles(Map<String, String> roles) { var m = new TreeMap<String, Integer>(); roles.values().forEach(r -> m.merge(r, 1, Integer::sum)); return m; }

    static void bootstrapUnchangedWhenDisabled(Path tmp) throws Exception {
        // With roles disabled, the bootstrap code path draws nothing extra (null role model).
        var cfg = config(tmp, "plain", "");
        var n = new UVNetwork(cfg);
        try {
            n.bootstrapNetwork(); n.pauseAndDrain(5000);
            check(n.getPaymentRoles() == null, "no role model when disabled");
        } finally { n.shutdown(); }
    }

    public static void main(String[] args) throws Exception {
        Path tmp = Files.createTempDirectory("traffic-roles-tests");
        roleModel(tmp);
        failureCategories();
        feeLaw(tmp);
        liveBranching(tmp);
        bootstrapUnchangedWhenDisabled(tmp);
        System.out.println("Traffic-role producer checks passed (" + passed + " assertions)");
    }
}
