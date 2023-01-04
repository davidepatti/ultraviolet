public class ChannelGraph {

    Log log = s-> System.out.println("p2p:"+s);

    public void channel_announcement(Channel ch) {
        log.print("channel_announcement "+ch.getChannel_id());
    }
    public void node_announcement(Node n) {
        log.print("node_announcement "+n.getPubkey());
    }

    public ChannelGraph(){

    }

}
