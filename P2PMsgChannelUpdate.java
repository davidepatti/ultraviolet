public class P2PMsgChannelUpdate extends P2PMessage {

    private final String node;
    private final String channel_id;
    private final int cltv_expiry_delta;
    private final int fee_base_msat;
    private final int fee_ppm;


    public P2PMsgChannelUpdate(String node, String channel_id, int timestamp, int forwardings, LNChannel.Policy policy) {
        super("ANN:"+channel_id,forwardings,timestamp, Type.CHANNEL_UPDATE);
        this.node = node;
        this.channel_id = channel_id;
        cltv_expiry_delta = policy.cltv();
        fee_base_msat = policy.base_fee();
        fee_ppm = policy.fee_ppm();
    }

    public synchronized P2PMsgChannelUpdate getNext() {
        var policy = new LNChannel.Policy(cltv_expiry_delta,fee_base_msat,fee_ppm);
        return new P2PMsgChannelUpdate(this.node,this.channel_id,this.timestamp,this.forwardings+1,policy);
    }

    public String getNode() {
        return node;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public int getCltv_expiry_delta() {
        return cltv_expiry_delta;
    }

    public int getFee_base_msat() {
        return fee_base_msat;
    }

    public int getFee_ppm() {
        return fee_ppm;
    }

}
