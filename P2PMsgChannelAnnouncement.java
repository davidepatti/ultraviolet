public class P2PMsgChannelAnnouncement extends P2PMessage{

    private final String short_channel_id;
    private final String node_id_1;
    private final String node_id_2;

    public P2PMsgChannelAnnouncement(String channel_id, String pubkey1, String pubkey2, int timestamp, int forwardings) {
        super("ANN:"+channel_id,forwardings,timestamp, Type.CHANNEL_ANNOUNCE);
        this.short_channel_id = channel_id;
        this.node_id_1 = pubkey1;
        this.node_id_2 = pubkey2;
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

    public synchronized P2PMsgChannelAnnouncement getNext() {
        return new P2PMsgChannelAnnouncement(short_channel_id,getNodeId1(),getNodeId2(),this.timestamp,this.forwardings+1);
    }

    @Override
    public String toString() {
        return super.toString()+" id:" + short_channel_id +", node1:'" + node_id_1 + ", node2:" + node_id_2 + '}';
    }
}
