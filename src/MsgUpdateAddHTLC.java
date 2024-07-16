public class MsgUpdateAddHTLC extends P2PMessage {
    private final String channel_id;
    private final int id;
    private final int amount;
    private final String payment_hash;
    private final int cltv_expiry;
    private final OnionLayer onionPacket;


    public MsgUpdateAddHTLC(String channel_id, int id, int amount, String hash, int cltv_expiry, OnionLayer onion_packet) {

        super(Type.UPDATE_ADD_HTLC);
        this.channel_id = channel_id;
        this.id = id;
        this.amount = amount;
        this.payment_hash = hash;
        this.cltv_expiry = cltv_expiry;
        this.onionPacket = onion_packet;
    }

    /**
     * @return 
     */
    public String getChannel_id() {
        return channel_id;
    }

    public int getId() {
        return id;
    }

    public int getAmount() {
        return amount;
    }

    public String getPayment_hash() {
        return payment_hash;
    }

    public int getCLTVExpiry() {
        return cltv_expiry;
    }

    public OnionLayer getOnionPacket() {
        return onionPacket;
    }

    @Override
    public String toString() {
        return "MsgUpdateAddHTLC{" +
                "ch:'" + channel_id + '\'' +
                ", id:" + id +
                ", amt:" + amount +
                ", hash:'" + Kit.shortString(payment_hash) + '\'' +
                ", cltv_expiry:" + cltv_expiry +
                '}';
    }
}
