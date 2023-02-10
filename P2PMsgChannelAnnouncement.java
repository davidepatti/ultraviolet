public class P2PMsgChannelAnnouncement extends P2PMessage{

    private final String channel_id;

    public P2PMsgChannelAnnouncement(String channel_id, int timestamp, int forwardings) {
        super("ANN:"+channel_id,forwardings,timestamp, Type.CHANNEL_ANNOUNCE);
        this.channel_id = channel_id;
    }

    public String getChannelId() {
        return channel_id;
    }

    public synchronized P2PMsgChannelAnnouncement getNext() {
        return new P2PMsgChannelAnnouncement(channel_id,this.timestamp,this.forwardings+1);
    }

    @Override
    public String toString() {
        return super.toString()+"{channel_id:" + channel_id + '}';
    }
}
