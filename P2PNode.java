public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    void announceChannel(LNChannel channel);
    void broadcastAnnounceChannel(P2PNode target, MsgChannelAnnouncement message);
    //public void receiveAnnounceChannel(String id, LNChannel ch);
    ChannelGraph getChannelGraph();

    void receiveAnnounceChannel(String pubKey, MsgChannelAnnouncement message);
}
