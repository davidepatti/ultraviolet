package message;

import java.util.Optional;

public class OnionLayer {

    final private Payload payload;
    final private OnionLayer innerLayer;

    public static class Payload {
        final private String short_channel_id;
        final private int amt_to_forward;
        final private int outgoing_cltv_value;
        final private String payment_secret;

        public Payload(String short_channel_id, int amt_to_forward, int outgoing_cltv_value, String payment_secret) {
            this.short_channel_id = short_channel_id;
            this.amt_to_forward = amt_to_forward;
            this.outgoing_cltv_value = outgoing_cltv_value;
            this.payment_secret = payment_secret;
        }

        public int getAmtToForward() {
            return amt_to_forward;
        }

        public String getShortChannelId() {
            return short_channel_id;
        }

        public Optional<String> getPayment_secret() {
            return Optional.ofNullable(payment_secret);
        }

        @Override
        public String toString() {
            return "(*" +
                    "ch_id='" + short_channel_id + '\'' + ", amt:" + amt_to_forward + ", out_cltv:" + outgoing_cltv_value + ", hash:" + payment_secret + "*)";
        }

        public int getOutgoingCLTV() {
            return outgoing_cltv_value;
        }
    }

    public Payload getPayload() {
        return payload;
    }

    public Optional<OnionLayer> getInnerLayer() {
        return Optional.ofNullable(innerLayer);
    }

    public OnionLayer(Payload payload, OnionLayer layer) {
        this.payload = payload;
        this.innerLayer = layer;
    }

    @Override
    public String toString() {
        return "Onion(" + payload + ')';
    }
}
