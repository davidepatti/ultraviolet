package stats;

import misc.UVConfig;
import network.*;
import protocol.UVTimechain;
import topology.PathFinderFactory;
import java.util.*;
import java.util.concurrent.Future;

/**
 * Restricted zero-invoice genuine-opening (O) branch audit, the counterpart of {@link InitialS50State}.
 * Every channel must be an untouched one-sided opening; fingerprints reuse InitialS50State's digest.
 */
public final class InitialOState {
    private InitialOState() {}
    private static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) try {
            var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o);
        } catch (NoSuchFieldException ignored) {} catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        throw new IllegalArgumentException("Unknown branch field " + name);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }

    public static void validate(UVConfig config, UVTimechain clock, Map<String, UVNode> nodes, boolean reading) {
        check(!clock.getStatus() && !clock.isThreadAlive(), "Initial O branch clock must be joined");
        check(((Collection<?>) field(clock, "mempoolQueue")).isEmpty(), "Initial O branch mempool must be empty");
        var waits = (Collection<?>) field(clock, "wait_blocks_latch"); check(waits == null || waits.isEmpty(), "Initial O branch has clock waiters");
        var channels = new TreeMap<String, UVChannel>();
        for (var node : nodes.values()) {
            check(node.getClass() == UVNode.class, "Unsupported node subclass");
            check(!((Boolean) field(node, "p2pIsRunning")), "Initial node worker is running");
            Object handler = field(node, "p2pHandler"); check(handler == null || handler instanceof Future<?> f && f.isDone(), "Initial node worker not joined");
            check(node.unsettledState().isEmpty(), "Initial node has unsettled protocol state");
            check(((Map<?, ?>) field(node, "failure_reason")).isEmpty(), "Unsaved initial failure cache");
            check(node.getGeneratedInvoices().isEmpty() && node.getPayedInvoices().isEmpty() && node.getInvoiceReports().isEmpty(), "Initial node has invoice history");
            var strategy = (PathFinderFactory.Strategy) field(node, "savedPathFinderStrategy");
            var expected = PathFinderFactory.of(strategy, config);
            if (reading) node.setPathFinder(expected);
            check(node.getPathFinder() != null && InitialS50State.digest(node.getPathFinder()).equals(InitialS50State.digest(expected)), "Unsupported mutable pathfinder state");
            Collection<?> peers = node.getPeers() == null ? (Collection<?>) field(node, "saved_peers_id") : node.getPeers().keySet();
            check(peers != null && nodes.keySet().containsAll(peers), "Invalid initial peer identities");
            if (node.getPeers() != null) for (var e : node.getPeers().entrySet()) check(nodes.get(e.getKey()) == e.getValue(), "Foreign initial peer object");
            for (var edges : node.getChannelGraph().getAdjMap().values()) if (reading) check(edges instanceof LinkedHashSet<?>, "Snapshot does not preserve routing iteration order");
            for (var channel : node.getChannels().values()) {
                check(channel.getClass() == UVChannel.class, "Unsupported channel subclass");
                var old = channels.putIfAbsent(channel.getChannelId(), channel); check(old == null || old == channel, "Duplicate physical channel");
                String a = channel.getNode1PubKey(), b = channel.getNode2PubKey();
                check(nodes.containsKey(a) && nodes.containsKey(b) && nodes.get(a).getChannels().get(channel.getChannelId()) == channel
                        && nodes.get(b).getChannels().get(channel.getChannelId()) == channel, "Invalid shared endpoint channel");
                int ba = channel.getBalance(a), bb = channel.getBalance(b);
                check((ba == 0 || bb == 0) && (long) ba + bb == channel.getCapacity() && channel.getLastCommitNumber() == 0
                        && channel.getPending(a) == 0 && channel.getPending(b) == 0, "Branch is not an untouched one-sided opening");
                check(channel.getPolicy(a) != null && channel.getPolicy(b) != null, "Opening channel lacks a directional policy");
            }
        }
        check(!nodes.isEmpty() && !channels.isEmpty(), "Empty initial O branch");
    }
}
