import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;

public class ChannelGraph implements Serializable  {

    private static boolean DEBUG = true;
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph
    transient private Map<String, List<String>> adj_map = new HashMap<>();

    // This function adds a new vertex to the graph
    private void addVertex(String s) {
        adj_map.putIfAbsent(s,new LinkedList<String>());
    }

    /**
     * This function adds the edge between source to destination
     * @param source
     * @param destination
     * @param bidirectional
     */
    private void addEdge(String source, String destination, boolean bidirectional) {

        adj_map.putIfAbsent(source,new LinkedList<String>());
        adj_map.putIfAbsent(destination,new LinkedList<String>());

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
        int v = adj_map.keySet().size();
        return v;
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
        addVertex(node.getPubKey());
    }
    // edges of graph
    public synchronized void addChannel(LNChannel channel) {
        addEdge(channel.getNode1().getPubKey(),channel.getNode2().getPubKey(),true);
    }
    // to edge properties
    @SuppressWarnings("EmptyMethod")
    public synchronized void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public synchronized boolean hasChannel(LNChannel channel) {
       return hasEdge(channel.getNode1().getPubKey(),channel.getNode2().getPubKey());
    }

    public ChannelGraph(){

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

        adj_map.keySet().stream().sorted().forEach((e)->list.add(e));

        for (String v : list) {
            builder.append(v).append(": ");
            for (String w : adj_map.get(v)) {
                builder.append(w).append(" ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
    /*
    public void DFS_util(LNode current_node, HashSet<LNode> visited) {
        visited.add(current_node);
        if (DEBUG)
            System.out.println("visiting:"+current_node.getPubKey());

        Iterator<LNode> i = adj_map.get(current_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            if (DEBUG)
                System.out.print("   -> Considering:"+n.getPubKey()+ " ");
            if (!visited.contains(n)) {
                System.out.println("NOT VISITED");
                DFS_util(n,visited);
            }
        }
    }

    public void DFS(LNode start_node) {
        var visited = new HashSet<LNode>();
        DFS_util(start_node,visited);
    }

     */
}
