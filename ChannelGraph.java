import java.io.*;
import java.util.*;

public class ChannelGraph implements Serializable  {

    private String root_node;
    public record Edge(String source, String destination, int capacity, LNChannel.Policy policy) implements Serializable {
        @Override
        public String toString() {
            return "{ source='" + source + '\'' + ", destination='" + destination + '\'' + ", capacity=" + capacity + '}';
        }
    }

    transient private static final boolean DEBUG = true;
    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph
    transient private Map<String, List<Edge>> adj_map = new HashMap<>();

    // This function adds a new vertex to the graph
    private void addVertex(String s) {
        adj_map.putIfAbsent(s, new LinkedList<>());
    }

    /**
     *
     * @param channel
     */
    public void addChannel(LNChannel channel) {

        var node1pub = channel.getNode1PubKey();
        var node2pub = channel.getNode2PubKey();


        adj_map.putIfAbsent(node1pub, new LinkedList<>());
        adj_map.putIfAbsent(node2pub, new LinkedList<>());

        // do not add channel edge if source and destination area already connected
        // TODO: in theory, multiple channel could be opened with differen id
        if (this.hasEdge(node1pub, node2pub)) return;

        var edge1 = new Edge(node1pub,node2pub,channel.getCapacity(),channel.getNode1Policy());
        var edge2 = new Edge(node2pub,node1pub,channel.getCapacity(),channel.getNode2Policy());

        adj_map.get(channel.getNode1PubKey()).add(edge1);
        adj_map.get(channel.getNode2PubKey()).add(edge2);

    }

    // TODO: assuming symmetric adjcency map (see above)
    private boolean hasEdge(String n1, String n2) {

        boolean from_n1_to_n2 = false;

        if (adj_map.containsKey(n1)) {
            from_n1_to_n2 = adj_map.get(n1).stream().anyMatch((e)->e.destination().equals(n2));
        }

        return from_n1_to_n2;
    }

    /**
     * This function gives the count of vertices
     * @return
     */
    private int getVertexCount() {
        return adj_map.keySet().size();
    }

    /**
     * This function gives the count of edges
     * @param bidirection
     * @return
     */
    private int getEdgesCount(boolean bidirection) {
        int count = 0;
        for (String v : adj_map.keySet()) {
            count += adj_map.get(v).size();
        }
        if (bidirection) {
            count = count / 2;
        }
        return count;
    }

    /**
     * This function gives whether  a vertex is present or not.
     * @param s
     * @return
     */
    private boolean hasVertex(String s) {
        return adj_map.containsKey(s);
    }

    public ArrayList<ArrayList<Edge>> findPath(String start,String end, boolean stopfirst)
    {
        var visited_vertex = new ArrayList<String>();
        var queue_vertex = new LinkedList<String>();
        var paths = new ArrayList<ArrayList<Edge>>();

        var last_parent = new HashMap<String,Edge>();
        last_parent.put("ROOT",null);

        int nfound = 0;

        visited_vertex.add(start);
        queue_vertex.add(start);

        while (queue_vertex.size() != 0) {
            var current_vertex = queue_vertex.poll();

            var list_edges =adj_map.get(current_vertex);

            for (Edge e :list_edges) {
                if (e.destination().equals(end))  {
                    nfound++;
                    System.out.println("Found "+nfound+" path(s)");
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
     *
     * @param current_node
     * @param end_node
     * @param visited
     */
    /*
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
                var list = (LinkedList<Edge>)s.readObject();
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

    // nodes of graph
    public synchronized void addNode(LNode node) {
        addNode(node.getPubKey());
    }

    public synchronized void addNode(String pubkey) {
        addVertex(pubkey);
    }
    // to edge properties
    @SuppressWarnings("EmptyMethod")
    public synchronized void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public synchronized boolean hasChannel(LNChannel channel) {
       return hasEdge(channel.getNode1PubKey(),channel.getNode2PubKey());
    }

    private ChannelGraph() {};

    public ChannelGraph(String root_node){
        this.root_node = root_node;
        addNode(root_node);
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
                builder.append(w).append(" ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
