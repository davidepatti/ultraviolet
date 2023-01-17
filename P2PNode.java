import java.util.ArrayList;

public interface P2PNode {

    String getPubKey();
    void addPeer(P2PNode node);
    //void broadcastAnnounceChannel(P2PNode target, MsgChannelAnnouncement message);
    void broadcastAnnounceChannel( MsgChannelAnnouncement message);
    ChannelGraph getChannelGraph();

    void receiveAnnounceChannel(String pubKey, MsgChannelAnnouncement message);
}
