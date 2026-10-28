package tests;

import misc.UVConfig;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.UVNetwork;
import stats.GraphJsonExporter;
import topology.PathFinderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Public-entry-point regressions; temporary fixtures only, no scientific workloads. */
public final class SimulationConsistencyTest {
    private SimulationConsistencyTest() {}
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void identityAndPersistence(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "ordinary-identities");
        var restored = JournalTrafficTest.fixture(tmp, "restored-identities");
        try {
            network.resume();
            var worker = network.getUVNode("a").p2pHandler;
            network.resume(); network.startP2PNetwork();
            check(worker == network.getUVNode("a").p2pHandler, "Repeated start duplicated services");
            var first = network.getUVNode("d").generateInvoice(1000, "default", true);
            var second = network.getUVNode("d").generateInvoice(1000, "default", true);
            check(!first.getHash().equals(second.getHash()), "Ordinary descriptions still alias identities");
            var one = network.submitInvoice(network.getUVNode("a"), first, 1000, false);
            var two = network.submitInvoice(network.getUVNode("a"), second, 1000, false);
            network.stopP2PNetwork(); // the legacy method must now drain both tasks
            check(one.isDone() && two.isDone() && one.get().success() && two.get().success(), "Stop omitted queued invoices");
            check(network.isPaused() && network.unsettledState().isEmpty(), "Ordinary stop is not settled");
            var reports = network.getUVNode("a").getInvoiceReports(); reports.clear();
            check(network.getUVNode("a").getInvoiceReports().size() == 2, "Report getter exposes mutable storage");
            var snapshot = tmp.resolve("consistency-roundtrip.dat");
            network.saveStatus(snapshot.toString());
            check(restored.loadStatus(snapshot.toString()), "Restore failed: " + restored.getLastLoadStatusError());
            check(restored.isPaused(), "Restored services must be stopped");
            check(PathFinderFactory.strategyOf(restored.getUVNode("a").getPathFinder()) == PathFinderFactory.Strategy.LND,
                    "Saved routing choice was reset");
            check(restored.getUVNode("a").getInvoiceReports().size() == 2, "Reports lost on restore");
            restored.resume();
            var third = restored.getUVNode("d").generateInvoice(1000, "default", true);
            check(!List.of(first.getHash(), second.getHash()).contains(third.getHash()), "Identity allocator reset on restore");
            check(restored.getUVNode("a").processInvoice(third, 1000, false).success(), "Direct routing failed after restore");
            try { restored.getUVNode("a").processInvoice(first, 1000, false); throw new AssertionError("Reprocessed saved invoice"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("already submitted"), "Wrong duplicate error"); }
            restored.pauseAndDrain();
            check(restored.unsettledState().isEmpty(), "Restore left pending state");
        } finally { network.shutdown(); restored.shutdown(); }
    }

    public static void queuedCapture(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "queued-capture");
        var pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            var original = network.getUVNode("a");
            var gated = new network.UVNode(network, "a", "a", 100000, original.getProfile()) {
                @Override public stats.GlobalStats.NodeStats.InvoiceReport processInvoice(protocol.LNInvoice invoice, int fees, boolean ui) {
                    entered.countDown();
                    try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                    return super.processInvoice(invoice, fees, ui);
                }
            };
            gated.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, network.getConfig()));
            for (var channel : original.getChannels().values()) { gated.configureChannel(channel); gated.getChannelGraph().addLNChannel(channel); }
            // Preserve the complete observer graph for the routing fixture.
            var graphField = network.UVNode.class.getDeclaredField("channelGraph"); graphField.setAccessible(true); graphField.set(gated, original.getChannelGraph());
            var registry = UVNetwork.class.getDeclaredField("uvnodes"); registry.setAccessible(true);
            @SuppressWarnings("unchecked") var nodes = (java.util.Map<String, network.UVNode>) registry.get(network);
            nodes.put("a", gated);
            network.resume();
            var job = network.submitInvoice(gated, network.getUVNode("d").generateInvoice(1000, "queued"), 1000, false);
            check(entered.await(2, TimeUnit.SECONDS), "Submitted task did not start");
            var capture = pool.submit(() -> network.withSettledState(() -> gated.getInvoiceReports().size()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!network.isDraining() && System.nanoTime() < deadline) Thread.sleep(1);
            check(network.isDraining(), "Capture did not close admission while task was active");
            release.countDown();
            check(capture.get(5, TimeUnit.SECONDS) == 1 && job.get().success(), "Capture observed unfinished submitted work");
            check(network.isRunning(), "Live export did not restore running state");
            GraphJsonExporter.buildOmniscientGraph(network);
            check(network.isRunning(), "Ordinary graph export lost running state");
        } finally { release.countDown(); pool.shutdownNow(); network.shutdown(); }
    }

    public static void concurrentBatchBoundary(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "batch-boundary");
        var pool = Executors.newSingleThreadExecutor();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            Path snapshot = tmp.resolve("batch-before.dat"); network.saveStatus(snapshot.toString());
            var graphField = network.UVNode.class.getDeclaredField("channelGraph"); graphField.setAccessible(true);
            var registry = UVNetwork.class.getDeclaredField("uvnodes"); registry.setAccessible(true);
            @SuppressWarnings("unchecked") var nodes = (java.util.Map<String, network.UVNode>) registry.get(network);
            for (var original : network.getUVNodeList().values()) {
                var gated = new network.UVNode(network, original.getPubKey(), original.getPubKey(), 100000, original.getProfile()) {
                    @Override public stats.GlobalStats.NodeStats.InvoiceReport processInvoice(protocol.LNInvoice invoice, int fees, boolean ui) {
                        entered.countDown();
                        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                        return super.processInvoice(invoice, fees, ui);
                    }
                };
                gated.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, network.getConfig()));
                for (var channel : original.getChannels().values()) gated.configureChannel(channel);
                graphField.set(gated, original.getChannelGraph());
                nodes.put(gated.getPubKey(), gated);
            }
            network.resume();
            var batch = pool.submit(() -> network.generateInvoiceBatch("Manual batch / 1", 1, 1, 1000, 1000, 1000, 4000));
            check(entered.await(2, TimeUnit.SECONDS), "Batch did not start");
            try { network.withSettledState(() -> 0); throw new AssertionError("Concurrent capture interrupted the batch"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("batch owns"), "Wrong capture rejection"); }
            check(!network.loadStatus(snapshot.toString()) && network.isHealthy(), "Concurrent reload replaced an active batch");
            release.countDown();
            check(Boolean.TRUE.equals(batch.get(5, TimeUnit.SECONDS).snapshot().get("settled")), "Rejected concurrent operation broke batch settlement");
            check(network.isPaused() && network.unsettledState().isEmpty(), "Batch boundary retained work");
        } finally { release.countDown(); pool.shutdownNow(); network.shutdown(); }
    }

    private static JsonObject command(UVNetwork network, Path tmp, JsonObject spec) throws Exception {
        var type = Class.forName("DseExperimentRunner");
        var runner = type.getConstructor(Path.class, Path.class, Path.class).newInstance(tmp, tmp, tmp);
        var execute = type.getDeclaredMethod("executeCommand", UVNetwork.class, JsonObject.class); execute.setAccessible(true);
        try { return (JsonObject) execute.invoke(runner, network, spec); }
        catch (java.lang.reflect.InvocationTargetException error) { throw new AssertionError("DSE command failed", error.getCause()); }
    }

    private static JsonObject object(Object... pairs) {
        var value = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }

    public static void dseCommands(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "ordinary-dse");
        try {
            network.resume();
            var route = object("command", "route", "sender", "a", "destination", "d", "amount", 1000, "message", "same");
            var first = command(network, tmp, route); var second = command(network, tmp, route);
            check(!first.get("invoice_hash").equals(second.get("invoice_hash")) && Boolean.TRUE.equals(second.get("success")), "DSE reused invoice outcome");
            var result = command(network, tmp, object("command", "inv", "node_events_per_block", 1, "blocks", 1, "min_amt", 1000, "max_amt", 1000));
            var batch = (JsonObject) result.get("invoice_batch");
            check(Boolean.TRUE.equals(batch.get("settled")), "DSE returned before settled accounting");
            var rows = (JsonArray) batch.get("requests");
            check(rows.size() == 4 && network.isRunning(), "DSE batch completion/resume failed");
            for (Object row : rows) check(((JsonObject) row).get("amount_sat").equals(1000), "Equal amount bounds were expanded");
            Path saved = tmp.resolve("dse-load.dat"), config = tmp.resolve("dse-load.properties");
            network.saveStatus(saved.toString());
            Files.writeString(config, "@include=" + network.getConfig().getSourcePath() + "\nlogfile=" + tmp.resolve("dse-load.log") + "\n");
            var restored = new UVNetwork(new UVConfig(config.toString()));
            try {
                var loaded = command(restored, tmp, object("command", "boot", "mode", "load", "file", saved.toString()));
                check("load".equals(loaded.get("mode")) && restored.isRunning(), "DSE boot ignored saved-state mode");
                check(restored.getUVNode("a").getInvoiceReports().size() == network.getUVNode("a").getInvoiceReports().size(), "DSE boot replaced saved results");
                restored.pauseAndDrain();
            } finally { restored.shutdown(); }
        } finally { network.shutdown(); }
    }

    public static void terminalCommands(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "terminal-source");
        Path initial = tmp.resolve("terminal-in.dat"), output = tmp.resolve("terminal-out.dat"), graph = tmp.resolve("terminal-graph.json");
        String config = network.getConfig().getSourcePath();
        network.saveStatus(initial.toString()); network.shutdown();
        Path log = tmp.resolve("terminal.log");
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "UltraViolet", config).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        String input = "load\n" + initial + "\n\nt\n\n"
                + "route\na\nd\n1000\n1000\nsame\nlnd\n\n"
                + "route\na\nd\n1000\n1000\nsame\nlnd\n\n"
                + "t\n\ngexpall\n" + graph + "\n\nsave\n" + output + "\n\nq\n\n";
        process.getOutputStream().write(input.getBytes(java.nio.charset.StandardCharsets.UTF_8)); process.getOutputStream().close();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("Terminal commands did not terminate"); }
        check(process.exitValue() == 0 && Files.isRegularFile(graph) && Files.isRegularFile(output), "Terminal failed: " + Files.readString(log));
        var restored = JournalTrafficTest.fixture(tmp, "terminal-check");
        try {
            check(restored.loadStatus(output.toString()), "Terminal snapshot failed to load");
            var rows = restored.getUVNode("a").getInvoiceReports();
            check(rows.size() == 2 && rows.stream().allMatch(stats.GlobalStats.NodeStats.InvoiceReport::success), "Terminal stop/save missed manual requests");
        } finally { restored.shutdown(); }
    }

    public static void smallBootstrap(Path tmp) throws Exception {
        Path config = tmp.resolve("small-bootstrap.properties");
        String values = "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nseed=71\nbootstrap_nodes=6\nbootstrap_blocks=6\nmax_threads=12\nblocktime_ms=10\nnode_services_tick_ms=2\ngossip_flush_period_ms=2\nminimum_depth=1\nlogfile=" + tmp.resolve("small-bootstrap.log") + "\n";
        for (String profile : List.of("small", "medium", "hub")) values += "profile." + profile + ".min_channels=1\nprofile." + profile + ".max_channels=2\n";
        Files.writeString(config, values);
        var network = new UVNetwork(new UVConfig(config.toString()));
        try {
            command(network, tmp, object("command", "boot"));
            network.pauseAndDrain(3000);
            check(network.isBootstrapCompleted() && network.isPaused() && network.unsettledState().isEmpty(), "Bootstrap returned unresolved state");
            check(network.getUVNodeList().size() == 6, "Bootstrap registry lost nodes");
            for (var node : network.getUVNodeList().values()) check(node.getOnchainPending() == 0, "Opening reservation leaked");
        } finally { network.shutdown(); }
    }
}
