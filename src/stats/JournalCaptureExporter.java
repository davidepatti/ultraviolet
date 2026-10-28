package stats;

import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Versioned journal export. The legacy omniscient exporter is deliberately unchanged. */
public final class JournalCaptureExporter {
    public record Records(JsonArray nodes, JsonArray channels, JsonArray truth, String fingerprint) {}
    public record Capture(JsonObject publicGraph, JsonObject openingTruth) {}

    private JournalCaptureExporter() {}

    public static JsonObject object(Object... pairs) {
        JsonObject result = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static Object sorted(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new TreeMap<>();
            map.forEach((key, item) -> result.put((String) key, sorted(item)));
            return result;
        }
        if (value instanceof Iterable<?> list) {
            List<Object> result = new ArrayList<>();
            list.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return value;
    }

    public static String canonical(Object value) {
        return Json.stringify(sorted(value));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static JsonObject policy(LNChannel.Policy policy, String context) {
        require(policy != null, "Missing directional policy: " + context);
        require(policy.getBaseFee() >= 0 && policy.getFeePpm() >= 0,
                "Negative directional fee: " + context);
        return object("fee_base_msat", policy.getBaseFee(), "fee_rate_milli_msat", policy.getFeePpm());
    }

    private static void checkEndpointGraph(UVNode node, UVChannel channel, String source, String destination) {
        var edges = node.getChannelGraph().getAdjMap().get(source);
        require(edges != null, "Missing endpoint graph vertex: " + source);
        var matching = edges.stream().filter(edge -> edge.id().equals(channel.getChannelId())).toList();
        require(matching.size() == 1, "Missing/duplicate endpoint graph channel: " + channel.getChannelId());
        var edge = matching.getFirst();
        require(edge.source().equals(source) && edge.destination().equals(destination)
                        && edge.capacity() == channel.getCapacity(), "Endpoint graph topology mismatch");
        require(policy(edge.policy(), source).equals(policy(channel.getPolicy(source), source))
                        && edge.policy().getCLTVDelta() == channel.getPolicy(source).getCLTVDelta(),
                "Endpoint graph policy mismatch: " + node.getPubKey() + "/" + channel.getChannelId());
    }

    /** Pure validation/serialization, also usable with handcrafted test fixtures.
     * Calling this alone is NOT evidence of a stopped simulator; build() enforces that boundary.
     */
    public static Records validateRecords(List<UVNode> nodeList) {
        Map<String, UVNode> nodes = new TreeMap<>();
        Map<String, UVChannel> channels = new TreeMap<>();
        for (UVNode node : nodeList) {
            require(node.getPubKey() != null && node.getPubKey().matches("[A-Za-z0-9_-]+"), "Noncanonical node ID");
            require(nodes.putIfAbsent(node.getPubKey(), node) == null, "Duplicate node ID");
            require(node.areQueuesEmpty() && node.getOnchainPending() == 0, "Unsettled node: " + node.getPubKey());
            for (var entry : node.getChannels().entrySet()) {
                UVChannel channel = entry.getValue();
                require(entry.getKey().equals(channel.getChannelId()), "Channel map key mismatch");
                require(node.getPubKey().equals(channel.getNode1PubKey())
                        || node.getPubKey().equals(channel.getNode2PubKey()), "Channel in unrelated node");
                var previous = channels.putIfAbsent(channel.getChannelId(), channel);
                require(previous == null || previous == channel, "Duplicate channel ID with distinct objects");
            }
        }
        require(!nodes.isEmpty() && !channels.isEmpty(), "Empty public graph");
        // Opaque public IDs do not encode the opening funder as UV's original channel names do.
        Map<String, UVChannel> opaqueChannels = new TreeMap<>();
        channels.forEach((id, channel) -> {
            String opaqueId = "jch-" + sha256(id.getBytes(StandardCharsets.UTF_8));
            require(opaqueChannels.putIfAbsent(opaqueId, channel) == null, "Opaque channel ID collision");
        });
        JsonArray publicNodes = new JsonArray();
        publicNodes.addAll(nodes.keySet());
        JsonArray publicChannels = new JsonArray();
        JsonArray truth = new JsonArray();
        for (var entry : opaqueChannels.entrySet()) {
            String id = entry.getKey();
            UVChannel channel = entry.getValue();
            String a = channel.getNode1PubKey(), b = channel.getNode2PubKey();
            require(a.compareTo(b) < 0 && nodes.containsKey(a) && nodes.containsKey(b), "Invalid channel endpoints");
            require(nodes.get(a).getChannels().get(channel.getChannelId()) == channel
                    && nodes.get(b).getChannels().get(channel.getChannelId()) == channel, "Endpoint channel views disagree");
            int ba = channel.getBalance(a), bb = channel.getBalance(b), capacity = channel.getCapacity();
            require(ba >= 0 && bb >= 0 && capacity > 0 && (long) ba + bb == capacity, "Capacity conservation violation");
            require(channel.getReserve() == capacity / 100, "Wrong A2 reserve convention");
            require(channel.getPending(a) == 0 && channel.getPending(b) == 0, "Pending channel HTLC amount");
            require((ba == 0 || bb == 0) && channel.getLastCommitNumber() == 0, "Not an untouched one-sided opening state");
            for (UVNode node : List.of(nodes.get(a), nodes.get(b))) {
                checkEndpointGraph(node, channel, a, b);
                checkEndpointGraph(node, channel, b, a);
            }
            publicChannels.add(object("channel_id", id, "a", a, "b", b, "capacity_sat", capacity,
                    "a_to_b", policy(channel.getPolicy(a), a), "b_to_a", policy(channel.getPolicy(b), b)));
            truth.add(object("channel_id", id, "balance_a_sat", ba, "balance_b_sat", bb,
                    "reserve_sat", channel.getReserve(), "pending_a_sat", 0, "pending_b_sat", 0));
        }
        String fingerprint = sha256(canonical(object("nodes", publicNodes, "channels", publicChannels)).getBytes(StandardCharsets.UTF_8));
        return new Records(publicNodes, publicChannels, truth, fingerprint);
    }

    public static Capture build(UVNetwork network, String topologyId, JsonObject provenance) {
        require(network.isStoppedForJournalCapture(), "Capture barrier not established");
        require(network.getUVNodeList().size() == network.getConfig().bootstrap_nodes, "Bootstrap node count mismatch");
        Records records = validateRecords(new ArrayList<>(network.getUVNodeList().values()));
        JsonObject publicGraph = object("schema", "journal-a2-public-v1", "topology_id", topologyId,
                "nodes", records.nodes(), "channels", records.channels(), "provenance", provenance);
        JsonObject truth = object("schema", "journal-a2-truth-v1", "topology_id", topologyId,
                "public_fingerprint", records.fingerprint(), "regime", "O", "draw", 0,
                "allocation_seed", null, "settled", true, "channels", records.truth());
        return new Capture(publicGraph, truth);
    }
}
