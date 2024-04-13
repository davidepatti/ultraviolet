public class MsgUpdateFailHTLC extends P2PMessage {
    private final String channel_id;
    private final int id;
    private final String reason;


    public MsgUpdateFailHTLC(String channel_id, int id, String reason) {

        super(Type.UPDATE_FAIL_HTLC);
        this.channel_id = channel_id;
        this.id = id;
        this.reason = reason;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public String getReason() {
        return reason;
    }

    public int getId() {
        return id;
    }

    @Override
    public String toString() {
        return "MsgUpdateFailHTLC{" + "ch_id:'" + channel_id + '\'' + ", id:" + id + ", reason:'" + reason + '\'' + '}';
    }
}
