package network;

import message.MsgUpdateAddHTLC;
import message.MsgUpdateFailHTLC;
import message.MsgUpdateFulFillHTLC;
import misc.json.JsonObject;
import protocol.LNInvoice;
import stats.GraphJsonExporter;
import stats.InvoiceBatch;
import tests.JournalTrafficTest;

import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Controlled delivery/block interleavings, independent of P2P worker scheduling. */
public final class HTLCResolutionTest {
    private HTLCResolutionTest() {}
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        check(condition.getAsBoolean(), "Fixture did not reach expected state");
    }
    private static UVNetwork manual(Path tmp, String name) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, name, "blocktime_ms=1000000\n");
        network.resume();
        stopDelivery(network);
        return network;
    }
    private static void stopDelivery(UVNetwork network) {
        for (var node : network.getUVNodeList().values()) {
            node.p2pHandler.cancel(false);
            node.setP2PServices(false); // joins any node handler already in progress via its monitor
        }
    }
    private static void step(UVNetwork network, String id) {
        var node = network.getUVNode(id);
        node.setP2PServices(true);
        node.runServices();
        node.setP2PServices(false);
        network.requireHealthy();
    }
    private static void block(UVNetwork network) throws Exception {
        var tick = protocol.UVTimechain.class.getDeclaredMethod("tictocNextBlock"); tick.setAccessible(true);
        tick.invoke(network.getTimechain());
        network.onBlockCommitted(network.getTimechain().getCurrentBlockHeight());
    }
    private static MsgUpdateAddHTLC offer(UVNetwork network, String sender, LNInvoice invoice) throws Exception {
        await(() -> network.getUVNode(sender).getPendingHTLC().containsKey(invoice.getHash()));
        return network.getUVNode(sender).getPendingHTLC().get(invoice.getHash());
    }
    private static void settled(UVNetwork network) throws Exception {
        network.pauseAndDrain(2000);
        check(network.isHealthy() && network.unsettledState().isEmpty(), "Unresolved HTLC state at boundary: " + network.unsettledState());
        GraphJsonExporter.buildOmniscientGraph(network);
    }
    private static void fundedBalances(UVNetwork network, int aBalance, int cBalance) {
        check(network.getUVNode("a").getChannels().get("fixture_a_c").getBalance("a") == aBalance, "Wrong sender debit");
        check(network.getUVNode("c").getChannels().get("fixture_c_d").getBalance("c") == cBalance, "Wrong downstream debit");
    }

    public static void expiryWithoutReceiver(Path tmp) throws Exception {
        var network = manual(tmp, "resolved-expiry"); var pool = Executors.newSingleThreadExecutor();
        try {
            var invoice = network.getUVNode("d").generateInvoice(1000, "unresponsive receiver");
            var future = pool.submit(() -> network.getUVNode("a").processInvoice(invoice, 1000, false));
            offer(network, "a", invoice);
            step(network, "b"); step(network, "a"); // first route fails normally for remote liquidity
            await(() -> !network.getUVNode("c").getUpdateAddHTLCQueue().isEmpty());
            step(network, "c"); // second route reaches d, whose P2P service stays stopped
            var last = network.getUVNode("d").getUpdateAddHTLCQueue().peek();
            check(last != null, "No forwarded HTLC");
            while (network.getTimechain().getCurrentBlockHeight() <= last.getCLTVExpiry()) block(network);
            var report = future.get(3, TimeUnit.SECONDS);
            check(!report.success() && report.attempt_failed_timeout() == 1 && report.attempted_paths() == 2, "Expiry outcome/retry accounting: " + report);
            fundedBalances(network, 100000, 100000);
            settled(network);
            var receipt = new InvoiceBatch("resolved expiry", 1, 1, 1000, 1000, 1000, 1, 0);
            receipt.request(1, "a", invoice, 0); receipt.complete(1, report, 10); receipt.injectionStopped(1); receipt.markSettled(10, 0);
            check(Boolean.TRUE.equals(receipt.snapshot().get("settled")), "Resolved expiry cannot be accounted as a valid failure");
            network.saveStatus(tmp.resolve("resolved-expiry.dat").toString());
        } finally { pool.shutdownNow(); network.shutdown(); }
    }

    public static void lateFulfillment(Path tmp) throws Exception {
        var network = manual(tmp, "late-fulfillment"); var pool = Executors.newSingleThreadExecutor();
        try {
            var invoice = network.getUVNode("d").generateInvoiceWithIdentity(1000, "late fulfillment", 321);
            var future = pool.submit(() -> network.getUVNode("a").processInvoice(invoice, 1000, false));
            offer(network, "a", invoice); step(network, "b"); step(network, "a");
            await(() -> !network.getUVNode("c").getUpdateAddHTLCQueue().isEmpty());
            var firstHop = network.getUVNode("c").getUpdateAddHTLCQueue().peek();
            step(network, "c"); step(network, "d"); // preimage committed; returning messages deliberately delayed
            var late = network.getUVNode("c").getUpdateFulFillHTLCQueue().peek();
            check(late != null, "Missing fulfillment to delay");
            while (network.getTimechain().getCurrentBlockHeight() <= firstHop.getCLTVExpiry()) block(network);
            var report = future.get(3, TimeUnit.SECONDS);
            check(report.success() && report.attempt_failed_timeout() == 0, "Expiry overrode an already fulfilled downstream contract");
            fundedBalances(network, 98900, 99000);
            check(network.getUVNode("d").getChannels().get("fixture_c_d").getBalance("d") == 1000, "Recipient was not paid once");
            check(network.getUVNode("c").getChannels().get("fixture_a_c").getBalance("c") + 99000 == 100100, "Intermediary lost reimbursement/fee");
            network.getUVNode("c").getUpdateFulFillHTLCQueue().add(late);
            network.getUVNode("c").getUpdateFulFillHTLCQueue().add(late);
            network.getUVNode("a").getUpdateFulFillHTLCQueue().add(new MsgUpdateFulFillHTLC(firstHop.getChannel_id(), firstHop.getId(), 321));
            network.getUVNode("a").getUpdateFailHTLCQueue().add(new MsgUpdateFailHTLC(firstHop.getChannel_id(), firstHop.getId(), "attempt_timeout"));
            step(network, "c"); step(network, "a");
            fundedBalances(network, 98900, 99000);
            check(network.getUVNode("a").getInvoiceReports().size() == 1, "Late messages duplicated a report");
            settled(network);
        } finally { pool.shutdownNow(); network.shutdown(); }
    }

    public static void expiryRetryAndStaleReplies(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "expiry-retry", "blocktime_ms=1000000\n");
        // Two paths share a->b: a->b->d and a->b->c->d. Retrying must allocate a new ID on that same channel.
        network.getUVNode("a").getChannels().remove("fixture_a_c");
        network.getUVNode("c").getChannels().remove("fixture_a_c");
        var bc = new UVChannel("fixture_b_c", "b", "c", 100000, 1000, 0, true);
        bc.setPolicy("b", new LNChannel.Policy(40, 50000, 0)); bc.setPolicy("c", new LNChannel.Policy(40, 50000, 0));
        network.getUVNode("b").configureChannel(bc); network.getUVNode("c").configureChannel(bc);
        var graph = UVNode.class.getDeclaredField("channelGraph"); graph.setAccessible(true);
        var allChannels = new java.util.TreeMap<String, UVChannel>();
        for (var node : network.getUVNodeList().values()) allChannels.putAll(node.getChannels());
        for (var node : network.getUVNodeList().values()) {
            var view = new topology.ChannelGraph(node.getPubKey());
            for (var channel : allChannels.values()) view.addLNChannel(channel);
            graph.set(node, view);
        }
        network.resume(); stopDelivery(network);
        var pool = Executors.newSingleThreadExecutor();
        try {
            var invoice = network.getUVNode("d").generateInvoiceWithIdentity(1000, "retry", 654);
            var future = pool.submit(() -> network.getUVNode("a").processInvoice(invoice, 1000, false));
            var old = offer(network, "a", invoice);
            // Do not deliver the first attempt at all. Its channel outcome must become final first.
            while (network.getTimechain().getCurrentBlockHeight() <= old.getCLTVExpiry()) block(network);
            await(() -> !network.getUVNode("b").getUpdateAddHTLCQueue().isEmpty());
            var next = network.getUVNode("b").getUpdateAddHTLCQueue().peek();
            check(old.getChannel_id().equals(next.getChannel_id()) && old.getId() != next.getId(), "Retry fixture did not reuse the first channel");
            check(!HTLCResolution.Key.of(old).equals(HTLCResolution.Key.of(next)), "Retry reused a channel HTLC identity");
            network.getUVNode("a").getUpdateFulFillHTLCQueue().add(new MsgUpdateFulFillHTLC(old.getChannel_id(), old.getId(), 654));
            network.getUVNode("a").getUpdateFailHTLCQueue().add(new MsgUpdateFailHTLC(old.getChannel_id(), old.getId(), "attempt_timeout"));
            step(network, "a");
            check(!future.isDone() && network.getUVNode("a").getPendingHTLC().get(invoice.getHash()) == next, "Old reply resolved a new attempt");
            network.getUVNode("b").getUpdateAddHTLCQueue().add(next); // duplicate add must not reserve or forward twice
            step(network, "b"); step(network, "c"); step(network, "d"); step(network, "c"); step(network, "b"); step(network, "a");
            var report = future.get(3, TimeUnit.SECONDS);
            check(report.success() && report.attempted_paths() == 2 && report.attempt_failed_timeout() == 1, "Resolved timeout retry accounting: " + report);
            check(network.getUVNode("a").getChannels().get(old.getChannel_id()).getBalance("a") == 98850, "First channel did not debit exactly the successful attempt");
            check(bc.getBalance("b") == 98900 && network.getUVNode("c").getChannels().get("fixture_c_d").getBalance("c") == 99000,
                    "Retry failed to settle downstream amount and fees");
            settled(network);
        } finally { pool.shutdownNow(); network.shutdown(); }
    }

    public static void stalledClock(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "stalled-clock", "htlc_stall_timeout_ms=200\n");
        var pool = Executors.newSingleThreadExecutor();
        try {
            network.resume(); stopDelivery(network);
            var invoice = network.getUVNode("d").generateInvoice(1000, "stalled clock");
            var future = pool.submit(() -> network.getUVNode("a").processInvoice(invoice, 1000, false));
            offer(network, "a", invoice);
            network.getTimechain().stopAndAwait(1000);
            try { future.get(3, TimeUnit.SECONDS); throw new AssertionError("Stalled host became a normal payment failure"); }
            catch (java.util.concurrent.ExecutionException expected) {
                check(expected.getCause().getMessage().contains("stalled"), "Wrong watchdog failure");
            }
            check(!network.isHealthy() && !network.getUVNode("a").getPendingHTLC().isEmpty(), "Watchdog erased unresolved state");
            try { GraphJsonExporter.buildOmniscientGraph(network); throw new AssertionError("Unsafe export accepted"); }
            catch (IllegalStateException expected) { check(expected.getMessage().contains("integrity failure"), "Wrong export rejection"); }
        } finally { pool.shutdownNow(); network.shutdown(); }
    }

    public static void expiryFulfillmentRace(Path tmp) throws Exception {
        var network = manual(tmp, "expiry-fulfillment-race"); var pool = Executors.newSingleThreadExecutor();
        try {
            var invoice = network.getUVNode("d").generateInvoiceWithIdentity(1000, "expiry boundary", 987);
            var future = pool.submit(() -> network.getUVNode("c").processInvoice(invoice, 1000, false));
            var message = offer(network, "c", invoice);
            var tick = protocol.UVTimechain.class.getDeclaredMethod("tictocNextBlock"); tick.setAccessible(true);
            while (network.getTimechain().getCurrentBlockHeight() < message.getCLTVExpiry()) tick.invoke(network.getTimechain());
            // The block exists, but its resolution sweep has not run. A handler with an earlier timestamp still cannot fulfill.
            var outcome = network.htlcResolution().fulfill("d", message, 987);
            check(outcome.state() == HTLCResolution.State.FAILED, "Fulfillment crossed the expiry boundary before the block callback");
            network.onBlockCommitted(network.getTimechain().getCurrentBlockHeight());
            var report = future.get(3, TimeUnit.SECONDS);
            check(!report.success() && report.attempt_failed_timeout() == 1, "Expiry race was not reported as a resolved failure");
            check(network.getUVNode("c").getChannels().get("fixture_c_d").getBalance("c") == 100000, "Expiry race moved funds");
            settled(network);
        } finally { pool.shutdownNow(); network.shutdown(); }
    }

    public static void concurrentChannelPayments(Path tmp) throws Exception {
        var network = manual(tmp, "concurrent-channel-payments");
        try {
            var channel = network.getUVNode("a").getChannels().get("fixture_a_b");
            channel.newCommitment(50000, 50000);
            int commits = channel.getLastCommitNumber();
            var futures = new java.util.ArrayList<java.util.concurrent.Future<stats.GlobalStats.NodeStats.InvoiceReport>>();
            for (int i = 0; i < 16; i++) {
                var sender = network.getUVNode(i % 2 == 0 ? "a" : "b");
                var receiver = network.getUVNode(i % 2 == 0 ? "b" : "a");
                futures.add(network.submitInvoice(sender, receiver.generateInvoice(1000, "concurrent"), 1000, false));
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!futures.stream().allMatch(java.util.concurrent.Future::isDone) && System.nanoTime() < deadline) {
                step(network, "a"); step(network, "b"); Thread.yield();
            }
            for (var future : futures) check(future.get(1, TimeUnit.SECONDS).success(), "Concurrent direct-channel payment failed");
            check(channel.getBalance("a") == 50000 && channel.getBalance("b") == 50000
                    && channel.getLastCommitNumber() == commits + 16, "Concurrent transfers lost or duplicated a debit");
            settled(network);
        } finally { network.shutdown(); }
    }

    public static void batchExpiry(Path tmp) throws Exception {
        var network = JournalTrafficTest.fixture(tmp, "batch-expiry", "blocktime_ms=10\n");
        try {
            network.resume(); stopDelivery(network);
            var result = network.generateInvoiceBatch("timeout receipts", 4, 1, 1000, 1000, 1000, 5000).snapshot();
            check(Boolean.TRUE.equals(result.get("settled")) && HTLCResolution.MODEL.equals(result.get("htlc_resolution")), "Batch resolution contract missing");
            var summary = (JsonObject) result.get("summary");
            check(summary.get("requested").equals(16) && summary.get("completed").equals(16) && summary.get("unfinished").equals(0), "Batch lost resolved requests");
            int timeouts = network.getUVNodeList().values().stream().flatMap(n -> n.getInvoiceReports().stream()).mapToInt(r -> r.attempt_failed_timeout()).sum();
            check(timeouts > 0 && network.isHealthy(), "Batch did not exercise valid timeout outcomes");
            check(network.unsettledState().isEmpty(), "Batch returned before resolution");
        } finally { network.shutdown(); }
    }
}
