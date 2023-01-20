import java.io.Serializable;

public class ChannelGraph implements Serializable {

    private final Graph<LNode> graph = new Graph<>();

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
