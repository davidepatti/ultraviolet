public class P2PMsgChannelAnnouncement extends P2PMessage{

    private final LNChannel channel_id;

    public P2PMsgChannelAnnouncement(LNChannel channel, int timestamp, int forwardings) {
        super("ANN:"+channel.getId(),forwardings,timestamp, Type.CHANNEL_ANNOUNCE);
        this.channel_id = channel;
    }

    public LNChannel getLNChannel() {
        return channel_id;
    }

    public synchronized P2PMsgChannelAnnouncement getNext() {
        return new P2PMsgChannelAnnouncement(this.channel_id,this.timestamp,this.forwardings+1);
    }

}
