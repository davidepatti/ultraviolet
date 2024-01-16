public interface P2PNode {

    String getPubKey();
    void broadcastToPeers(String fromId, GossipMsg msg);
    ChannelGraph getChannelGraph();

    boolean advanceChannelStatus(String channel_id, int node1_balance, int node2_balance);

}
