public class ChannelGraph {

    private final Graph<Node> graph = new Graph<>();

    Log log = s-> System.out.println("p2p:"+s);

    // nodes of graph
    public void addNode(Node node) {
        graph.addVertex(node);
    }
    // edges of graph
    public void addChannel(Channel ch) {
        graph.addEdge(ch.getInitiator_node(),ch.getPeer_node(),false);
    }
    // to edge properties
    public void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public boolean hasChannel(Channel ch) {
       return graph.hasEdge(ch.getInitiator_node(),ch.getPeer_node());
    }

    public ChannelGraph(){

    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        for (Node v : this.graph.getMap().keySet()) {
            builder.append(v.getPubkey() + ": ");
            for (Node w : this.graph.getMap().get(v)) {
                builder.append(w.getPubkey() + " ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
