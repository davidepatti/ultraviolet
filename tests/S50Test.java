package tests;

import network.SymmetricAllocation;
import network.UVChannel;
import network.UVNetwork;
import java.nio.file.Files;

public final class S50Test {
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    interface Action { void run() throws Exception; }
    static void rejects(Action a) throws Exception {
        try { a.run(); } catch (IllegalStateException | IllegalArgumentException e) { return; }
        throw new AssertionError("Expected rejection");
    }
    static void field(Object o, String key, Object value) throws Exception {
        var f = o.getClass().getDeclaredField(key); f.setAccessible(true); f.set(o, value);
    }
    public static void main(String[] args) throws Exception {
        for (int c : new int[]{1, 2, 3, 99, 100, 101, 1000000, 1000001, Integer.MAX_VALUE}) {
            for (boolean orientation : new boolean[]{false, true}) {
                var ch = new UVChannel("fixture", "a", "b", c, c/100, 0, orientation);
                long moved = SymmetricAllocation.apply(ch);
                check(moved == Math.abs((long)(orientation ? c : 0)-c/2));
                check(ch.getBalance("a") == c/2 && ch.getBalance("b") == c-c/2);
                int commits = ch.getLastCommitNumber();
                check(SymmetricAllocation.apply(ch) == 0 && ch.getLastCommitNumber() == commits);
            }
        }
        var tiny = new UVChannel("tiny", "a", "b", 1000001, 10000, 500000, true);
        check(SymmetricAllocation.apply(tiny) == 1 && tiny.getBalance("a") == 500000);
        var rejected = new UVChannel("reject", "a", "b", 100, 1, 0, true) {
            @Override public synchronized void newCommitment(int a, int b) { }
        };
        rejects(() -> SymmetricAllocation.apply(rejected));
        var tmp = Files.createTempDirectory("s50-tests");
        UVNetwork.Log = ignored -> {};
        try (var n = new NetworkClose(JournalTrafficTest.fixture(tmp, "initial"))) {
            n.network.resume(); rejects(() -> n.network.initializeExactS50(1234));
            n.network.pauseAndDrain(1000);
            field(n.network, "activeInvoiceTasks", 1); rejects(() -> n.network.initializeExactS50(1234));
            field(n.network, "activeInvoiceTasks", 0);
            var ch = n.network.getUVNode("a").getChannels().values().iterator().next();
            ch.resolveNode(ch.getNode1PubKey()).setPending(1); rejects(() -> n.network.initializeExactS50(1234));
            ch.resolveNode(ch.getNode1PubKey()).setPending(0);
            var provenance = stats.JournalCaptureExporter.object("fixture", true);
            var opening = stats.JournalS50Exporter.build(n.network, "fixture", "o", provenance);
            field(n.network, "activeMutations", 1); rejects(() -> n.network.initializeExactS50(1234));
            field(n.network, "activeMutations", 0);
            var receipt = n.network.initializeExactS50(1234);
            var balanced = stats.JournalS50Exporter.build(n.network, "fixture", "s50", provenance);
            check(opening.publicGraph().equals(balanced.publicGraph()) && opening.privateState().equals(balanced.privateState()));
            check(balanced.equals(stats.JournalS50Exporter.build(n.network, "fixture", "s50", provenance)));
            check(receipt.get("invoice_requests").equals(0));
            rejects(() -> n.network.initializeExactS50(1234));
            n.network.resume();
            var batch = n.network.generateInvoiceBatch("s50-fixture", 0.5, 1, 1000, 100000, 1000, 10000);
            check(((Number)((misc.json.JsonObject)batch.snapshot().get("summary")).get("requested")).intValue() == 2);
            rejects(() -> n.network.initializeExactS50(1234));
        }
        try (var n = new NetworkClose(JournalTrafficTest.fixture(tmp, "preflight"))) {
            var ch = n.network.getUVNode("c").getChannels().get("fixture_c_d");
            ch.newCommitment(50000, 50000);
            rejects(() -> n.network.initializeExactS50(1234));
            check(n.network.getUVNode("a").getChannels().get("fixture_a_b").getLastCommitNumber() == 0);
        }
        System.out.println("S50 exact allocation and lifecycle checks passed");
    }
    record NetworkClose(UVNetwork network) implements AutoCloseable {
        public void close() { network.shutdown(); }
    }
}
