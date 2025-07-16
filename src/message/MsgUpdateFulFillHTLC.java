package message;

public class MsgUpdateFulFillHTLC extends P2PMessage {
    private final String channel_id;
    private final int id;
    private final long payment_preimage;


    public MsgUpdateFulFillHTLC(String channel_id, int id, long preimage) {

        super(P2PMessage.Type.UPDATE_FULFILL_HTLC);
        this.channel_id = channel_id;
        this.id = id;
        this.payment_preimage = preimage;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public int getId() {
        return id;
    }

    public long getPayment_preimage() {
        return payment_preimage;
    }

    @Override
    public String toString() {
        return "message.MsgUpdateFulFillHTLC{" +
                "ch_id:'" + channel_id + '\'' +
                ", id:" + id +
                ", preimage:'" + payment_preimage + '\'' +
                '}';
    }
}
