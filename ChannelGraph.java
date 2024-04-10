import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ChannelGraph implements Serializable  {

    private final String root_node;
    private final HashSet<String> channelSet = new HashSet<>();
    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph, indexed by node id as keys
    //transient private Map<String, List<Edge>> adj_map = new ConcurrentHashMap<>();
    transient private Map<String, Set<Edge>> adj_map = new HashMap<>();

    public static String pathString(ArrayList<Edge> path) {
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

        final String id = channel.getId();
        var node1pub = channel.getNode1PubKey();
        var node2pub = channel.getNode2PubKey();

        if (this.hasChannel(id)) {
            System.out.println(" FATAL: calling addChannel with existing edge for channel "+channel.getId()+" node1:"+node1pub+" node2:"+node2pub);
            System.exit(-1);
        }

        adj_map.putIfAbsent(node1pub, new HashSet<>());
        adj_map.putIfAbsent(node2pub, new HashSet<>());

        var edge1 = new Edge(id,node1pub,node2pub,channel.getCapacity(),channel.getNode1Policy());
        var edge2 = new Edge(id,node2pub,node1pub,channel.getCapacity(),channel.getNode2Policy());

        adj_map.get(channel.getNode1PubKey()).add(edge1);
        adj_map.get(channel.getNode2PubKey()).add(edge2);

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

    protected synchronized ArrayList<ArrayList<Edge>> findPath(String start, String end, boolean stopfirst)
    {
        var visited_vertex = new ArrayList<String>();
        var queue_vertex = new LinkedList<String>();
        var paths = new ArrayList<ArrayList<Edge>>();

        var last_parent = new HashMap<String,Edge>();
        last_parent.put("ROOT",null);

        int nfound = 0;

        visited_vertex.add(start);
        queue_vertex.add(start);

        while (!queue_vertex.isEmpty()) {
            var current_vertex = queue_vertex.poll();

            var list_edges =adj_map.get(current_vertex);

            for (Edge e :list_edges) {
                if (e.destination().equals(end))  {
                    nfound++;
                    var path = new ArrayList<Edge>();
                    path.add(e);

                    Edge current = last_parent.get(e.source());
                    while (current!=null) {
                        path.add(current);
                        current = last_parent.get(current.source());
                    }
                    paths.add(path);
                    if (stopfirst) return paths;
                    // no need to go deeper along that path
                    visited_vertex.add(e.destination());
                    continue;
                }
                if (!visited_vertex.contains(e.destination())) {
                    last_parent.put(e.destination(),e);
                    visited_vertex.add(e.destination());
                    queue_vertex.add(e.destination());
                }
            }
        }
        return paths;
    }

    /**
    public void DFS_path_util(String current_node, String end_node, HashSet<String> visited) {
        visited.add(current_node);
        if (DEBUG)
            System.out.println(">>> Considering:" + current_node + " ");

        if (DEBUG)
            if (current_node.equals(end_node)) System.out.println("FOUND!");

        for (var n : adj_map.get(current_node)) {
            if (DEBUG)
                System.out.println("\t-> Looking " + current_node + "-->" + n);
            if (!visited.contains(n)) {
                DFS_path_util(n, end_node, visited);
            }
        }
        if (DEBUG)
            System.out.println(">>> FINISH "+current_node+ " ");
    }

    public boolean DFSFindPath(LNode start_node,LNode end_node) {
        var visited = new HashSet<String>();
        if (DEBUG)
            System.out.println("Starting from "+start_node.getPubKey()+" destination "+end_node.getPubKey());
        DFS_path_util(start_node.getPubKey(),end_node.getPubKey(),visited);
        return  false;
    }

     */


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
