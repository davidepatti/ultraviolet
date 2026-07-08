package stats;

import misc.json.JsonArray;
import misc.json.JsonObject;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;
import topology.ChannelGraph;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

public final class GraphJsonExporter {
    private static final String NODE_GRAPH_SCHEMA = "uv-node-graph-v1";
    private static final String OMNISCIENT_GRAPH_SCHEMA = "uv-omniscient-graph-v1";

    public record WrittenGraph(Path path, int nodes, int channels, int directedEdges, int missingPolicies) {}

    private record DirectedEdgeRecord(
            ChannelGraph.Edge edge,
            int edgeId,
            int channelIdx,
            int sourceNodeId,
            int destinationNodeId,
            boolean policyMissing
    ) {}

    private record ChannelRecord(
            int channelIdx,
            String channelId,
            String node1Pub,
            String node2Pub,
            int capacitySat,
            UVChannel channel
    ) {}

    private GraphJsonExporter() {}

    public static WrittenGraph writeNodeGraph(UVNetwork network, UVNode observer, Path outputPath) throws IOException {
        JsonObject export = buildNodeGraph(network, observer);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.writeString(outputPath, export.toJsonString() + System.lineSeparator());

        JsonObject graph = (JsonObject) export.get("graph");
        return new WrittenGraph(
                outputPath,
                asInt(graph.get("num_nodes")),
                asInt(graph.get("num_channels")),
                asInt(graph.get("num_directed_edges")),
                asInt(graph.get("num_missing_policies"))
        );
    }

    public static WrittenGraph writeOmniscientGraph(UVNetwork network, Path outputPath) throws IOException {
        JsonObject export = buildOmniscientGraph(network);
        Files.createDirectories(outputPath.toAbsolutePath().getParent());
        Files.writeString(outputPath, export.toJsonString() + System.lineSeparator());

        JsonObject graph = (JsonObject) export.get("graph");
        return new WrittenGraph(
                outputPath,
                asInt(graph.get("num_nodes")),
                asInt(graph.get("num_channels")),
                asInt(graph.get("num_directed_edges")),
                asInt(graph.get("num_missing_policies"))
        );
    }

    public static JsonObject buildNodeGraph(UVNetwork network, UVNode observer) {
        ChannelGraph graph = observer.getChannelGraph();
        List<ChannelGraph.Edge> edges = sortedEdges(graph);
        Map<String, UVChannel> channelsById = indexChannels(network);
        List<String> pubKeys = sortedGraphPubKeys(graph, edges);
        Map<String, Integer> nodeIds = assignNodeIds(pubKeys);
        List<ChannelRecord> channels = buildChannels(edges, channelsById);
        Map<String, Integer> channelIndexes = new LinkedHashMap<>();
        for (ChannelRecord channel : channels) {
            channelIndexes.put(channel.channelId(), channel.channelIdx());
        }

        int[] inDegree = new int[pubKeys.size()];
        int[] outDegree = new int[pubKeys.size()];
        long[] incidentCapacity = new long[pubKeys.size()];
        for (ChannelRecord channel : channels) {
            Integer node1Id = nodeIds.get(channel.node1Pub());
            Integer node2Id = nodeIds.get(channel.node2Pub());
            if (node1Id != null) {
                incidentCapacity[node1Id] += channel.capacitySat();
            }
            if (node2Id != null && !node2Id.equals(node1Id)) {
                incidentCapacity[node2Id] += channel.capacitySat();
            }
        }
        int missingPolicies = 0;
        ArrayList<DirectedEdgeRecord> directedEdges = new ArrayList<>();
        for (int i = 0; i < edges.size(); i++) {
            ChannelGraph.Edge edge = edges.get(i);
            Integer sourceId = nodeIds.get(edge.source());
            Integer destinationId = nodeIds.get(edge.destination());
            Integer channelIdx = channelIndexes.get(edge.id());
            if (sourceId == null || destinationId == null || channelIdx == null) {
                throw new IllegalStateException("Internal export error while indexing edge " + edge);
            }
            boolean policyMissing = edge.policy() == null;
            if (policyMissing) {
                missingPolicies++;
            }
            outDegree[sourceId]++;
            inDegree[destinationId]++;
            directedEdges.add(new DirectedEdgeRecord(edge, i, channelIdx, sourceId, destinationId, policyMissing));
        }

        JsonObject root = new JsonObject();
        root.put("schema", NODE_GRAPH_SCHEMA);
        root.put("exported_at_utc", Instant.now().toString());
        root.put("source", buildSource(network, observer));
        root.put("graph", buildGraphSummary(pubKeys.size(), channels.size(), directedEdges.size(), missingPolicies));
        root.put("nodes", buildNodes(network, observer, pubKeys, inDegree, outDegree, incidentCapacity));
        root.put("channels", buildChannelsJson(channels));
        root.put("directed_edges", buildDirectedEdgesJson(directedEdges, channels));
        return root;
    }

