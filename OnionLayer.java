import java.util.Optional;

public class OnionLayer {

    final Payload payload;
    final OnionLayer innerLayer;

    public static class Payload {
        final String short_channel_id;
        final int amt_to_forward;
        final int outgoing_cltv_value;
        final Optional<String> payment_secret;

        public Payload(String short_channel_id, int amt_to_forward, int outgoing_cltv_value, Optional<String> payment_secret) {
            this.short_channel_id = short_channel_id;
            this.amt_to_forward = amt_to_forward;
            this.outgoing_cltv_value = outgoing_cltv_value;
            this.payment_secret = payment_secret;
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

    public OnionLayer(Payload payload, OnionLayer innerLayer) {
        this.payload = payload;
        this.innerLayer = innerLayer;
    }

    @Override
    public String toString() {
        return "OnionLayer{" +
                "payload=" + payload +
                ", innerLayer=" + innerLayer +
                '}';
    }
}
