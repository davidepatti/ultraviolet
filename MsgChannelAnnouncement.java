public class MsgChannelAnnouncement {
    final LNChannel channel;
    private int forwardings = 0;

    public MsgChannelAnnouncement(LNChannel channel) {
        this.channel = channel;
    }

    public MsgChannelAnnouncement getNext() {
        var next = new MsgChannelAnnouncement(this.channel);
        next.forwardings = this.forwardings+1;
        return next;
    }

    public int getForwardings() {
        return forwardings;
    }

    @Override
    public String toString() {
        return "MsgChannelAnnouncement{" +
                "channel_id=" + channel.getId() +
                ", forwardings=" + forwardings +
                '}';
    }
}
