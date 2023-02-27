public class MsgUpdateFulFillHTLC extends Message {
    private final String channel_id;
    private final int id;
    private final String payment_preimage;


    public MsgUpdateFulFillHTLC(String channel_id, int id, String preimage) {

        super(Type.UPDATE_FULFILL_HTLC);
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

    @Override
    public String toString() {
        return "MsgUpdateFulFillHTLC{" +
                "channel_id='" + channel_id + '\'' +
                ", id=" + id +
                ", payment_preimage='" + payment_preimage + '\'' +
                '}';
    }
}
