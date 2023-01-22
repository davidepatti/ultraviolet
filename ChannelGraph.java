import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.ArrayList;

public class ChannelGraph implements Serializable {

    private static final long serialVersionUID = 120676L;

    transient private Graph<LNode> graph = new Graph<>();
    transient private Graph<String> restored_graph;

    public Graph<LNode> getGraph() {
        return graph;
    }

    public void restoreChannelGraph(UVManager uvm) {
        graph = new Graph<>();

        for (String v : restored_graph.getMap().keySet()) {
            ArrayList<LNode> list = new ArrayList<>();
            for (String w : restored_graph.getMap().get(v)) {
                list.add(uvm.getUVnodes().get(w));
            }
            graph.getMap().put(uvm.getUVnodes().get(v),list);
        }
    }

    private void readObject(ObjectInputStream s) {
        int n_lists;
        try {
            s.defaultReadObject();
            restored_graph = new Graph<>();
            n_lists = s.readInt();

            for (int i=0;i<n_lists;i++) {
                var node_pubkey = (String) s.readObject();
                int sub_nodes = s.readInt();
                var list_pubkeys = new ArrayList<String>();
                for (int j=0;j<sub_nodes;j++) {
                    list_pubkeys.add((String) s.readObject());
                }
                restored_graph.getMap().put(node_pubkey,list_pubkeys);
            }

        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

    }

    private void writeObject(ObjectOutputStream s) {
        try {
            s.defaultWriteObject();
            int n_key = graph.getMap().keySet().size();
            s.writeInt(n_key);

            for (LNode v : graph.getMap().keySet()) {
                s.writeObject(v.getPubKey());

                n_key = graph.getMap().get(v).size();
                s.writeInt(n_key);

                for (LNode w : this.graph.getMap().get(v)) {
                    s.writeObject(w.getPubKey());
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // nodes of graph
    public synchronized void addNode(LNode node) {
        graph.addVertex(node);
    }
    // edges of graph
    public synchronized void addChannel(LNChannel channel) {
        graph.addEdge(channel.getNode1(),channel.getNode2(),false);
    }
    // to edge properties
    @SuppressWarnings("EmptyMethod")
    public synchronized void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public synchronized boolean hasChannel(LNChannel channel) {
       return graph.hasEdge(channel.getNode1(),channel.getNode2());
    }

    public ChannelGraph(){

    }

    public int getNodeCount() {
       return graph.getVertexCount();
    }

    public int getChannelCount() {
        return graph.getEdgesCount(false);
    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        for (LNode v : this.graph.getMap().keySet()) {
            builder.append(v.getPubKey()).append(": ");
            for (LNode w : this.graph.getMap().get(v)) {
                builder.append(w.getPubKey()).append(" ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
