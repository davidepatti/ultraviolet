import java.io.Serializable;

public interface LNChannel {
    class Policy implements Serializable {

        final int cltv;
        final int base_fee;
        final int fee_ppm;

        public Policy(int cltv, int base_fee, int fee_ppm) {
            this.cltv = cltv;
            this.base_fee = base_fee;
            this.fee_ppm = fee_ppm;
        }

        public int getCLTV() {
            return cltv;
        }

        public int getBaseFee() {
            return base_fee;
        }

        public int getFeePpm() {
            return fee_ppm;
        }

        @Override
        public String toString() {
            return String.format("%-5d %-5d", base_fee, fee_ppm);
        }
    }

    String getId();
    String getNode1PubKey();
    String getNode2PubKey();
    int getCapacity();

    Policy getPolicy(String pubkey);

}
