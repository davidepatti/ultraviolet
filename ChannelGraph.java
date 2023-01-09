public class ChannelGraph {

    Log log = s-> System.out.println("p2p:"+s);

    // nodes of graph
    public void addNode(String node_id) {
    }
    // edges of graph
    public void addChannel(Channel ch) {
        // TODO: add nodes if not present
    }
    // to edge properties
    public void updateChannel(String channel_id, int base_fee, int ppm_fee, int cltv_expiry_delta, long timestamp) {

    }

    public ChannelGraph(){

    }

}
