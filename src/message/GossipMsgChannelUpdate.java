package message;

import network.LNChannel;

public class GossipMsgChannelUpdate extends GossipMsg {

    private final String channel_id;
    private final String signerId;
    private final LNChannel.Policy updated_policy;

    public String getSignerId() {
        return signerId;
    }

    public GossipMsgChannelUpdate(String senderID, String signer, String channel_id, int timestamp, int forwardings, LNChannel.Policy policy) {
        super(senderID,forwardings,timestamp, Type.CHANNEL_UPDATE);
        this.signerId = signer;
        this.channel_id = channel_id;
        this.updated_policy = policy;
    }

    public GossipMsgChannelUpdate nextMsgToForward(String sender) {
        return new GossipMsgChannelUpdate(sender,signerId,channel_id,timestamp,forwardings+1, updated_policy);
    }

    public String getChannelId() {
        return channel_id;
    }

    public LNChannel.Policy getUpdatedPolicy() {
        return updated_policy;
    }

    @Override
    public String toString() {
        return super.toString() + "ch_id:" + channel_id + "}";
    }
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GossipMsgChannelUpdate that)) return false;

        return channel_id.equals(that.channel_id) && timestamp == that.timestamp && signerId.equals(that.signerId);
    }
}
