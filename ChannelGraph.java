import java.io.*;
import java.util.*;

public class ChannelGraph implements Serializable  {

    private static final boolean DEBUG = true;
    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph
    transient private Map<String, List<String>> adj_map = new HashMap<>();

    // This function adds a new vertex to the graph
    private void addVertex(String s) {
        adj_map.putIfAbsent(s, new LinkedList<>());
    }

    private String root_node;

    public void clear() {
        this.adj_map.clear();
    }

    /**
     * This function adds the edge between source to destination
     * @param source
     * @param destination
     * @param bidirectional
     */
    private void addEdge(String source, String destination, boolean bidirectional) {

        adj_map.putIfAbsent(source, new LinkedList<>());
        adj_map.putIfAbsent(destination, new LinkedList<>());

        // do not add channel edge if source and destination area already connected
        // TODO: in theory, multiple channel could be opened with differen id
        if (this.hasEdge(source,destination)) return;

        adj_map.get(source).add(destination);

        if (bidirectional) {
            adj_map.get(destination).add(source);
        }
    }

    // TODO: assuming symmetric adjcency map (see above)
    private boolean hasEdge(String n1, String n2) {

        boolean x1 = false;

        if (adj_map.containsKey(n1)) {
            x1 = adj_map.get(n1).stream().anyMatch((e)->e.equals(n2));
        }

        return x1;
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

    public ArrayList<ArrayList<String>> findPath(String start,String end, boolean stopfirst)
    {
        var visited = new ArrayList<String>();
        var queue = new LinkedList<String>();
        var paths = new ArrayList<ArrayList<String>>();

        var last_parent = new HashMap<String,String>();
        last_parent.put(start,"ROOT");

        int nfound = 0;

        visited.add(start);
        queue.add(start);

        while (queue.size() != 0) {
            var s = queue.poll();

            var list_neighbors =adj_map.get(s);

            for (String n :list_neighbors) {
                if (n.equals(end))  {
                    nfound++;
                    System.out.println("Found "+nfound+" path(s)");
                    var path = new ArrayList<String>();
                    path.add(end);
                    path.add(s);

                    String current = last_parent.get(s);
                    while (!current.equals("ROOT")) {
                        path.add(current);
                        current = last_parent.get(current);
                    }
                    paths.add(path);
                    if (stopfirst) return paths;
                    // no need to go deeper along that path
                    visited.add(n);
                    continue;
                }
                if (!visited.contains(n)) {
                    last_parent.put(n,s);
                    visited.add(n);
                    queue.add(n);
                }
            }
        }
        return paths;
    }

    /**
     * prints BFS traversal
     * @param start
     * @param end
     * @return
     */
    public ArrayList<ArrayList<String>> BFS(String start,String end)
    {
        var visited = new ArrayList<String>();
        var queue = new LinkedList<String>();
        var paths = new ArrayList<ArrayList<String>>();

        var last_parent = new HashMap<String,String>();
        last_parent.put(start,"ROOT");

        // Mark the current node as visited and enqueue it
        visited.add(start);
        queue.add(start);

        while (queue.size() != 0) {
            // Dequeue a vertex from queue and print it
            var s = queue.poll();
            if (DEBUG) {
                System.out.println("Visiting "+s);
                System.out.println("---------------------------------------------");
            }

            for (String n : adj_map.get(s)) {
                System.out.println("Looking "+s+"--->"+n);

                if (n.equals(end))  {
                    if (DEBUG)
                        System.out.println("FOUND "+end);

                    var path = new ArrayList<String>();
                    path.add(end);
                    path.add(s);

                    String current = last_parent.get(s);
                    while (!current.equals("ROOT")) {
                       path.add(current);
                       current = last_parent.get(current);
                    }

                    paths.add(path);
                }
                if (!visited.contains(n)) {
                    System.out.println("\tWill visit "+n+" (last parent "+s+")");
                    last_parent.put(n,s);
                    visited.add(n);
                    queue.add(n);
                }
            }
            if (DEBUG)
                System.out.println("---------------------------------------------");
        }
        return paths;
    }
    /**
     *
     * @param current_node
     * @param end_node
     * @param visited
     */
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


    public Map<String,List<String>> getAdj_map() {
        return adj_map;
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
                var list = (LinkedList<String>)s.readObject();
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
    // edges of graph
    public synchronized void addChannel(LNChannel channel) {
        addChannel(channel.getNode1PubKey(),channel.getNode2PubKey());
    }
    public synchronized void addChannel(String node1, String node2) {
        addEdge(node1,node2,true);
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
            for (String w : adj_map.get(v)) {
                builder.append(w).append(" ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
