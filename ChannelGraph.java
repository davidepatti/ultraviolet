import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.*;

public class ChannelGraph implements Serializable  {

    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph
    transient private Map<LNode, List<LNode>> adj_map = new HashMap<>();
    transient private Map<String,List<String>> restore_map = new HashMap<>();

    // This function adds a new vertex to the graph
    private void addVertex(LNode s) {
        if (!adj_map.containsKey(s))
            adj_map.put(s, new LinkedList<LNode>());
    }

    /**
     * This function adds the edge between source to destination
     * @param source
     * @param destination
     * @param bidirectional
     */
    private void addEdge(LNode source, LNode destination, boolean bidirectional) {

        if (!adj_map.containsKey(source))
            addVertex(source);

        if (!adj_map.containsKey(destination))
            addVertex(destination);

        if (this.hasEdge(source,destination)) return;

        adj_map.get(source).add(destination);

        if (bidirectional) {
            adj_map.get(destination).add(source);
        }
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
        for (LNode v : adj_map.keySet()) {
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
    private boolean hasVertex(LNode s) {
        return adj_map.containsKey(s);
    }

    /**
     * This function gives whether an edge is present or not.
     * @param s
     * @param d
     * @return
     */
    private boolean hasEdge(LNode s, LNode d) {

        if (adj_map.containsKey(s)) {
            if (adj_map.get(s).contains(d)) return true;
            else return false;
        }
        return false;
    }


    /**
     *
     * @param current_node
     * @param end_node
     * @param visited
     */
    public void DFS_path_util(LNode current_node, LNode end_node, HashSet<LNode> visited) {
        visited.add(current_node);
        System.out.println("visiting:"+current_node);
        if (current_node.equals(end_node)) System.out.println("FOUND!");

        Iterator<LNode> i = adj_map.get(current_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            System.out.print("   -> Considering:"+n+ " ");
            if (!visited.contains(n)) {
                DFS_path_util(n,end_node,visited);
            }
        }
    }

    public void DFSFindPath(LNode start_node,LNode end_node) {
        var visited = new HashSet<LNode>();
        System.out.println("Starting from "+start_node+" destination "+end_node);
        DFS_path_util(start_node,end_node,visited);
    }

    public void DFS_util(LNode current_node, HashSet<LNode> visited) {
        visited.add(current_node);
        System.out.println("visiting:"+current_node);

        Iterator<LNode> i = adj_map.get(current_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            System.out.print("   -> Considering:"+n+ " ");
            if (!visited.contains(n)) {
                System.out.println("NOT VISITED");
                DFS_util(n,visited);
            }
        }
    }

    public void DFS(LNode start_node) {
        var visited = new HashSet<LNode>();
        System.out.println("Starting from "+start_node);
        DFS_util(start_node,visited);
    }


    public Map<LNode,List<LNode>> getAdj_map() {
        return adj_map;
    }

    public void restoreChannelGraph(UVManager uvm) {

        adj_map = new HashMap<>();

        for (String v : restore_map.keySet()) {
            ArrayList<LNode> list = new ArrayList<>();
            for (String w : restore_map.get(v)) {
                list.add(uvm.getUVnodes().get(w));
            }
            adj_map.put(uvm.getUVnodes().get(v),list);
        }
    }

    private void readObject(ObjectInputStream s) {
        int n_lists;
        try {
            s.defaultReadObject();
            restore_map = new HashMap<>();
            n_lists = s.readInt();

            for (int i=0;i<n_lists;i++) {
                var node_pubkey = (String) s.readObject();
                int sub_nodes = s.readInt();
                var list_pubkeys = new ArrayList<String>();
                for (int j=0;j<sub_nodes;j++) {
                    list_pubkeys.add((String) s.readObject());
                }
                restore_map.put(node_pubkey,list_pubkeys);
            }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    private void writeObject(ObjectOutputStream s) {
        try {
            s.defaultWriteObject();
            int n_key = adj_map.keySet().size();
            s.writeInt(n_key);

            for (LNode v : adj_map.keySet()) {
                s.writeObject(v.getPubKey());

                n_key = adj_map.get(v).size();
                s.writeInt(n_key);

                for (LNode w : adj_map.get(v)) {
                    s.writeObject(w.getPubKey());
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // nodes of graph
    public synchronized void addNode(LNode node) {
        addVertex(node);
    }
    // edges of graph
    public synchronized void addChannel(LNChannel channel) {
        addEdge(channel.getNode1(),channel.getNode2(),false);
    }
    // to edge properties
    @SuppressWarnings("EmptyMethod")
    public synchronized void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public synchronized boolean hasChannel(LNChannel channel) {
       return hasEdge(channel.getNode1(),channel.getNode2());
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

        for (LNode v : adj_map.keySet()) {
            builder.append(v.getPubKey()).append(": ");
            for (LNode w : adj_map.get(v)) {
                builder.append(w.getPubKey()).append(" ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
