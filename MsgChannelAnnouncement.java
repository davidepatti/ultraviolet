public class MsgChannelAnnouncement extends P2PMessage{

    private final LNChannel announced_channel;

    public MsgChannelAnnouncement(LNChannel channel,int timestamp, int forwardings) {
        super("ANN:"+channel.getId(),forwardings,timestamp);
        this.announced_channel = channel;
        this.msgType = Type.CHANNEL_ANNOUNCE;
    }

    public LNChannel getLNChannel() {
        return announced_channel;
    }

    public synchronized MsgChannelAnnouncement getNext() {
        return new MsgChannelAnnouncement(this.announced_channel,this.timestamp,this.forwardings+1);
    }

}
