public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    //void broadcastAnnounceChannel(P2PNode target, MsgChannelAnnouncement message);
    void broadcastToPeers(P2PMessage msg);
    ChannelGraph getChannelGraph();

    void receiveP2PMessage(P2PMessage msg);
}
