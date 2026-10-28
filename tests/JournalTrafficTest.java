package tests;

import message.MsgUpdateAddHTLC;
import message.OnionLayer;
import misc.UVConfig;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;
import stats.JournalCaptureExporter;
import stats.InvoiceBatch;
import stats.JournalTrafficExporter;
import topology.PathFinderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Small handcrafted state/route fixtures; no generated pilot topology or learner. */
public final class JournalTrafficTest {
    private JournalTrafficTest() {}
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    @FunctionalInterface private interface Action { void run() throws Exception; }
    private static void rejects(Action action, String text) throws Exception {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException error) {
            check(error.getMessage().contains(text), "Unexpected failure: " + error); return;
        }
        throw new AssertionError("Expected rejection: " + text);
    }
    private static void field(Object object, String name, Object value) throws Exception {
        var f = object.getClass().getDeclaredField(name); f.setAccessible(true); f.set(object, value);
    }

    static UVNetwork fixture(Path tmp, String name) throws Exception {
        Path configPath = tmp.resolve(name + ".properties");
        Files.writeString(configPath, "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nseed=71\nbootstrap_nodes=4\nmax_threads=8\nblocktime_ms=100\nnode_services_tick_ms=5\nlogfile=" + tmp.resolve(name + ".log") + "\n");
        var config = new UVConfig(configPath.toString());
        var network = new UVNetwork(config);
        for (String id : List.of("a", "b", "c", "d")) {
            var node = new UVNode(network, id, id, 100000, config.getNodeProfile("default"));
            node.setLocalRndGenerator(new Random(71));
            node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, config));
            network.registerNode(node);
        }
        channel(network, "a", "b", true, 0);
        channel(network, "b", "d", false, 0); // Cheap path fails at b's remote zero-liquidity direction.
        channel(network, "a", "c", true, 0);
        channel(network, "c", "d", true, 100000); // Alternate route forwards with a 100-sat fee.
        field(network, "pubkeys_list", new ArrayList<>(List.of("a", "b", "c", "d")));
        field(network, "bootstrap_completed", true);
        field(network, "bootstrapWorkersTerminated", true);
        network.startP2PNetwork();
        network.pauseAndDrain(1000);

        return network;
    }

    private static void channel(UVNetwork n, String a, String b, boolean fundedA, int fee) {
        var channel = new UVChannel("fixture_" + a + "_" + b, a, b, 100000, 1000, 0, fundedA);
        channel.setPolicy(a, new LNChannel.Policy(40, fee, 0));
        channel.setPolicy(b, new LNChannel.Policy(40, fee, 0));
        n.getUVNode(a).configureChannel(channel); n.getUVNode(b).configureChannel(channel);
        for (var node : n.getUVNodeList().values()) node.getChannelGraph().addLNChannel(channel);
    }

    public static void lifecycleAndPayments(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-lifecycle");
        try {
            var p = JournalCaptureExporter.object("fixture", true);
            var opening = JournalTrafficExporter.build(n, "fixture", "o", p);
            var node = n.getUVNode("a"); var profile = node.getProfile(); var rng = node.getLocalRndGenerator();
            int expectedNext = new Random(71).nextInt();
            n.resume();
            n.resume(); // repeated resume must not duplicate workers
            rejects(() -> JournalTrafficExporter.build(n, "fixture", "running", p), "barrier");
            var invoice = n.getUVNode("d").generateInvoiceWithIdentity(1000, "same description", 100001);
            var report = node.processInvoice(invoice, 1000);
            check(report.success() && report.attempted_paths() == 2 && report.attempt_failed_temporary_channel() == 1,
                    "Alternative-path failure/retry did not produce a successful multihop payment: " + report);
            var aToC = n.getUVNode("a").getChannels().get("fixture_a_c");
            var cToD = n.getUVNode("c").getChannels().get("fixture_c_d");
            check(aToC.getBalance("a") == 98900 && cToD.getBalance("c") == 99000, "Multihop amount/fee settlement");
            var noLiquidity = n.getUVNode("a").generateInvoiceWithIdentity(90000, "insufficient local", 100002);
            var failure = n.getUVNode("c").processInvoice(noLiquidity, 1000);
            check(!failure.success() && failure.filtered_local_liquidity() > 0, "Local-liquidity filtering");
            n.pauseAndDrain(2000);
            var evolved = JournalTrafficExporter.build(n, "fixture", "t", p);
            check(evolved.publicGraph().equals(opening.publicGraph()) && evolved.privateState().equals(opening.privateState()), "Structure/profile changed");
            check(!evolved.truth().get("channels").equals(opening.truth().get("channels")), "Traffic did not change balances");
            rejects(() -> JournalCaptureExporter.validateRecords(new ArrayList<>(n.getUVNodeList().values())), "opening state");
            int height = n.getTimechain().getCurrentBlockHeight(); Thread.sleep(150);
            check(height == n.getTimechain().getCurrentBlockHeight() && evolved.equals(JournalTrafficExporter.build(n, "fixture", "t", p)), "Mutation while paused");
            n.resume();
            check(node.getProfile() == profile && node.getLocalRndGenerator() == rng && rng.nextInt() == expectedNext, "Profile/RNG reset at resume");
            var nextInvoice = n.getUVNode("d").generateInvoiceWithIdentity(1000, "same description", 100003);
            check(!nextInvoice.getHash().equals(invoice.getHash()), "Repeated descriptions alias invoice identities");
            rejects(() -> n.getUVNode("d").generateInvoiceWithIdentity(1000, "duplicate", 100003), "Duplicate");
            check(node.processInvoice(nextInvoice, 1000).success(), "Routing after resume");
            n.pauseAndDrain(2000);
            check(n.unsettledState().isEmpty() && aToC.getBalance("a") == 97800, "State continuity after second payment");
            n.resume();
            var batch = n.generateInvoiceBatch("batch-a", 1, 2, 1000, 2000, 1000, 3000).snapshot();
            check(n.isPaused() && ((JsonArray) batch.get("requests")).size() == 8, "Native batch completion/pause");
            // The identity mechanism consumes no traffic RNG: verify the native sampling sequence exactly.
            Random traffic = new Random(71); var ids = List.of("a", "b", "c", "d"); int row = 0;
            for (int block = 0; block < 2; block++) {
                traffic.nextDouble(1);
                for (int i = 0; i < 4; i++) {
                    String sender = ids.get(traffic.nextInt(4)), dest;
                    do { dest = ids.get(traffic.nextInt(4)); } while (dest.equals(sender));
                    int amount = traffic.nextInt(1001) + 1000;
                    var actual = (JsonObject) ((JsonArray) batch.get("requests")).get(row++);
                    check(actual.get("sender").equals(sender) && actual.get("destination").equals(dest)
                            && actual.get("amount_sat").equals(amount), "Native sampling law/RNG changed");
                    check(!actual.get("status").equals("unfinished"), "Lost submitted task");
                }
            }
            var second = JournalTrafficExporter.build(n, "fixture", "batch-a", p);
            n.resume();
            n.generateInvoiceBatch("batch-b", 1, 1, 1000, 2000, 1000, 3000);
            check(second.publicGraph().equals(JournalTrafficExporter.build(n, "fixture", "batch-b", p).publicGraph()), "Public fingerprint drift");
            n.resume();
            rejects(() -> n.generateInvoiceBatch("batch-a",1,1,1000,2000,1000,3000), "duplicate batch");
            n.pauseAndDrain(2000);
        } finally { n.shutdown(); }
    }

    public static void rejectionAndAccounting(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-reject");
        try {
            n.resume();
            var htlc = new MsgUpdateAddHTLC("fixture_a_b", 1, 1000, "hash", 100, null);
            n.getUVNode("b").getReceivedHTLC().put("orphan", htlc);
            check(!n.getUVNode("b").areQueuesEmpty(), "All ordinary queue checks must include received contracts");
            UVNetwork pending = n;
            rejects(() -> pending.pauseAndDrain(50), "deadline");
            check(n.getUVNode("b").getReceivedHTLC().containsKey("orphan"), "Barrier erased forwarding state");
            rejects(() -> JournalTrafficExporter.build(pending,"fixture","bad",new JsonObject()), "barrier");
        } finally { n.shutdown(); }
        n = fixture(tmp, "ot-accounting");
        try {
            n.resume();
            var batch = new InvoiceBatch("fixture",1,1,1000,2000000000,1000,4,0);
            for (int i=1;i<=3;i++) batch.request(i,"a",n.getUVNode("b").generateInvoiceWithIdentity(2000000000,"large",i),0);
            batch.exception(1,new IllegalStateException("injected task failure"),0);
            batch.injectionStopped(0);
            check(((Number)((JsonObject)batch.snapshot().get("summary")).get("requested_amount_sat")).longValue()==6000000000L,"Amount overflow");
            rejects(batch::requireCompleted,"Unfinished or exceptional");
            rejects(() -> batch.exception(1,new RuntimeException(),0),"duplicate invoice outcome");
            n.recordFailure("injected service fault",new IllegalStateException("fixture"));
            UVNetwork failed = n;
            rejects(() -> failed.pauseAndDrain(500),"integrity failure");
        } finally { n.shutdown(); }
    }

    public static void queuedTaskExceptions(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-queued");
        var pool = Executors.newSingleThreadExecutor();
        var release = new java.util.concurrent.CountDownLatch(1);
        try {
            // A slow failing worker touches no protocol queues. A queue-only barrier would return early.
            for (String id : List.of("a", "b", "c", "d")) {
                var original = n.getUVNode(id);
                var failing = new UVNode(n, id, id, 100000, original.getProfile()) {
                    @Override public stats.GlobalStats.NodeStats.InvoiceReport processInvoice(protocol.LNInvoice invoice, int fees, boolean showUi) {
                        try { if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out"); }
                        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        throw new IllegalStateException("injected delayed task failure");
                    }
                };
                for (var channel : original.getChannels().values()) failing.configureChannel(channel);
                var registry = UVNetwork.class.getDeclaredField("uvnodes"); registry.setAccessible(true);
                ((java.util.Map<String, UVNode>) registry.get(n)).put(id, failing);
            }
            n.resume();
            var job = pool.submit(() -> n.generateInvoiceBatch("queue", 4, 1, 1000, 2000, 1000, 2000));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            while ((n.getLastInvoiceBatch() == null || n.getLastInvoiceBatch().snapshot().get("injection_ended_at_utc") == null)
                    && System.nanoTime() < deadline) Thread.sleep(1);
            check(n.getLastInvoiceBatch() != null && n.getLastInvoiceBatch().snapshot().get("injection_ended_at_utc") != null, "Injection did not finish");
            release.countDown();
            try { job.get(5, java.util.concurrent.TimeUnit.SECONDS); throw new AssertionError("Exceptional batch completed"); }
            catch (java.util.concurrent.ExecutionException expected) { check(expected.getCause().getMessage().contains("exceptional invoice"), "Wrong batch failure"); }
            var summary = (JsonObject) n.getLastInvoiceBatch().snapshot().get("summary");
            check(summary.get("requested").equals(16) && summary.get("completed").equals(16)
                    && summary.get("exceptional").equals(16) && summary.get("unfinished").equals(0), "Queued invoice tasks were lost");
            rejects(() -> JournalTrafficExporter.build(n,"fixture","bad",new JsonObject()), "barrier");
        } finally { release.countDown(); pool.shutdownNow(); n.shutdown(); }
    }

    public static void expiryAndTimeout(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-expiry");
        try {
            n.resume();
            var invoice = n.getUVNode("b").generateInvoiceWithIdentity(1000,"expired",100001);
            var channel = n.getUVNode("a").getChannels().get("fixture_a_b");
            var message = new MsgUpdateAddHTLC(channel.getChannelId(),channel.getNextHTLCid(),1000,invoice.getHash(),-1,
                    new OnionLayer(new OnionLayer.Payload("00",1000,-1,invoice.getHash()),null));
            channel.reservePending("a",1000);
            n.getUVNode("a").getPendingHTLC().put(invoice.getHash(),message);
            n.getUVNode("b").getUpdateAddHTLCQueue().add(message);
            n.pauseAndDrain(2000);
            check(channel.getPending("a")==0 && channel.getBalance("a")==100000, "Expiry did not release reservation without transfer");
        } finally { n.shutdown(); }
        n = fixture(tmp, "ot-timeout");
        var pool = Executors.newSingleThreadExecutor();
        try {
            n.resume();
            // Freeze final recipient processing so c forwards, but no final outcome can return.
            var dest = n.getUVNode("d"); dest.p2pHandler.cancel(false); dest.setP2PServices(false);
            var invoice = dest.generateInvoiceWithIdentity(1000,"timeout",100001);
            UVNetwork timed = n;
            var future = pool.submit(() -> timed.getUVNode("a").processInvoice(invoice,1000));
            try { future.get(20,TimeUnit.SECONDS); throw new AssertionError("Timeout must fail every caller"); }
            catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause().getMessage().contains("integrity failure"), "Expected timeout integrity failure");
            }
            check(timed.getUVNode("a").getInvoiceReports().getLast().attempt_failed_timeout() > 0, "Timeout diagnostics missing");
            rejects(() -> timed.pauseAndDrain(100), "integrity failure");
            rejects(() -> JournalTrafficExporter.build(timed,"fixture","unsafe",new JsonObject()),"barrier");
            check(!dest.getUpdateAddHTLCQueue().isEmpty() && !timed.getUVNode("c").getReceivedHTLC().isEmpty()
                    && !timed.getUVNode("c").getPendingHTLC().isEmpty(), "Multihop timeout diagnostics were erased");
        } finally { pool.shutdownNow(); n.shutdown(); }
    }
}
