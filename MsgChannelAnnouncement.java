public class MsgChannelAnnouncement {
    LNChannel channel;
    int forwardings = 0;

    public MsgChannelAnnouncement(LNChannel channel) {
        this.channel = channel;
    }

    public MsgChannelAnnouncement getNext() {
        var next = new MsgChannelAnnouncement(this.channel);
        next.forwardings = this.forwardings+1;
        return next;
    }

    @Override
    public String toString() {
        return "MsgChannelAnnouncement{" +
                "channel_id=" + channel.getId() +
                ", forwardings=" + forwardings +
                '}';
    }
}
