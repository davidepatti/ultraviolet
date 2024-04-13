import java.util.Objects;

public class GossipMsgChannelAnnouncement extends GossipMsg {

    private final String short_channel_id;
    private final String node_id_1;
    private final String node_id_2;
    private final int funding;



    public GossipMsgChannelAnnouncement(String sender, String channel_id, String pubkey1, String pubkey2, int funding, int timestamp, int forwardings) {
        super(sender,forwardings,timestamp, Type.CHANNEL_ANNOUNCE);
        this.short_channel_id = channel_id;
        this.node_id_1 = pubkey1;
        this.node_id_2 = pubkey2;
        this.funding = funding;
    }

    public String getChannelId() {
        return short_channel_id;
    }

    public String getNodeId1() {
        return node_id_1;
    }

    public String getNodeId2() {
        return node_id_2;
    }

    public int getFunding() {
        return funding;
    }

    public synchronized GossipMsgChannelAnnouncement nextMsgToForward(String sender) {
        return new GossipMsgChannelAnnouncement(sender,short_channel_id,getNodeId1(),getNodeId2(),funding, timestamp,forwardings+1);
    }

    @Override
    public String toString() {
        return super.toString()+" id:" + short_channel_id +", node1:'" + node_id_1 + ", node2:" + node_id_2 + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GossipMsgChannelAnnouncement that)) return false;

        var e =  short_channel_id.equals(that.short_channel_id) && timestamp == that.timestamp;
        return e;
    }
}
