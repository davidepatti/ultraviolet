public class MsgUpdateAddHTLC extends P2PMessage {
    private final String channel_id;
    private final int id;
    private final int amount;
    private final String payment_hash;
    private final int cltv;
    private final OnionLayer onionPacket;


    public MsgUpdateAddHTLC(String channel_id, int id, int amount, String hash, int cltv, OnionLayer onion_packet) {

        super(channel_id+"_"+id, 0, 0, Type.UPDATE_ADD_HTCL);
        this.channel_id = channel_id;
        this.id = id;
        this.amount = amount;
        this.payment_hash = hash;
        this.cltv = cltv;
        this.onionPacket = onion_packet;
    }

    /**
     * @return 
     */
    @Override
    public P2PMessage getNext() {
        return null;
    }

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

    public int getCltv() {
        return cltv;
    }

    public OnionLayer getOnionPacket() {
        return onionPacket;
    }

    @Override
    public String toString() {
        return "MsgUpdateAddHTLC{" +
                "channel_id='" + channel_id + '\'' +
                ", id=" + id +
                ", amount=" + amount +
                ", payment_hash='" + payment_hash + '\'' +
                ", cltv=" + cltv +
                '}';
    }
}
