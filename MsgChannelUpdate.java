public class MsgChannelUpdate extends P2PMessage {

    private final String node;
    private final String channel_id;
    private final LNChannel.Policy updated_policy;


    public MsgChannelUpdate(String node, String channel_id, int timestamp, int forwardings, LNChannel.Policy policy) {
        super("UP:"+channel_id,forwardings,timestamp, Type.CHANNEL_UPDATE);
        this.node = node;
        this.channel_id = channel_id;
        this.updated_policy = policy;
    }

    public synchronized MsgChannelUpdate getNext() {
        return new MsgChannelUpdate(this.node,this.channel_id,this.timestamp,this.forwardings+1, updated_policy);
    }

    public String getNode() {
        return node;
    }

    public String getChannelId() {
        return channel_id;
    }

    public LNChannel.Policy getUpdatedPolicy() {
        return updated_policy;
    }

    @Override
    public String toString() {
        return super.toString() +
                "{node:" + node + "channel_id:" + channel_id + "}";
    }
}
