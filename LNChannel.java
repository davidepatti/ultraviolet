import java.io.Serializable;

public interface LNChannel {
    record Policy(int cltv,int base_fee, int fee_ppm) implements Serializable {
        @Override
        public String toString() {
            StringBuilder s = new StringBuilder("(");
            s.append(cltv).append("/").append(base_fee).append("/").append(fee_ppm).append(")");
            return s.toString();
        }
    }

    String getId();
    String getNode1PubKey();
    String getNode2PubKey();
    int getCapacity();

    Policy getNode1Policy();
    Policy getNode2Policy();
    Policy getPolicy(String pubkey);

}