    public static JsonObject buildOmniscientGraph(UVNetwork network) {
        List<ChannelRecord> channels = buildOmniscientChannels(network);
        List<String> pubKeys = sortedNetworkPubKeys(network, channels);
        Map<String, Integer> nodeIds = assignNodeIds(pubKeys);

        int[] inDegree = new int[pubKeys.size()];
        int[] outDegree = new int[pubKeys.size()];
        long[] incidentCapacity = new long[pubKeys.size()];
        long totalCapacity = 0L;
        int missingPolicies = 0;
        ArrayList<DirectedEdgeRecord> directedEdges = new ArrayList<>();

        for (ChannelRecord channelRecord : channels) {
            UVChannel channel = channelRecord.channel();
            Integer node1Id = nodeIds.get(channelRecord.node1Pub());
            Integer node2Id = nodeIds.get(channelRecord.node2Pub());
            if (node1Id == null || node2Id == null) {
                throw new IllegalStateException("Internal export error while indexing channel " + channelRecord.channelId());
            }

            totalCapacity += channelRecord.capacitySat();
            incidentCapacity[node1Id] += channelRecord.capacitySat();
            if (!node2Id.equals(node1Id)) {
                incidentCapacity[node2Id] += channelRecord.capacitySat();
            }

            outDegree[node1Id]++;
            inDegree[node2Id]++;
            missingPolicies += addOmniscientDirectedEdge(
                    directedEdges,
                    channel,
                    channelRecord.channelIdx(),
                    node1Id,
                    node2Id,
                    channelRecord.node1Pub(),
                    channelRecord.node2Pub()
            );

            outDegree[node2Id]++;
            inDegree[node1Id]++;
            missingPolicies += addOmniscientDirectedEdge(
                    directedEdges,
                    channel,
                    channelRecord.channelIdx(),
                    node2Id,
                    node1Id,
                    channelRecord.node2Pub(),
                    channelRecord.node1Pub()
            );
        }

        JsonObject graph = buildGraphSummary(pubKeys.size(), channels.size(), directedEdges.size(), missingPolicies);
        graph.put("total_capacity_sat", totalCapacity);

        JsonObject root = new JsonObject();
        root.put("schema", OMNISCIENT_GRAPH_SCHEMA);
        root.put("exported_at_utc", Instant.now().toString());
        root.put("source", buildOmniscientSource(network));
        root.put("graph", graph);
        root.put("nodes", buildOmniscientNodes(network, pubKeys, inDegree, outDegree, incidentCapacity));
        root.put("channels", buildChannelsJson(channels));
        root.put("directed_edges", buildDirectedEdgesJson(directedEdges, channels));
        return root;
    }

    private static JsonObject buildSource(UVNetwork network, UVNode observer) {
        JsonObject source = new JsonObject();
        source.put("application", "UltraViolet");
        source.put("source_command", "uv node graph json export");
        source.put("observer_pub_key", observer.getPubKey());
        source.put("observer_alias", observer.getAlias());
        source.put("current_block_height", network.getTimechain().getCurrentBlockHeight());
        source.put("config_logfile", network.getConfig().logfile);
        source.put("bootstrap_completed", network.isBootstrapCompleted());
        source.put("topology_mode", network.getTopologyMode().name());
        source.put("topology_scope", network.describeTopologyMode());
        if (network.isImportedObserverView()) {
            source.put("valid_routing_origins", "observer_root_only");
            source.put("imported_observer_root_pub_key", network.getImportedRootNodeGraph());
        }
        return source;
    }

    private static JsonObject buildOmniscientSource(UVNetwork network) {
        JsonObject source = new JsonObject();
        source.put("application", "UltraViolet");
        source.put("source_command", "uv omniscient graph json export");
        source.put("snapshot_scope", "actual_uv_network_state");
        source.put("current_block_height", network.getTimechain().getCurrentBlockHeight());
        source.put("config_logfile", network.getConfig().logfile);
        source.put("bootstrap_completed", network.isBootstrapCompleted());
        source.put("topology_mode", network.getTopologyMode().name());
        source.put("topology_scope", network.describeTopologyMode());
        if (network.isImportedObserverView()) {
            source.put("valid_routing_origins", "observer_root_only");
            source.put("imported_observer_root_pub_key", network.getImportedRootNodeGraph());
        }
        return source;
    }

