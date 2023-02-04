import java.util.Optional;

public class OnionLayer {

    final private Payload payload;
    final private OnionLayer innerLayer;

    public static class Payload {
        final private String short_channel_id;
        final private int amt_to_forward;
        final private int outgoing_cltv_value;
        final private Optional<String> payment_secret;

        public Payload(String short_channel_id, int amt_to_forward, int outgoing_cltv_value, Optional<String> payment_secret) {
            this.short_channel_id = short_channel_id;
            this.amt_to_forward = amt_to_forward;
            this.outgoing_cltv_value = outgoing_cltv_value;
            this.payment_secret = payment_secret;
        }

        public int getAmt_to_forward() {
            return amt_to_forward;
        }

        public int getOutgoing_cltv_value() {
            return outgoing_cltv_value;
        }

        public String getShort_channel_id() {
            return short_channel_id;
        }

        public Optional<String> getPayment_secret() {
            return payment_secret;
        }

        @Override
        public String toString() {
            return "\n\nPayload{" +
                    "short_channel_id='" + short_channel_id + '\'' +
                    ", amt_to_forward=" + amt_to_forward +
                    ", outgoing_cltv_value=" + outgoing_cltv_value +
                    ", payment_secret=" + payment_secret +
                    '}';
        }
    }

    public Payload getPayload() {
        return payload;
    }

    public OnionLayer getInnerLayer() {
        return innerLayer;
    }

    public OnionLayer(Payload payload, OnionLayer innerLayer) {
        this.payload = payload;
        this.innerLayer = innerLayer;
    }

    @Override
    public String toString() {
        return "OnionLayer{" +
                "payload=" + payload +
                //", innerLayer=" + innerLayer +
                '}';
    }
}
