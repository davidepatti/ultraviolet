import java.io.Serializable;

public class MsgAcceptChannel extends Message implements Serializable {
    final private int to_self_delay;
    final private String channel_id;
    final private String funding_pubkey;

    public int getTo_self_delay() {
        return to_self_delay;
    }

    public String getChannel_id() {
        return channel_id;
    }

    public String getFundingPubkey() {
        return funding_pubkey;
    }



    public MsgAcceptChannel(String channel_id, int to_self_delay, String funding_pubkey) {
        super(Type.ACCEPT_CHANNEL);
        this.channel_id = channel_id;
        this.to_self_delay = to_self_delay;
        this.funding_pubkey = funding_pubkey;
    }

}
