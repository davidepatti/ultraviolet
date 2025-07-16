package topology;

import network.*;
import message.*;

import java.io.*;
import java.util.*;

public class ChannelGraph implements Serializable  {

    public static PathFinder uniformCostPathFinder = new UniformCost();

    private final String root_node;
    private final HashSet<String> channelSet = new HashSet<>();
    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph, indexed by node id as keys
    //transient private Map<String, List<Edge>> adj_map = new ConcurrentHashMap<>();
    transient private Map<String, Set<Edge>> adj_map = new HashMap<>();

    public Map<String, Set<Edge>> getAdj_map() {
        return adj_map;
    }

    public static String pathString(List<Edge> path) {
        StringBuilder s = new StringBuilder("(");

        for (int i = path.size();i>0;i--) {
            var e = path.get(i-1);
            s.append(e.source()).append("->");
        }
        s.append(path.get(0).destination()).append(")");
        return s.toString();
    }

    public record Edge(String id, String source, String destination, int capacity, LNChannel.Policy policy) implements Serializable {
        @Override
        public String toString() {
            return "("+source + "->"+ destination + ")["+capacity +"]("+policy+")";
        }

        public double weight() {
            return 1.0;
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

        var edge1 = new Edge(id,node1pub,node2pub,channel.getCapacity(),channel.getPolicy(node1pub));
        var edge2 = new Edge(id,node2pub,node1pub,channel.getCapacity(),channel.getPolicy(node2pub));

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
        adj_map.get(node1).add(new Edge(channel_id,node1,node2,msg.getFunding(),null));
        adj_map.get(node2).add(new Edge(channel_id,node2,node1,msg.getFunding(),null));
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
    private void readObject(ObjectInputStream s) {
        adj_map = new HashMap<>();
        int n_keys;
        try {
            s.defaultReadObject();
            n_keys = s.readInt();

            for (int i=0;i<n_keys;i++) {
                var pubkey = (String) s.readObject();
                @SuppressWarnings("unchecked") var list = (HashSet<Edge>)s.readObject();
                adj_map.put(pubkey,list);
             }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
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
                s.writeObject(adj_map.get(k));
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public synchronized void updateChannel(String updater, LNChannel.Policy new_policy, String channel_id) {
        var edges = adj_map.get(updater);
        for (Edge e:edges) {
            if (e.id().equals(channel_id)) {
                var new_edge = new Edge(channel_id,e.source,e.destination,e.capacity,new_policy);
                if (!edges.remove(e)) {
                    throw new IllegalStateException("FATAL:Cannot remove "+e+" for matching channel id"+channel_id);
                }
                edges.add(new_edge);
                return;
            }
        }
        throw new IllegalStateException("Cannot updateChannel "+channel_id+" in "+root_node+" updater:"+updater+" policy:"+new_policy);
    }

    public synchronized void updateChannel(GossipMsgChannelUpdate msgChannelUpdate) {
        var updater = msgChannelUpdate.getSignerId();
        var new_policy = msgChannelUpdate.getUpdatedPolicy();
        var channel_id = msgChannelUpdate.getChannelId();

        updateChannel(updater,new_policy,channel_id);
    }

    public synchronized boolean hasChannel(String channelId) {
        return channelSet.contains(channelId);
    }

    public ChannelGraph(String root_node){
        this.root_node = root_node;
        addNode(root_node);
    }

    public int purgeNullPolicyChannels() {
        Iterator<String> nodeIterator = adj_map.keySet().iterator();
        int purged = 0;
        while (nodeIterator.hasNext()) {
            var node = nodeIterator.next();
            Collection<Edge> edges = adj_map.get(node);
            edges.removeIf(edge -> edge.policy == null);
            // Removing node if no edges remain
            if (edges.isEmpty()) {
                purged++;
                nodeIterator.remove();
            }
        }
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
