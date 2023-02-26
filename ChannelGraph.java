import java.io.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class ChannelGraph implements Serializable  {

    private String root_node;
    final transient private Consumer<String> Log = UVNetworkManager.Log;
    public record Edge(String id, String source, String destination, int capacity, LNChannel.Policy policy) implements Serializable {
        @Override
        public String toString() {
            return "{ch:"+id+"("+source + "-"+ destination + ")["+capacity +"]"+policy+"}";
        }
    }

    private void log(String s) {
        Log.accept(s);
    }

    @Serial
    private static final long serialVersionUID = 120676L;
    // We use Hashmap to store the edges in the graph, indexed by node id as keys
    transient private Map<String, List<Edge>> adj_map = new ConcurrentHashMap<>();

    // This function adds a new vertex to the graph
    private void addVertex(String node_id) {
        adj_map.putIfAbsent(node_id, new LinkedList<>());
    }

    /**
     *
     * @param channel
     */
    public void addLNChannel(LNChannel channel) {

        if (channel==null) {
            log("FATAL ERROR: null channel ");
            return;
        }

        var node1pub = channel.getNode1PubKey();
        var node2pub = channel.getNode2PubKey();


        adj_map.putIfAbsent(node1pub, new LinkedList<>());
        adj_map.putIfAbsent(node2pub, new LinkedList<>());

        if (this.hasChannel(channel.getId())) {
            log("WARNING: calling addChannel with already existing edge for channel "+channel.getId());
            return;
        }

        var edge1 = new Edge(channel.getId(),node1pub,node2pub,channel.getCapacity(),channel.getNode1Policy());
        var edge2 = new Edge(channel.getId(),node2pub,node1pub,channel.getCapacity(),channel.getNode2Policy());

        adj_map.get(channel.getNode1PubKey()).add(edge1);
        adj_map.get(channel.getNode2PubKey()).add(edge2);

    }

    public void addAnnouncedChannel(MsgChannelAnnouncement msg) {

        var channel_id = msg.getChannelId();
        var node1 = msg.getNodeId1();
        var node2 = msg.getNodeId2();
        adj_map.putIfAbsent(node1, new LinkedList<>());
        adj_map.putIfAbsent(node2, new LinkedList<>());
        adj_map.get(node1).add(new Edge(channel_id,node1,node2,msg.getFunding(),null));
        adj_map.get(node1).add(new Edge(channel_id,node2,node1,msg.getFunding(),null));
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
                @SuppressWarnings("unchecked") var list = (LinkedList<Edge>)s.readObject();
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


    public void addNode(String pubkey) {
        addVertex(pubkey);
    }
    // to edge properties
    public synchronized void updateChannel(String channel_id, LNChannel.Policy policy) {
        for (List<Edge> list:adj_map.values()){
            for (Edge e:list) {
                if (e.id().equals(channel_id)) {
                    var new_edge = new Edge(channel_id,e.source,e.destination,e.capacity,policy);
                    if (!list.remove(e)) {
                        log("FATAL:Cannot remove "+e+" for matching channel id"+channel_id);
                        System.exit(-1);
                    }
                    //log("Adding edge "+new_edge+" on node graph:"+node);
                    list.add(new_edge);
                    return;
                }
            }
        }
        log("YOU SHOULD NOT READ THIS, check updateChannel "+channel_id+" in "+this.root_node);
    }

    public boolean hasChannel(String channel_id) {
        for (List<Edge> list:adj_map.values() ) {
           for (Edge e:list) {
               if (e.id().equals(channel_id)) {
                   return true;
               }
           }
        }
        return false;
    }

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
