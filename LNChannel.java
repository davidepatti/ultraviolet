import java.io.Serializable;

public interface LNChannel {
    record Policy(int cltv,int base_fee, int fee_ppm) implements Serializable {
        @Override
        public String toString() {
            return "{" + "cltv:" + cltv + ", base:" + base_fee + ", ppm:" + fee_ppm + '}';
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
