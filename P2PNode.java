public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    void broadcastToPeers(MessageGossip msg);
    ChannelGraph getChannelGraph();

    //void receiveMessage(Message msg);
}
