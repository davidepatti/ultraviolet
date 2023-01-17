import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public interface P2PNode {

    public String getPubKey();
    public void addPeer(P2PNode node);
    public void announceChannel(LNChannel channel);
    public void broadcastAnnounceChannel(P2PNode target, MsgChannelAnnouncement message);
    //public void receiveAnnounceChannel(String id, LNChannel ch);
    public ChannelGraph getChannelGraph();

    void receiveAnnounceChannel(String pubKey, MsgChannelAnnouncement message);
}
