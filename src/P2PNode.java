public interface P2PNode {

    String getPubKey();
    void deliverMessage(P2PMessage message);
    ChannelGraph getChannelGraph();
}
