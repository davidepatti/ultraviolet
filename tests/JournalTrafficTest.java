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
import stats.JournalInvoiceBatch;
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

    private static UVNetwork fixture(Path tmp, String name) throws Exception {
        Path configPath = tmp.resolve(name + ".properties");
        Files.writeString(configPath, "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nseed=71\nbootstrap_nodes=4\nmax_threads=8\nblocktime_ms=100\nnode_services_tick_ms=5\nlogfile=" + tmp.resolve(name + ".log") + "\n");
        var config = new UVConfig(configPath.toString());
        var network = new UVNetwork(config);
        for (String id : List.of("a", "b", "c", "d")) {
            var node = new UVNode(network, id, id, 100000, config.getNodeProfile("default"));
            node.setLocalRndGenerator(new Random(71));
            node.setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, config));
            network.getUVNodeList().put(id, node);
        }
        channel(network, "a", "b", true, 0);
        channel(network, "b", "d", false, 0); // Cheap path fails at b's remote zero-liquidity direction.
        channel(network, "a", "c", true, 0);
        channel(network, "c", "d", true, 100000); // Alternate route forwards with a 100-sat fee.
        field(network, "pubkeys_list", new ArrayList<>(List.of("a", "b", "c", "d")));
        field(network, "bootstrap_completed", true);
        field(network, "bootstrapWorkersTerminated", true);
        network.startP2PNetwork();
        network.stopForJournalCapture(1000);
        network.beginJournalTraffic();
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
            n.resumeJournalTraffic();
            rejects(n::resumeJournalTraffic, "settled journal pause");
            rejects(() -> JournalTrafficExporter.build(n, "fixture", "running", p), "barrier");
            var invoice = n.getUVNode("d").generateJournalInvoice(1000, "same description", 100001);
            var report = node.processInvoiceForJournal(invoice, 1000);
            check(report.success() && report.attempted_paths() == 2 && report.attempt_failed_temporary_channel() == 1,
                    "Alternative-path failure/retry did not produce a successful multihop payment: " + report);
            var aToC = n.getUVNode("a").getChannels().get("fixture_a_c");
            var cToD = n.getUVNode("c").getChannels().get("fixture_c_d");
            check(aToC.getBalance("a") == 98900 && cToD.getBalance("c") == 99000, "Multihop amount/fee settlement");
            var noLiquidity = n.getUVNode("a").generateJournalInvoice(90000, "insufficient local", 100002);
            var failure = n.getUVNode("c").processInvoiceForJournal(noLiquidity, 1000);
            check(!failure.success() && failure.filtered_local_liquidity() > 0, "Local-liquidity filtering");
            n.pauseForJournalTraffic(2000);
            var evolved = JournalTrafficExporter.build(n, "fixture", "t", p);
            check(evolved.publicGraph().equals(opening.publicGraph()) && evolved.privateState().equals(opening.privateState()), "Structure/profile changed");
            check(!evolved.truth().get("channels").equals(opening.truth().get("channels")), "Traffic did not change balances");
            rejects(() -> JournalCaptureExporter.validateRecords(new ArrayList<>(n.getUVNodeList().values())), "opening state");
            int height = n.getTimechain().getCurrentBlockHeight(); Thread.sleep(150);
            check(height == n.getTimechain().getCurrentBlockHeight() && evolved.equals(JournalTrafficExporter.build(n, "fixture", "t", p)), "Mutation while paused");
            n.resumeJournalTraffic();
            check(node.getProfile() == profile && node.getLocalRndGenerator() == rng && rng.nextInt() == expectedNext, "Profile/RNG reset at resume");
            var nextInvoice = n.getUVNode("d").generateJournalInvoice(1000, "same description", 100003);
            check(!nextInvoice.getHash().equals(invoice.getHash()), "Repeated descriptions alias invoice identities");
            rejects(() -> n.getUVNode("d").generateJournalInvoice(1000, "duplicate", 100003), "Duplicate");
            check(node.processInvoiceForJournal(nextInvoice, 1000).success(), "Routing after resume");
            n.pauseForJournalTraffic(2000);
            check(n.journalUnsettledState().isEmpty() && aToC.getBalance("a") == 97800, "State continuity after second payment");
            n.resumeJournalTraffic();
            var batch = n.generateJournalInvoiceBatch("batch-a", 1, 2, 1000, 2000, 1000, 3000).snapshot();
            check(n.isPausedForJournalTraffic() && ((JsonArray) batch.get("requests")).size() == 8, "Native batch completion/pause");
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
            n.resumeJournalTraffic();
            n.generateJournalInvoiceBatch("batch-b", 1, 1, 1000, 2000, 1000, 3000);
            check(second.publicGraph().equals(JournalTrafficExporter.build(n, "fixture", "batch-b", p).publicGraph()), "Public fingerprint drift");
            n.resumeJournalTraffic();
            rejects(() -> n.generateJournalInvoiceBatch("batch-a",1,1,1000,2000,1000,3000), "duplicate batch");
            n.pauseForJournalTraffic(2000);
        } finally { n.shutdown(); }
    }

    public static void rejectionAndAccounting(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-reject");
        try {
            n.resumeJournalTraffic();
            var htlc = new MsgUpdateAddHTLC("fixture_a_b", 1, 1000, "hash", 100, null);
            n.getUVNode("b").getReceivedHTLC().put("orphan", htlc);
            check(n.getUVNode("b").areQueuesEmpty(), "Fixture should expose omission in old check");
            UVNetwork pending = n;
            rejects(() -> pending.pauseForJournalTraffic(50), "deadline");
            check(n.getUVNode("b").getReceivedHTLC().containsKey("orphan"), "Barrier erased forwarding state");
            rejects(() -> JournalTrafficExporter.build(pending,"fixture","bad",new JsonObject()), "barrier");
        } finally { n.shutdown(); }
        n = fixture(tmp, "ot-accounting");
        try {
            n.resumeJournalTraffic();
            var batch = new JournalInvoiceBatch("fixture",1,1,1000,2000000000,1000,4,0);
            for (int i=1;i<=3;i++) batch.request(i,"a",n.getUVNode("b").generateJournalInvoice(2000000000,"large",i),0);
            batch.exception(1,new IllegalStateException("injected task failure"),0);
            batch.injectionStopped(0);
            check(((Number)((JsonObject)batch.snapshot().get("summary")).get("requested_amount_sat")).longValue()==6000000000L,"Amount overflow");
            rejects(batch::requireCompleted,"Unfinished or exceptional");
            rejects(() -> batch.exception(1,new RuntimeException(),0),"duplicate invoice outcome");
            n.logException("injected service fault",new IllegalStateException("fixture"));
            UVNetwork failed = n;
            rejects(() -> failed.pauseForJournalTraffic(500),"service exception");
        } finally { n.shutdown(); }
    }

    public static void queuedTaskExceptions(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-queued");
        try {
            // A slow failing worker touches no protocol queues. A queue-only barrier would return early.
            for (String id : List.of("a", "b", "c", "d")) {
                var original = n.getUVNode(id);
                var failing = new UVNode(n, id, id, 100000, original.getProfile()) {
                    @Override public stats.GlobalStats.NodeStats.InvoiceReport processInvoiceForJournal(protocol.LNInvoice invoice, int fees) {
                        try { Thread.sleep(80); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                        throw new IllegalStateException("injected delayed task failure");
                    }
                };
                for (var channel : original.getChannels().values()) failing.configureChannel(channel);
                n.getUVNodeList().put(id, failing);
            }
            n.resumeJournalTraffic();
            rejects(() -> n.generateJournalInvoiceBatch("queue", 4, 1, 1000, 2000, 1000, 2000), "exceptional journal invoice");
            var summary = (JsonObject) n.getLastJournalBatch().snapshot().get("summary");
            check(summary.get("requested").equals(16) && summary.get("completed").equals(16)
                    && summary.get("exceptional").equals(16) && summary.get("unfinished").equals(0), "Queued invoice tasks were lost");
            rejects(() -> JournalTrafficExporter.build(n,"fixture","bad",new JsonObject()), "barrier");
        } finally { n.shutdown(); }
    }

    public static void expiryAndTimeout(Path tmp) throws Exception {
        var n = fixture(tmp, "ot-expiry");
        try {
            n.resumeJournalTraffic();
            var invoice = n.getUVNode("b").generateJournalInvoice(1000,"expired",100001);
            var channel = n.getUVNode("a").getChannels().get("fixture_a_b");
            var message = new MsgUpdateAddHTLC(channel.getChannelId(),channel.getNextHTLCid(),1000,invoice.getHash(),-1,
                    new OnionLayer(new OnionLayer.Payload("00",1000,-1,invoice.getHash()),null));
            channel.reservePending("a",1000);
            n.getUVNode("a").getPendingHTLC().put(invoice.getHash(),message);
            n.getUVNode("b").getUpdateAddHTLCQueue().add(message);
            n.pauseForJournalTraffic(2000);
            check(channel.getPending("a")==0 && channel.getBalance("a")==100000, "Expiry did not release reservation without transfer");
        } finally { n.shutdown(); }
        n = fixture(tmp, "ot-timeout");
        var pool = Executors.newSingleThreadExecutor();
        try {
            n.resumeJournalTraffic();
            // Freeze final recipient processing so c forwards, but no final outcome can return.
            var dest = n.getUVNode("d"); dest.p2pHandler.cancel(false); dest.setP2PServices(false);
            var invoice = dest.generateJournalInvoice(1000,"timeout",100001);
            UVNetwork timed = n;
            var future = pool.submit(() -> timed.getUVNode("a").processInvoiceForJournal(invoice,1000));
            var report = future.get(20,TimeUnit.SECONDS);
            check(report.attempt_failed_timeout()>0, "Expected native timeout evidence");
            rejects(() -> timed.pauseForJournalTraffic(100), "running journal");
            rejects(() -> JournalTrafficExporter.build(timed,"fixture","unsafe",new JsonObject()),"barrier");
            check(!dest.getUpdateAddHTLCQueue().isEmpty() && !timed.getUVNode("c").getReceivedHTLC().isEmpty()
                    && !timed.getUVNode("c").getPendingHTLC().isEmpty(), "Multihop timeout diagnostics were erased");
        } finally { pool.shutdownNow(); n.shutdown(); }
    }
}
