import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

@SuppressWarnings("CanBeFinal")
public class UVChannel implements LNChannel, Serializable, Comparable<LNChannel> {

    private void log(String s) {
        UVNetworkManager.log(this.getId()+":"+s);
    }
    @Serial
    private static final long serialVersionUID = 120897L;
    private int htlc_id =0;

    enum ChannelStatus { OPEN, CLOSED, PENDING, NONE }

    private ChannelStatus status;

    final private String node_id_1;
    final private String node_id_2;
    private final String channel_id;

    private int commitNumber = 0;

    private Policy node1Policy;
    private Policy node2Policy;
    
    private int node1Balance;
    private int node2Balance;
    private int node1Pending;
    private int node2Pending;
    private final int reserve;
    private final boolean init_direction; // true -> from 1 to 2


    public UVChannel(String pub1, String pub2, String id, Policy p1, Policy p2, int balance1, int balance2, int reserve, boolean dir) {

        this.node_id_1 = pub1;
        this.node_id_2 = pub2;
        this.channel_id = id;
        this.node1Balance = balance1;
        this.node2Balance = balance2;
        this.reserve = 0;
        this.init_direction = dir;

    }
    // constructor only fill the "proposal" for the channel
    public UVChannel(String channel_id, String initiator, String peer, int fundingSatoshis, int channelReserveSatoshis, int pushMsat) {
        if (initiator.compareTo(peer)<0) {
            node_id_1 = initiator;
            node_id_2 = peer;
            this.node1Balance = fundingSatoshis;
            this.node2Balance = pushMsat;
            init_direction = true;
        }
        else {
            node_id_2 = initiator;
            node_id_1 = peer;
            this.node2Balance = fundingSatoshis;
            this.node1Balance = pushMsat;
            init_direction = false;
        }

        this.channel_id = channel_id;
        this.node1Pending = 0;
        this.node2Pending = 0;
        this.status = ChannelStatus.NONE;
        this.reserve = 0;
    }


    public synchronized boolean reservePending(String node, int amt) {

        log("Checking liquidity for node "+node+ " , required: "+amt+ " in channel "+this);
        if (node.equals(node_id_1))  {
            if (amt>getNode1Liquidity()) {
                return false;
            }
            log("Increasing pending from "+node1Pending+" to "+(node1Pending+amt));
            node1Pending += amt;
            return true;
        }
        else
        if (node.equals(node_id_2)) {
            if (amt>getNode2Liquidity()) {
               return false;
            }
            log("Increasing pending from "+node1Pending+" to "+(node1Pending+amt));
            node2Pending +=amt;
            return true;
        }
        else {
            throw new IllegalArgumentException("Wrong node "+node);
        }
    }
    public synchronized void removePending(String node, int amt) {

        if (node.equals(this.getNode1PubKey())) {
            node1Pending+=amt;
        }
        else if (node.equals(this.getNode2PubKey())) {
            node2Pending+=amt;
        }
        else

        throw new IllegalArgumentException(" Unkown node "+node);

    }

    public void setPolicy(String node, Policy policy) {
        if (node.equals(node_id_1))  {
            node1Policy = policy;
        }
        else
        if (node.equals(node_id_2)) {
            node2Policy = policy;
        }
        else {
            throw new IllegalArgumentException("Wrong node "+node);
        }
    }


    public void setStatus(ChannelStatus status) {
        this.status = status;
    }
    public ChannelStatus getStatus() {
        return status;
    }

    /**
     * @return 
     */
    @Override
    public String getId() {
        return this.channel_id;
    }

    /**
     * @return 
     */
    @Override
    public String getNode1PubKey() {
        return node_id_1;
    }

    /**
     * @return 
     */
    @Override
    public String getNode2PubKey() {
        return node_id_2;
    }

    public String getInitiator(){
        if (init_direction)
            return node_id_1;
        else return node_id_2;
    }


    public synchronized int getCapacity() {
        return node1Balance + node2Balance;
    }

    /**
     * @return 
     */

    /**
     * @return 
     */
    @Override
    public Policy getNode1Policy() {
        return node1Policy;
    }

    /**
     * @return 
     */
    @Override
    public Policy getNode2Policy() {
        return node2Policy;
    }

    public Policy getPolicy(String pubkey) {
        Policy p;
        if (pubkey.equals(node_id_1))
            p = getNode1Policy();
        else
        if (pubkey.equals(node_id_2))
            p = getNode2Policy();
        else {
            throw new IllegalArgumentException("WRONG pubkey "+pubkey);
        }

        Objects.requireNonNull(p);

        return p;
    }


    public synchronized int getLastCommitNumber() {
        return this.commitNumber;
    }
    public synchronized int getNextCommitNumber() {
        this.commitNumber = getLastCommitNumber()+1;
        return commitNumber;
    }

    public synchronized int getNode1Balance() {
        return node1Balance;
    }
    public synchronized int getNode2Balance() {
        return node2Balance;
    }

    public synchronized int getNode1Pending() {
        return node1Pending;
    }

    public synchronized int getNode2Pending() {
        return node2Pending;
    }


    public synchronized int getReserve() {
        return reserve;
    }

    public synchronized int getMinLiquidity() {
        return getReserve();
    }

    public synchronized int getMaxLiquidity() {
        return getCapacity()-getReserve();
    }

    public synchronized  int getNode1Liquidity() {

        return getNode1Balance()-getReserve()- getNode1Pending();
    }
    public synchronized  int getNode2Liquidity() {

        return getNode2Balance()-getReserve()- getNode2Pending();
    }

    /**
     * Evaluate the current liquidity on channel peer side, considering the reserve to be mantained and the pending transactions
     * @return the amount of sats that could actually be received from peer
     */
    public synchronized int getLiquidity(String pubkey) {
        if (pubkey.equals(node_id_1))
            return getNode1Liquidity();
        else
        if (pubkey.equals(node_id_2))
            return getNode2Liquidity();
        else {
            throw new IllegalArgumentException("WRONG pubkey "+pubkey);
        }
    }

    public synchronized int increaseHTLCId() {

        this.htlc_id++;
        return htlc_id;
    }

    public int getCommitNumber() {
        return commitNumber;
    }

    public synchronized void newCommitment(int node1Balance, int node2Balance) {

        if (node1Balance+node2Balance != this.getCapacity()) {
            throw new IllegalArgumentException("Ch["+this.getId()+"] Wrong balances in commitment:"+node1Balance+"+"+node2Balance+"!= "+getCapacity());
        }

        this.node1Balance = node1Balance;
        this.node2Balance = node2Balance;
        this.commitNumber = getLastCommitNumber()+1;
    }

    @Override
    public String toString() {

        StringBuilder p1 = new StringBuilder("(");
        p1.append(channel_id).append(") ").append("n1:").append(node_id_1);
        p1.append(" n2:").append(node_id_2).append(", [").append(node1Balance);
        if (this.getNode1Pending()!=0) p1.append("(pending ").append(getNode1Pending()).append(")");
        p1.append(",").append(node2Balance);
        if (this.getNode2Pending()!=0) p1.append("(pending ").append(getNode2Pending()).append(")");
        p1.append("]").append(node1Policy).append(node2Policy);
        p1.append(", commit:").append(commitNumber);

        return p1.toString();
    }

    /**
     * @param channel
     * @return
     */
    public int compareTo(LNChannel channel) {
        return this.getId().compareTo(channel.getId());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        UVChannel uvChannel = (UVChannel) o;
        return channel_id.equals(uvChannel.channel_id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channel_id);
    }
}
