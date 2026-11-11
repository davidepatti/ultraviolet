package topology;

import network.*;
import message.*;

import java.io.*;
import java.util.*;

public class ChannelGraph implements Serializable  {

    private final String root_node;
    private final HashSet<String> channelSet = new HashSet<>();
    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph, indexed by node id as keys
    //transient private Map<String, List<Edge>> adj_map = new ConcurrentHashMap<>();
    transient private Map<String, Set<Edge>> adj_map = new HashMap<>();

    // Edges are immutable values. The network can supply one canonical instance per distinct
    // value, so that graphs holding the same information about a channel point at one object
    // instead of each keeping a copy. Every graph still holds its own set of references: a node
    // that has not received an update keeps pointing at the edge value it knows.
    transient private java.util.function.UnaryOperator<Edge> edgeInterner = java.util.function.UnaryOperator.identity();

    public synchronized void setEdgeInterner(java.util.function.UnaryOperator<Edge> interner) {
        edgeInterner = interner == null ? java.util.function.UnaryOperator.identity() : interner;
    }

    /** Replace the edges already held with their canonical instances (used after loading a snapshot). */
    public synchronized void shareEdges() {
        for (var entry : adj_map.entrySet()) {
            var shared = new HashSet<Edge>(Math.max(16, (int) (entry.getValue().size() / 0.75f) + 1));
            for (Edge e : entry.getValue()) shared.add(edgeInterner.apply(e));
            entry.setValue(shared);
        }
    }

    /**
     * Path finders iterate this map without taking the graph lock. That is safe only while no
     * gossip is being processed for this node, which holds today because routing runs after
     * gossip has drained. If policies are ever updated during traffic, searches must be
     * protected (for example with a read/write lock taken once per search).
     */
    public Map<String, Set<Edge>> getAdjMap() {
        return adj_map;
    }

    /** Value of {@code policyHops} for a policy that was not learned through gossip. */
    public static final int LOCAL_POLICY = Integer.MAX_VALUE;

    /**
     * A directed view of a channel. {@code policyTimestamp} and {@code policyHops} describe the
     * channel_update the policy came from: its timestamp and how many times it had been
     * forwarded when it was accepted (-1 and {@link #LOCAL_POLICY} when not learned by gossip).
     */
    public record Edge(String id, String source, String destination, int capacity, LNChannel.Policy policy,
                       int policyTimestamp, int policyHops) implements Serializable {
        public Edge(String id, String source, String destination, int capacity, LNChannel.Policy policy) {
            this(id, source, destination, capacity, policy, -1, LOCAL_POLICY);
        }
        @Override
        public String toString() {
            return "("+source + "->"+ destination + ")["+capacity +"]("+policy+")";
        }
    }


    // This function adds a new vertex to the graph
    private synchronized void addNode(String node_id) {
        adj_map.putIfAbsent(node_id, new HashSet<>());
    }

    /**
     *
     * @param channel
     */
    public synchronized void addLNChannel(LNChannel channel) {

        final String id = channel.getChannelId();
        var node1pub = channel.getNode1PubKey();
        var node2pub = channel.getNode2PubKey();

        if (this.hasChannel(id)) {
            System.out.println(" WARNING: skipping addChannel on "+root_node+ ", existing edge for channel "+channel.getChannelId());
            return;
            //System.exit(-1);
        }

        adj_map.putIfAbsent(node1pub, new HashSet<>());
        adj_map.putIfAbsent(node2pub, new HashSet<>());

        var edge1 = edgeInterner.apply(new Edge(id,node1pub,node2pub,channel.getCapacity(),channel.getPolicy(node1pub)));
        var edge2 = edgeInterner.apply(new Edge(id,node2pub,node1pub,channel.getCapacity(),channel.getPolicy(node2pub)));

        adj_map.get(node1pub).add(edge1);
        adj_map.get(node2pub).add(edge2);

        channelSet.add(id);
    }

    public synchronized void addAnnouncedChannel(GossipMsgChannelAnnouncement msg) {

        var channel_id = msg.getChannelId();
        if (this.hasChannel(channel_id)) {
            System.out.println(" FATAL: calling addAnnouncedChannel with existing edge for msg: "+msg);
            System.exit(-1);
        }
        channelSet.add(channel_id);
        var node1 = msg.getNodeId1();
        var node2 = msg.getNodeId2();
        adj_map.putIfAbsent(node1, new HashSet<>());
        adj_map.putIfAbsent(node2, new HashSet<>());
        adj_map.get(node1).add(edgeInterner.apply(new Edge(channel_id,node1,node2,msg.getFunding(),null)));
        adj_map.get(node2).add(edgeInterner.apply(new Edge(channel_id,node2,node1,msg.getFunding(),null)));
    }

    /**
     * This function gives the count of vertices
     * @return
     */
    private synchronized int getVertexCount() {
        return adj_map.keySet().size();
    }

    /**
     * This function gives the count of edges
     * @param bidirection
     * @return
     */
    private synchronized int getEdgesCount(boolean bidirection) {
        int count = 0;
        for (String v : adj_map.keySet()) {
            count += adj_map.get(v).size();
        }
        if (bidirection) {
            count = count / 2;
        }
        return count;
    }

    @Serial
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        adj_map = new HashMap<>();
        edgeInterner = java.util.function.UnaryOperator.identity();
        int n_keys;
        s.defaultReadObject();
        n_keys = s.readInt();

