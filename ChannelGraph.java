public class ChannelGraph {

    private final Graph<LNode> graph = new Graph<>();

    Log log = s-> System.out.println("p2p:"+s);

    // nodes of graph
    public void addNode(LNode node) {
        graph.addVertex(node);
    }
    // edges of graph
    public void addChannel(LNChannel channel) {
        graph.addEdge(channel.getNode1(),channel.getNode2(),false);
    }
    // to edge properties
    public void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {
    }

    public boolean hasChannel(LNChannel channel) {
       return graph.hasEdge(channel.getNode1(),channel.getNode2());
    }

    public ChannelGraph(){

    }

    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        for (LNode v : this.graph.getMap().keySet()) {
            builder.append(v.getPubKey() + ": ");
            for (LNode w : this.graph.getMap().get(v)) {
                builder.append(w.getPubKey() + " ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }
}
