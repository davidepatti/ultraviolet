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
        public Payload(String short_channel_id, int amt_to_forward, int outgoing_cltv_value) {
            this(short_channel_id,amt_to_forward,outgoing_cltv_value,null);
        }

        public int getAmt_to_forward() {
            return amt_to_forward;
        }

        public String getShort_channel_id() {
            return short_channel_id;
        }

        public Optional<String> getPayment_secret() {
            return Optional.ofNullable(payment_secret);
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

    public Optional<OnionLayer> getInnerLayer() {
        return Optional.ofNullable(innerLayer);
    }

    public OnionLayer(Payload payload, OnionLayer layer) {
        this.payload = payload;
        this.innerLayer = layer;
    }

    @Override
    public String toString() {
        return "OnionLayer{" +
                "payload=" + payload +
                //", innerLayer=" + innerLayer +
                '}';
    }
}