    private static JsonObject buildGraphSummary(int numNodes, int numChannels, int numDirectedEdges, int missingPolicies) {
        JsonObject graph = new JsonObject();
        graph.put("num_nodes", numNodes);
        graph.put("num_channels", numChannels);
        graph.put("num_directed_edges", numDirectedEdges);
        graph.put("num_missing_policies", missingPolicies);
        return graph;
    }

    private static JsonArray buildNodes(
            UVNetwork network,
            UVNode observer,
            List<String> pubKeys,
            int[] inDegree,
            int[] outDegree,
            long[] incidentCapacity
    ) {
        JsonArray nodes = new JsonArray();
        for (int i = 0; i < pubKeys.size(); i++) {
            String pubKey = pubKeys.get(i);
            UVNode node = network.getUVNode(pubKey);
            JsonObject nodeJson = new JsonObject();
            nodeJson.put("node_id", i);
            nodeJson.put("pub_key", pubKey);
            nodeJson.put("alias", node == null ? "" : node.getAlias());
            nodeJson.put("is_observer", pubKey.equals(observer.getPubKey()));
            nodeJson.put("node_missing_announcement", node == null);
            nodeJson.put("graph_in_degree", inDegree[i]);
            nodeJson.put("graph_out_degree", outDegree[i]);
            nodeJson.put("graph_incident_capacity_sat", incidentCapacity[i]);
            nodeJson.put("local_channel_count", node == null ? 0 : node.getLNChannelList().size());
            nodeJson.put("node_capacity_sat", node == null ? 0 : node.getNodeCapacity());
            nodeJson.put("local_balance_sat", node == null ? 0 : node.getLocalBalance());
            nodeJson.put("remote_balance_sat", node == null ? 0 : node.getRemoteBalance());
            nodes.add(nodeJson);
        }
        return nodes;
    }

    private static JsonArray buildOmniscientNodes(
            UVNetwork network,
            List<String> pubKeys,
            int[] inDegree,
            int[] outDegree,
            long[] incidentCapacity
    ) {
        JsonArray nodes = new JsonArray();
        for (int i = 0; i < pubKeys.size(); i++) {
            String pubKey = pubKeys.get(i);
            UVNode node = network.getUVNode(pubKey);
            JsonObject nodeJson = new JsonObject();
            nodeJson.put("node_id", i);
            nodeJson.put("pub_key", pubKey);
            nodeJson.put("alias", node == null ? "" : node.getAlias());
            nodeJson.put("is_observer", false);
            nodeJson.put("node_missing_announcement", node == null);
            nodeJson.put("node_missing_state", node == null);
            nodeJson.put("graph_in_degree", inDegree[i]);
            nodeJson.put("graph_out_degree", outDegree[i]);
            nodeJson.put("graph_incident_capacity_sat", incidentCapacity[i]);
            nodeJson.put("local_channel_count", node == null ? 0 : node.getLNChannelList().size());
            nodeJson.put("node_capacity_sat", node == null ? 0 : node.getNodeCapacity());
            nodeJson.put("local_balance_sat", node == null ? 0 : node.getLocalBalance());
            nodeJson.put("remote_balance_sat", node == null ? 0 : node.getRemoteBalance());
            nodes.add(nodeJson);
        }
        return nodes;
    }

