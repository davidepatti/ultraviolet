package message;

import java.io.Serializable;

public class MsgOpenChannel extends P2PMessage implements Serializable {

    final private String temporary_channel_id;
    final private int funding_satoshis;
    final private int push_msat;
    final private int channel_reserve_satoshis;
    final private int to_self_delay;

    public String getTemporary_channel_id() {
        return temporary_channel_id;
    }

    public int getFunding() {
        return funding_satoshis;
    }

    public int getPushMSAT() {
        return push_msat;
    }

    public int getChannelReserve() {
        return channel_reserve_satoshis;
    }

    public int getTo_self_delay() {
        return to_self_delay;
    }

    public String getFunding_pubkey() {
        return funding_pubkey;
    }

    final private String funding_pubkey;


    public MsgOpenChannel(String temp_channel_id, int funding, int reserve, int push_msat, int to_self_delay, String funding_pubkey) {
        super(P2PMessage.Type.OPEN_CHANNEL);
        this.temporary_channel_id = temp_channel_id;
        this.funding_satoshis = funding;
        this.channel_reserve_satoshis = reserve;
        this.push_msat = push_msat;
        this.to_self_delay = to_self_delay;
        this.funding_pubkey = funding_pubkey;
    }

    @Override
    public String toString() {
        return "message.MsgOpenChannel{" +
                "temp_id='" + temporary_channel_id + '\'' +
                ", funding_sat=" + funding_satoshis +
                ", push_msat=" + push_msat +
                ", reserve=" + channel_reserve_satoshis +
                ", to_self_delay=" + to_self_delay +
                ", funding_pubkey='" + funding_pubkey + '\'' +
                '}';
    }
}