        for (int i=0;i<n_keys;i++) {
            var pubkey = (String) s.readObject();
            @SuppressWarnings("unchecked") var list = (HashSet<Edge>)s.readObject();
            adj_map.put(pubkey,list);
        }

    }

    @Serial
    private void writeObject(ObjectOutputStream s) {
        try {
            s.defaultWriteObject();
            int n_keys = adj_map.keySet().size();
            s.writeInt(n_keys);

            for (String k : adj_map.keySet()) {
                s.writeObject(k);
                // Preserve the observed search-neighbor order. Policy identity hashes change on restore.
                // LinkedHashSet is also a HashSet, so the legacy reader format remains compatible.
                s.writeObject(new LinkedHashSet<>(adj_map.get(k)));
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    /**
     * Set a policy that was not learned through gossip (the node's own, or test fixtures).
     * @return false when this graph has no edge of {@code updater} for the channel
     */
    public synchronized boolean updateChannel(String updater, LNChannel.Policy new_policy, String channel_id) {
        return replaceEdge(updater, channel_id, new_policy, -1, LOCAL_POLICY, true);
    }

    /**
     * Apply a gossiped channel_update following BOLT 7: an update that is not newer than the one
     * already held for the same channel and direction is ignored. The same update received with
     * fewer forwardings than the accepted copy is recorded and reported as accepted, so that the
     * caller forwards it again with the larger remaining hop budget; this keeps the reach of an
     * update equal to the hop limit whatever the order of arrival.
     * @return true when the update was accepted and should be forwarded
     */
    public synchronized boolean updateChannel(GossipMsgChannelUpdate msgChannelUpdate) {
        return replaceEdge(msgChannelUpdate.getSignerId(), msgChannelUpdate.getChannelId(),
                msgChannelUpdate.getUpdatedPolicy(), msgChannelUpdate.getTimeStamp(), msgChannelUpdate.getForwardings(), false);
    }

    private boolean replaceEdge(String updater, String channel_id, LNChannel.Policy new_policy, int timestamp, int hops, boolean local) {
        var edges = adj_map.get(updater);
        // The edge can be absent when the channel is unknown or this direction has been purged.
        if (edges == null) return false;
        for (Edge e:edges) {
            if (e.id().equals(channel_id)) {
                if (!local && e.policy != null) {
                    boolean sameUpdate = e.policy == new_policy || timestamp == e.policyTimestamp;
                    if (sameUpdate && hops >= e.policyHops) return false;
                    if (!sameUpdate && timestamp < e.policyTimestamp) return false;
                }
                var new_edge = edgeInterner.apply(new Edge(channel_id,e.source,e.destination,e.capacity,new_policy,timestamp,hops));
                if (!edges.remove(e)) {
                    throw new IllegalStateException("FATAL:Cannot remove "+e+" for matching channel id"+channel_id);
                }
                edges.add(new_edge);
                return true;
            }
        }
        return false;
    }

    /** Number of directed edges this view holds for (source, channel): 0 or 1 in a valid graph. */
    public synchronized int countDirectedEdges(String source, String channel_id) {
        var edges = adj_map.get(source);
        if (edges == null) return 0;
        int n = 0;
        for (Edge e : edges) if (e.id().equals(channel_id)) n++;
        return n;
    }

    /**
     * Replaces one directed policy without changing routing iteration order. Only defined for
     * restored graphs, whose edge sets are LinkedHashSet; a live HashSet orders by identity hashes.
     */
    public synchronized void replacePolicyPreservingOrder(String source, String channel_id, LNChannel.Policy new_policy) {
        Objects.requireNonNull(new_policy, "policy");
        var edges = adj_map.get(source);
        if (!(edges instanceof LinkedHashSet<Edge>)) throw new IllegalStateException("Order-preserving policy replacement requires a restored graph");
        var rebuilt = new LinkedHashSet<Edge>();
        int replaced = 0;
        for (Edge e : edges) {
            if (e.id().equals(channel_id)) { rebuilt.add(new Edge(e.id, e.source, e.destination, e.capacity, new_policy)); replaced++; }
            else rebuilt.add(e);
        }
        if (replaced != 1 || rebuilt.size() != edges.size()) throw new IllegalStateException("Cannot replace policy " + channel_id + " from " + source + " in " + root_node);
        edges.clear();
        edges.addAll(rebuilt);
    }

    public synchronized boolean hasChannel(String channelId) {
        return channelSet.contains(channelId);
    }

    public ChannelGraph(String root_node){
        this.root_node = root_node;
        addNode(root_node);
    }

    public synchronized int purgeNullPolicyChannels() {
        Iterator<String> nodeIterator = adj_map.keySet().iterator();
        int purged = 0;
        var surviving = new HashSet<String>();
        while (nodeIterator.hasNext()) {
            var node = nodeIterator.next();
            Collection<Edge> edges = adj_map.get(node);
            edges.removeIf(edge -> edge.policy == null);
            // Removing node if no edges remain
            if (edges.isEmpty()) {
                purged++;
                nodeIterator.remove();
            } else {
                for (Edge edge : edges) surviving.add(edge.id);
            }
        }
        // A channel with no remaining direction is unknown again, so that a later
        // announcement can add it back instead of being taken for a duplicate.
        channelSet.retainAll(surviving);
        return purged;
    }

    public synchronized int countNullPolicies() {
        int empty = 0;
        for (var node: adj_map.keySet()) {
            var edges = adj_map.get(node);
            for (var e: edges) {
                if (e.policy == null) empty++;
            }
        }
        return empty;
    }


    public int getNodeCount() {
       return getVertexCount();
    }

    public int getChannelCount() {
        return getEdgesCount(false);
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();
        ArrayList<String> list = new ArrayList<>();

        adj_map.keySet().stream().sorted().forEach(list::add);

        for (String v : list) {
            builder.append(v).append(": ");
            for (Edge w : adj_map.get(v)) {
                builder.append("\n").append(w);
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
