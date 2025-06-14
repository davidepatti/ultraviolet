import java.io.Serializable;

public interface LNChannel {
    class Policy implements Serializable {

        final int cltv_delta;
        final int base_fee_msat;
        final int fee_ppm;

        public Policy(int cltv_delta, int base_fee_msat, int fee_ppm) {
            this.cltv_delta = cltv_delta;
            this.base_fee_msat = base_fee_msat;
            this.fee_ppm = fee_ppm;
        }

        public int getCLTVDelta() {
            return cltv_delta;
        }

        public int getBaseFee() {
            return base_fee_msat;
        }

        public int getFeePpm() {
            return fee_ppm;
        }

        @Override
        public String toString() {
            return String.format("%-5d %-5d", base_fee_msat, fee_ppm);
        }
    }

    String getChannelId();
    String getNode1PubKey();
    String getNode2PubKey();
    int getCapacity();

    Policy getPolicy(String pubkey);

}
