public interface P2PNode {

    String getPubKey();
    void broadcastToPeers(String fromId, GossipMsg msg);
    ChannelGraph getChannelGraph();


}
