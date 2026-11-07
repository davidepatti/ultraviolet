package tests;

import misc.UVConfig;
import network.UVNetwork;
import network.UVNode;
import protocol.LNInvoice;
import stats.GlobalStats;
import topology.PathFinderFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Controlled admission/cancellation interleavings; no timing-dependent routing outcomes. */
public final class LifecycleConcurrencyTest {
    private LifecycleConcurrencyTest() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static int activeTasks(UVNetwork network) throws Exception {
        var field = UVNetwork.class.getDeclaredField("activeInvoiceTasks");
        field.setAccessible(true);
        return field.getInt(network);
    }

    private static void awaitDrain(UVNetwork network) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!network.isDraining() && System.nanoTime() < deadline) Thread.sleep(1);
        check(network.isDraining(), "Drain did not close admission while the task was gated");
    }

    private static void assertMutationRejected(UVNetwork network) {
        try (var mutation = network.beginMutation()) {
            throw new AssertionError("Mutation entered a settled capture");
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains("draining"), "Wrong admission error");
        }
    }

    private static final class AdmissionNetwork extends UVNetwork {
        Mutation slippedMutation;
        AdmissionNetwork(UVConfig config) { super(config); }

        @Override public void pauseAndDrain() {
            super.pauseAndDrain();
            // Deterministically widen the old handoff: another public API can admit
            // a mutation once ordinary pause reopens admission. Capture must use
            // its private retained-admission boundary rather than this public method.
            slippedMutation = beginMutation();
        }
    }

    public static void captureAdmission(Path tmp) throws Exception {
        Path config = tmp.resolve("capture-admission.properties");
        Files.writeString(config, "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nlogfile=" + tmp.resolve("capture-admission.log") + "\n");
        var network = new AdmissionNetwork(new UVConfig(config.toString()));
        try {
            network.withSettledState(() -> {
                check(network.slippedMutation == null, "Capture admitted a mutation between drain and callback");
                assertMutationRejected(network);
                return null;
            });
            check(!network.isRunning() && !network.isDraining(), "Paused capture changed running state");
            network.resume();
            network.withSettledState(() -> {
                check(network.slippedMutation == null, "Capture admitted a mutation between drain and callback");
                check(network.isDraining(), "Capture lost admission ownership");
                assertMutationRejected(network);
                network.withSettledState(() -> {
                    assertMutationRejected(network);
                    return null;
                });
                check(network.isDraining(), "Nested capture reopened outer admission");
                assertMutationRejected(network);
                try { network.pauseAndDrain(1000); }
                catch (InterruptedException error) { throw new AssertionError(error); }
                check(network.isDraining(), "Nested ordinary pause reopened outer admission");
                return null;
            });
            check(network.isRunning() && !network.isDraining(), "Capture failed to restore running admission");
            try {
                network.withSettledState(() -> { throw new IllegalArgumentException("fixture capture failure"); });
                throw new AssertionError("Capture swallowed callback failure");
            } catch (IllegalArgumentException expected) {
                check(expected.getMessage().equals("fixture capture failure"), "Wrong capture failure");
            }
            check(network.isRunning() && !network.isDraining(), "Failed callback left admission closed");
            network.pauseAndDrain(1000);
            network.withSettledState(() -> { assertMutationRejected(network); return null; });
            check(!network.isRunning() && !network.isDraining(), "Paused capture changed running state");
            try (var mutation = network.beginMutation()) {
                check(!network.isDraining(), "Capture did not release admission");
            }
        } finally {
            if (network.slippedMutation != null) network.slippedMutation.close();
            network.shutdown();
        }
    }

    private static final class GatedSender extends UVNode {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch interrupted = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);

        GatedSender(UVNetwork network, UVNode original) throws Exception {
            super(network, original.getPubKey(), original.getAlias(), 100000, original.getProfile());
            setPathFinder(PathFinderFactory.of(PathFinderFactory.Strategy.LND, network.getConfig()));
            for (var channel : original.getChannels().values()) configureChannel(channel);
            var graph = UVNode.class.getDeclaredField("channelGraph");
            graph.setAccessible(true);
            graph.set(this, original.getChannelGraph());
        }

        @Override public GlobalStats.NodeStats.InvoiceReport processInvoice(LNInvoice invoice, int fees, boolean ui) {
            entered.countDown();
            try {
                while (true) {
                    try {
                        if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test gate timed out");
                        break;
                    } catch (InterruptedException expected) {
                        // A cancelled task may legally keep running while it settles/cleans up.
                        // Consume the interrupt here to finish this fixture's payment normally.
                        interrupted.countDown();
                    }
                }
                return super.processInvoice(invoice, fees, ui);
            } finally { exited.countDown(); }
        }
    }

    @SuppressWarnings("unchecked")
    private static GatedSender gateSender(UVNetwork network) throws Exception {
        var gated = new GatedSender(network, network.getUVNode("a"));
        var field = UVNetwork.class.getDeclaredField("uvnodes");
        field.setAccessible(true);
        ((Map<String, UVNode>) field.get(network)).put("a", gated);
        return gated;
    }

    public static void runningCancellation(Path tmp) throws Exception {
        for (boolean interrupt : new boolean[] { false, true }) {
            var network = JournalTrafficTest.fixture(tmp, "cancel-running-" + interrupt, "max_threads=2\n");
            var sender = gateSender(network);
            var pool = Executors.newSingleThreadExecutor();
            try {
                network.resume();
                var task = network.submitInvoice(sender, network.getUVNode("d").generateInvoice(1000, "running cancellation"), 1000, false);
                check(sender.entered.await(3, TimeUnit.SECONDS), "Task never entered its body");
                check(task.cancel(interrupt) && task.isDone() && task.isCancelled(), "Running cancellation failed");
                if (interrupt) check(sender.interrupted.await(3, TimeUnit.SECONDS), "Cancellation did not interrupt running task");
                check(activeTasks(network) == 1, "Cancelled running task released accounting before body exit");
                var pause = pool.submit(() -> { network.pauseAndDrain(); return null; });
                awaitDrain(network);
                check(!pause.isDone() && sender.exited.getCount() == 1, "Pause ignored a running cancelled task");
                sender.release.countDown();
                pause.get(5, TimeUnit.SECONDS);
                check(sender.exited.getCount() == 0 && activeTasks(network) == 0, "Running cancellation did not release accounting exactly once");
                check(network.isPaused() && network.isHealthy() && network.unsettledState().isEmpty(), "Cancelled body did not settle safely");
                check(sender.getInvoiceReports().size() == 1 && sender.getInvoiceReports().getFirst().success(), "Continued body lost its payment outcome");
            } finally { sender.release.countDown(); pool.shutdownNow(); network.shutdown(); }
        }
    }

    public static void queuedCancellation(Path tmp) throws Exception {
        for (boolean interrupt : new boolean[] { false, true }) {
            var network = JournalTrafficTest.fixture(tmp, "cancel-queued-" + interrupt, "max_threads=2\n");
            var sender = gateSender(network);
            try {
                network.resume();
                var first = network.submitInvoice(sender, network.getUVNode("d").generateInvoice(1000, "first"), 1000, false);
                check(sender.entered.await(3, TimeUnit.SECONDS), "First task never occupied single-worker pool");
                var unused = network.getUVNode("d").generateInvoice(1000, "queued cancellation");
                var queued = network.submitInvoice(sender, unused, 1000, false);
                check(activeTasks(network) == 2, "Queued work was not admitted");
                check(queued.cancel(interrupt) && queued.isCancelled(), "Queued cancellation failed");
                check(activeTasks(network) == 1, "Never-started cancellation did not release exactly one task");
                check(sender.interrupted.getCount() == 1, "Queued cancellation interrupted another invoice");
                sender.release.countDown();
                check(first.get(5, TimeUnit.SECONDS).success(), "Uncancelled invoice failed");
                network.pauseAndDrain(2000);
                check(activeTasks(network) == 0 && network.isPaused() && network.isHealthy(), "Queued cancellation leaked or double-released accounting");
                check(sender.getInvoiceReports().size() == 1 && !sender.getPayedInvoices().containsKey(unused.getHash()), "Cancelled queued body executed");
            } finally { sender.release.countDown(); network.shutdown(); }
        }
    }
}