    private static JsonArray buildChannelsJson(List<ChannelRecord> channels) {
        JsonArray channelsJson = new JsonArray();
        for (ChannelRecord channel : channels) {
            JsonObject channelJson = new JsonObject();
            channelJson.put("channel_idx", channel.channelIdx());
            channelJson.put("channel_id", channel.channelId());
            channelJson.put("node1_pub", channel.node1Pub());
            channelJson.put("node2_pub", channel.node2Pub());
            channelJson.put("capacity_sat", channel.capacitySat());
            channelJson.put("has_uv_channel_state", channel.channel() != null);
            if (channel.channel() != null) {
                channelJson.put("reserve_sat", channel.channel().getReserve());
                channelJson.put("node1_balance_sat", channel.channel().getBalance(channel.node1Pub()));
                channelJson.put("node2_balance_sat", channel.channel().getBalance(channel.node2Pub()));
                channelJson.put("node1_spendable_balance_sat", spendableBalance(channel.channel(), channel.node1Pub()));
                channelJson.put("node2_spendable_balance_sat", spendableBalance(channel.channel(), channel.node2Pub()));
                channelJson.put("node1_policy_missing", channel.channel().getPolicy(channel.node1Pub()) == null);
                channelJson.put("node2_policy_missing", channel.channel().getPolicy(channel.node2Pub()) == null);
            } else {
                channelJson.put("reserve_sat", 0);
                channelJson.put("node1_balance_sat", 0);
                channelJson.put("node2_balance_sat", 0);
                channelJson.put("node1_spendable_balance_sat", 0);
                channelJson.put("node2_spendable_balance_sat", 0);
                channelJson.put("node1_policy_missing", true);
                channelJson.put("node2_policy_missing", true);
            }
            channelsJson.add(channelJson);
        }
        return channelsJson;
    }

    private static JsonArray buildDirectedEdgesJson(List<DirectedEdgeRecord> directedEdges, List<ChannelRecord> channels) {
        JsonArray edgesJson = new JsonArray();
        for (DirectedEdgeRecord record : directedEdges) {
            ChannelGraph.Edge edge = record.edge();
            ChannelRecord channel = channels.get(record.channelIdx());
            LNChannel.Policy policy = edge.policy();
            JsonObject edgeJson = new JsonObject();
            edgeJson.put("edge_id", record.edgeId());
            edgeJson.put("channel_idx", record.channelIdx());
            edgeJson.put("channel_id", edge.id());
            edgeJson.put("src", record.sourceNodeId());
            edgeJson.put("dst", record.destinationNodeId());
            edgeJson.put("src_pub_key", edge.source());
            edgeJson.put("dst_pub_key", edge.destination());
            edgeJson.put("capacity_sat", edge.capacity());
            edgeJson.put("policy_missing", record.policyMissing());
            edgeJson.put("time_lock_delta", policy == null ? 0 : policy.getCLTVDelta());
            edgeJson.put("fee_base_msat", policy == null ? 0 : policy.getBaseFee());
            edgeJson.put("fee_rate_milli_msat", policy == null ? 0 : policy.getFeePpm());
            edgeJson.put("disabled", false);
            edgeJson.put("disabled_missing", true);
            edgeJson.put("direction_is_node2_to_node1", edge.source().equals(channel.node2Pub()) && edge.destination().equals(channel.node1Pub()));
            if (channel.channel() != null) {
                edgeJson.put("source_balance_sat", channel.channel().getBalance(edge.source()));
                edgeJson.put("destination_balance_sat", channel.channel().getBalance(edge.destination()));
                edgeJson.put("source_spendable_balance_sat", spendableBalance(channel.channel(), edge.source()));
                edgeJson.put("destination_spendable_balance_sat", spendableBalance(channel.channel(), edge.destination()));
                edgeJson.put("balance_missing", false);
            } else {
                edgeJson.put("source_balance_sat", 0);
                edgeJson.put("destination_balance_sat", 0);
                edgeJson.put("source_spendable_balance_sat", 0);
                edgeJson.put("destination_spendable_balance_sat", 0);
                edgeJson.put("balance_missing", true);
            }
            edgesJson.add(edgeJson);
        }
        return edgesJson;
    }

    private static List<ChannelGraph.Edge> sortedEdges(ChannelGraph graph) {
        ArrayList<ChannelGraph.Edge> edges = new ArrayList<>();
        for (String source : graph.getAdjMap().keySet()) {
            edges.addAll(graph.getAdjMap().get(source));
        }
        edges.sort(
                Comparator.comparing(ChannelGraph.Edge::source)
                        .thenComparing(ChannelGraph.Edge::destination)
                        .thenComparing(ChannelGraph.Edge::id)
        );
        return edges;
    }

    private static List<String> sortedGraphPubKeys(ChannelGraph graph, List<ChannelGraph.Edge> edges) {
        TreeSet<String> pubKeys = new TreeSet<>(graph.getAdjMap().keySet());
        for (ChannelGraph.Edge edge : edges) {
            pubKeys.add(edge.source());
            pubKeys.add(edge.destination());
        }
        return new ArrayList<>(pubKeys);
    }

