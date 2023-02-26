import java.io.Serializable;

public class MsgOpenChannel extends Message implements Serializable {

    final private String channel_id;
    final private int funding_satoshis;
    final private int push_msat;
    final private int channel_reserve_satoshis;
    final private int to_self_delay;

    public String getChannel_id() {
        return channel_id;
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


    public MsgOpenChannel(String channel_id, int funding, int reserve, int push_msat, int to_self_delay, String funding_pubkey) {
        super(Type.OPEN_CHANNEL);
        this.channel_id  = channel_id;
        this.funding_satoshis = funding;
        this.channel_reserve_satoshis = reserve;
        this.push_msat = push_msat;
        this.to_self_delay = to_self_delay;
        this.funding_pubkey = funding_pubkey;
    }

    @Override
    public String toString() {
        return "MsgOpenChannel{" +
                "channel_id='" + channel_id + '\'' +
                ", funding_satoshis=" + funding_satoshis +
                ", push_msat=" + push_msat +
                ", channel_reserve_satoshis=" + channel_reserve_satoshis +
                ", to_self_delay=" + to_self_delay +
                ", funding_pubkey='" + funding_pubkey + '\'' +
                '}';
    }
}
