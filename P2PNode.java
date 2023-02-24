public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    void broadcastToPeers(P2PMessage msg);
    ChannelGraph getChannelGraph();

    void receiveMessage(P2PMessage msg);
}
