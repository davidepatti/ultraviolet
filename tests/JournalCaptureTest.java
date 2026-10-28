package tests;

import misc.UVConfig;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;
import stats.JournalCaptureExporter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class JournalCaptureTest {
    private JournalCaptureTest() {}

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void rejected(Runnable action, String message) {
        try {
            action.run();
        } catch (IllegalStateException expected) {
            check(expected.getMessage().contains(message), "Unexpected failure: " + expected);
            return;
        }
        throw new AssertionError("Expected rejection: " + message);
    }

    public static void assertOpeningRejections(Path temporary) throws Exception {
        Path configPath = temporary.resolve("opening-rejection.properties");
        Files.writeString(configPath, "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nlogfile=" + temporary.resolve("opening-rejection.log") + "\n");
        UVConfig config = new UVConfig(configPath.toString());
        UVNetwork network = new UVNetwork(config);
        try {
            UVNode left = new UVNode(network, "left", "left", 100_000, config.getNodeProfile("default"));
            UVNode right = new UVNode(network, "right", "right", 100_000, config.getNodeProfile("default"));
            network.registerNode(left); network.registerNode(right);
            check(left.openChannel("right", 10_000) && right.openChannel("left", 20_000), "Mutual opening fixture");
            String oldProposal = left.getSentChannelOpenings().get("right").getTemporary_channel_id();
            var accept = UVNode.class.getDeclaredMethod("acceptChannel", message.MsgOpenChannel.class); accept.setAccessible(true);
            accept.invoke(left, left.getChannelsToAcceptQueue().poll());
            accept.invoke(right, right.getChannelsToAcceptQueue().poll());
            for (UVNode node : List.of(left, right)) {
                node.setP2PServices(true); node.runServices(); node.setP2PServices(false);
                check(node.areQueuesEmpty() && node.getOnchainPending() == 0, "Rejected reservation was not released");
                check(node.getOnChainBalance() == 100_000 && node.getChannels().isEmpty(), "Rejection changed funded state");
            }
            left.openChannel("right", 1000);
            var stale = new message.MsgRejectChannel(oldProposal, "right");
            network.deliverMessage(left, stale); network.deliverMessage(left, stale);
            left.setP2PServices(true); left.runServices(); left.setP2PServices(false);
            check(left.getOnchainPending() == 1000, "Stale rejection cancelled a newer proposal");
            String current = left.getSentChannelOpenings().get("right").getTemporary_channel_id();
            network.deliverMessage(left, new message.MsgRejectChannel(current, "right"));
            network.deliverMessage(left, new message.MsgRejectChannel(current, "right"));
            left.setP2PServices(true); left.runServices(); left.setP2PServices(false);
            check(left.getOnchainPending() == 0, "Duplicate rejection double-released reservation");
        } finally { network.shutdown(); }
    }

    public static void run(Path temporary) throws Exception {
        Path configPath = temporary.resolve("journal.properties");
        String template = Files.readString(Path.of("uv_configs/template.properties"));
        // Remove optional keys to verify resolved defaults, not just a copy of supplied properties.
        template = template.replaceAll("(?m)^pathfinding_[^\\n]*\\n", "");
        Path base = temporary.resolve("journal-base.properties");
        Files.writeString(base, template);
        Files.writeString(configPath, "@include=" + base + "\nseed=23\nbootstrap_nodes=2\nlogfile=" + temporary.resolve("journal.log") + "\n");
        UVConfig config = new UVConfig(configPath.toString());
        var settings = config.getEffectiveSettings();
        check(settings.get("seed").equals(23) && settings.get("bootstrap_nodes").equals(2), "Typed effective overrides");
        check(settings.get("pathfinding_max_hops").equals(6)
                && settings.get("pathfinding_lnd_default_path_probability").equals(0.6), "Missing resolved defaults");
        check(settings.containsKey("profile.default.min_funding") && settings.containsKey("base_fee_set"), "Incomplete property family");
        UVNetwork network = new UVNetwork(config);
        try {
            rejected(() -> JournalCaptureExporter.build(network, "fixture", JournalCaptureExporter.object()), "barrier");
            UVNode a = new UVNode(network, "a", "a", 100_000, config.getNodeProfile("default"));
            UVNode b = new UVNode(network, "b", "b", 100_000, config.getNodeProfile("default"));
            var policyA = new LNChannel.Policy(40, 100, 300);
            var policyB = new LNChannel.Policy(40, 1000, 200);
            UVChannel channel = new UVChannel("ch_funder_a_b", "a", "b", 10_000, 100, 0, true);
            channel.setPolicy("a", policyA);
            channel.setPolicy("b", policyB);
            for (UVNode node : List.of(a, b)) {
                node.configureChannel(channel);
                node.getChannelGraph().addLNChannel(channel);
            }
            var records = JournalCaptureExporter.validateRecords(List.of(a, b));
            var reordered = JournalCaptureExporter.validateRecords(List.of(b, a));
            check(records.equals(reordered), "Canonical record order is not stable");
            check(!records.channels().toJsonString().contains("funder"), "Public ID exposes funder naming");
            check(!records.channels().toJsonString().contains("balance"), "Public/oracle separation");
            check(channel.reservePending("a", 1), "Fixture pending reserve");
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "Unsettled node");
            channel.removePending("a", 1);
            channel.setPolicy("b", null);
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "Missing directional policy");
            channel.setPolicy("b", policyB);
            a.getChannelGraph().updateChannel("a", policyB, channel.getChannelId());
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "policy mismatch");
            a.getChannelGraph().updateChannel("a", policyA, channel.getChannelId());
            b.getChannels().remove(channel.getChannelId());
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "views disagree");
            b.configureChannel(channel);
            UVChannel duplicate = new UVChannel(channel.getChannelId(), "a", "b", 10_000, 100, 0, true);
            b.getChannels().put(channel.getChannelId(), duplicate);
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "distinct objects");
            b.getChannels().put(channel.getChannelId(), channel);
            channel.resolveNode("a").setBalance(-1);
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "conservation");
            channel.resolveNode("a").setBalance(10_000);
            UVChannel wrongReserve = new UVChannel(channel.getChannelId(), "a", "b", 10_000, 99, 0, true);
            a.getChannels().put(channel.getChannelId(), wrongReserve);
            b.getChannels().put(channel.getChannelId(), wrongReserve);
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "reserve convention");
            a.getChannels().put(channel.getChannelId(), channel);
            b.getChannels().put(channel.getChannelId(), channel);
            channel.newCommitment(5000, 5000);
            rejected(() -> JournalCaptureExporter.validateRecords(List.of(a, b)), "opening state");
        } finally {
            network.shutdown();
        }
    }
}
