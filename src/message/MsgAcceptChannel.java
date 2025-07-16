package message;

import java.io.Serializable;

public class MsgAcceptChannel extends P2PMessage implements Serializable {
    final private int to_self_delay;
    final private String temporary_channel_id;
    final private String funding_pubkey;
    final private int minimum_depth;

    public String getTemporary_channel_id() {
        return temporary_channel_id;
    }

    public String getFundingPubkey() {
        return funding_pubkey;
    }

    public int getMinimum_depth() {
        return minimum_depth;
    }

    public MsgAcceptChannel(String temporary_channel_id, int minimum_depth, int to_self_delay, String funding_pubkey) {
        super(P2PMessage.Type.ACCEPT_CHANNEL);
        this.temporary_channel_id = temporary_channel_id;
        this.to_self_delay = to_self_delay;
        this.funding_pubkey = funding_pubkey;
        this.minimum_depth = minimum_depth;
    }

    @Override
    public String toString() {
        return "message.MsgAcceptChannel{" +
                "to_self_delay=" + to_self_delay +
                ", temporary_channel_id='" + temporary_channel_id + '\'' +
                ", funding_pubkey='" + funding_pubkey + '\'' +
                ", minimum_depth=" + minimum_depth +
                '}';
    }
}