    private static List<String> sortedNetworkPubKeys(UVNetwork network, List<ChannelRecord> channels) {
        TreeSet<String> pubKeys = new TreeSet<>();
        for (UVNode node : network.getSortedNodeListByPubkey()) {
            pubKeys.add(node.getPubKey());
        }
        for (ChannelRecord channel : channels) {
            pubKeys.add(channel.node1Pub());
            pubKeys.add(channel.node2Pub());
        }
        return new ArrayList<>(pubKeys);
    }

    private static Map<String, Integer> assignNodeIds(List<String> pubKeys) {
        Map<String, Integer> nodeIds = new LinkedHashMap<>();
        for (int i = 0; i < pubKeys.size(); i++) {
            nodeIds.put(pubKeys.get(i), i);
        }
        return nodeIds;
    }

    private static List<ChannelRecord> buildChannels(List<ChannelGraph.Edge> edges, Map<String, UVChannel> channelsById) {
        Map<String, Set<ChannelGraph.Edge>> edgesByChannel = new TreeMap<>();
        for (ChannelGraph.Edge edge : edges) {
            edgesByChannel.computeIfAbsent(edge.id(), ignored -> new LinkedHashSet<>()).add(edge);
        }

        ArrayList<ChannelRecord> channels = new ArrayList<>();
        int channelIdx = 0;
        for (Map.Entry<String, Set<ChannelGraph.Edge>> entry : edgesByChannel.entrySet()) {
            String channelId = entry.getKey();
            UVChannel channel = channelsById.get(channelId);
            String node1Pub;
            String node2Pub;
            int capacitySat = 0;
            if (channel != null) {
                node1Pub = channel.getNode1PubKey();
                node2Pub = channel.getNode2PubKey();
                capacitySat = channel.getCapacity();
            } else {
                TreeSet<String> endpoints = new TreeSet<>();
                for (ChannelGraph.Edge edge : entry.getValue()) {
                    endpoints.add(edge.source());
                    endpoints.add(edge.destination());
                    capacitySat = Math.max(capacitySat, edge.capacity());
                }
                ArrayList<String> endpointList = new ArrayList<>(endpoints);
                node1Pub = endpointList.isEmpty() ? "" : endpointList.get(0);
                node2Pub = endpointList.size() < 2 ? "" : endpointList.get(1);
            }
            channels.add(new ChannelRecord(channelIdx, channelId, node1Pub, node2Pub, capacitySat, channel));
            channelIdx++;
        }
        return channels;
    }

    private static List<ChannelRecord> buildOmniscientChannels(UVNetwork network) {
        Map<String, UVChannel> channelsById = indexChannels(network);
        ArrayList<ChannelRecord> channels = new ArrayList<>();
        int channelIdx = 0;
        for (UVChannel channel : channelsById.values()) {
            channels.add(new ChannelRecord(
                    channelIdx,
                    channel.getChannelId(),
                    channel.getNode1PubKey(),
                    channel.getNode2PubKey(),
                    channel.getCapacity(),
                    channel
            ));
            channelIdx++;
        }
        return channels;
    }

    private static int addOmniscientDirectedEdge(
            ArrayList<DirectedEdgeRecord> directedEdges,
            UVChannel channel,
            int channelIdx,
            int sourceNodeId,
            int destinationNodeId,
            String sourcePubKey,
            String destinationPubKey
    ) {
        LNChannel.Policy policy = channel.getPolicy(sourcePubKey);
        directedEdges.add(new DirectedEdgeRecord(
                new ChannelGraph.Edge(
                        channel.getChannelId(),
                        sourcePubKey,
                        destinationPubKey,
                        channel.getCapacity(),
                        policy
                ),
                directedEdges.size(),
                channelIdx,
                sourceNodeId,
                destinationNodeId,
                policy == null
        ));
        return policy == null ? 1 : 0;
    }

    private static Map<String, UVChannel> indexChannels(UVNetwork network) {
        Map<String, UVChannel> channels = new TreeMap<>();
        for (UVNode node : network.getSortedNodeListByPubkey()) {
            for (UVChannel channel : node.getChannels().values()) {
                channels.putIfAbsent(channel.getChannelId(), channel);
            }
        }
        return channels;
    }

    private static int spendableBalance(UVChannel channel, String pubKey) {
        return Math.max(0, channel.getBalance(pubKey) - channel.getReserve());
    }

    private static int asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        throw new IllegalArgumentException("Expected numeric value, got " + value);
    }
}
