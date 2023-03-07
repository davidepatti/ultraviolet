import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

@SuppressWarnings("CanBeFinal")
public class UVChannel implements LNChannel, Serializable, Comparable<LNChannel> {

    @Serial
    private static final long serialVersionUID = 120897L;

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
    @SuppressWarnings("FieldMayBeFinal")
    private int node1Pending;
    @SuppressWarnings("FieldMayBeFinal")
    private int node2Pending;
    private final int reserve;
    private final boolean init_direction; // true -> from 1 to 2

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
        this.reserve = channelReserveSatoshis;
    }

    public void setPolicy(String node, Policy policy) {
        if (node.equals(node_id_1))  {
            node1Policy = policy;
            return;
        }
        if (node.equals(node_id_2)) {
            node2Policy = policy;
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
    @Override
    public int getLastUpdate() {
        return 0;
    }

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
        if (pubkey.equals(node_id_1))
            return getNode1Policy();
        if (pubkey.equals(node_id_2))
            return getNode2Policy();

        return null;
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
    public synchronized int getPeerLiquidity() {
        return getNode2Balance()-getReserve()- getNode2Pending();
    }

    public synchronized boolean newCommitment(int node1Balance, int node2Balance) {

        if (node1Balance+node2Balance != this.getCapacity())
            throw new IllegalArgumentException(" Wrong balances in commitment!");

        this.node1Balance = node1Balance;
        this.node2Balance = node2Balance;
        this.commitNumber = getLastCommitNumber()+1;
        return true;
    }

    @Override
    public String toString() {
        return "Ch{" +
                " Id:'" + channel_id + '\'' +
                ", n1:" + node_id_1 + '\'' +
                ", n2:" + node_id_2 + '\'' +
                ", [" + node1Balance + "," + node2Balance +"]"+
                ", "+ node1Policy +
                ", " + node2Policy +
                ", ups:" + commitNumber +
                '}';
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
