public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    void broadcastToPeers(MessageGossip msg);
    ChannelGraph getChannelGraph();

    public boolean advanceChannelStatus(String channel_id, int node1_balance, int node2_balance );

}
