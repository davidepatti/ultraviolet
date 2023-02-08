public class P2PMsgChannelUpdate extends P2PMessage {

    private final String node;
    private final String channel_id;
    private final LNChannel.Policy updated_policy;
    // TODO: to replace older msg
    private final int timestamp;


    public P2PMsgChannelUpdate(String node, String channel_id, int timestamp, int forwardings, LNChannel.Policy policy) {
        super("UP:"+channel_id,forwardings,timestamp, Type.CHANNEL_UPDATE);
        this.node = node;
        this.timestamp = timestamp;
        this.channel_id = channel_id;
        this.updated_policy = policy;
    }

    public synchronized P2PMsgChannelUpdate getNext() {
        return new P2PMsgChannelUpdate(this.node,this.channel_id,this.timestamp,this.forwardings+1, updated_policy);
    }

    public String getNode() {
        return node;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public LNChannel.Policy getUpdatedPolicy() {
        return updated_policy;
    }
}
