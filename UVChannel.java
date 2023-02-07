import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

public class UVChannel implements LNChannel, Serializable, Comparable<LNChannel> {

    @Serial
    private static final long serialVersionUID = 120897L;

    enum ChannelStatus { OPEN, CLOSED, PENDING, NONE }

    private ChannelStatus status;

    final private String initiatorPubkey;
    final private String peerPubkey;
    private final String channelId;

    private int commitNumber = 0;

    private Policy node1Policy;
    private Policy node2Policy;
    
    private int initiatorBalance;
    private int peerBalance;
    private int initiatorPending;
    private int peerPending;
    private int reserve;

    // constructor only fill the "proposal" for the channel
    public UVChannel(String channel_id, String initiatorPubkey, String peerPubkey, int fundingSatoshis, int channelReserveSatoshis, int pushMsat) {
        this.initiatorPubkey = initiatorPubkey;
        this.peerPubkey = peerPubkey;
        this.initiatorBalance = fundingSatoshis;
        this.peerBalance = pushMsat;
        this.channelId = channel_id;
        this.initiatorPending = 0;
        this.peerPending = 0;
        this.status = ChannelStatus.NONE;
        this.reserve = channelReserveSatoshis;
    }

    public boolean setPolicy(String node, Policy policy) {
        if (node.equals(initiatorPubkey))  {
            node1Policy = policy;
            return true;
        }
        if (node.equals(peerPubkey)) {
            node2Policy = policy;
            return true;
        }
        return false;
    }


    public String getInitiatorPubkey() {
        return initiatorPubkey;
    }
    public String getPeerPubkey() {
        return peerPubkey;
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
        return this.channelId;
    }

    /**
     * @return 
     */
    @Override
    public String getNode1PubKey() {
        return getInitiatorPubkey();
    }

    /**
     * @return 
     */
    @Override
    public String getNode2PubKey() {
        return getPeerPubkey();
    }

    public synchronized int getCapacity() {
        return initiatorBalance + peerBalance;
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


    public synchronized int getLastCommit_number() {
        return this.commitNumber;
    }

    public synchronized int getInitiatorBalance() {
        return initiatorBalance;
    }
    public synchronized int getPeerBalance() {
        return peerBalance;
    }

    public synchronized int getInitiatorPending() {
        return initiatorPending;
    }

    public synchronized int getPeerPending() {
        return peerPending;
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

    public synchronized  int getInitatorLiquidity() {

        return getInitiatorBalance()-getReserve()- getInitiatorPending();
    }

    /**
     * Evaluate the current liquidity on channel peer side, considering the reserve to be mantained and the pending transactions
     * @return the amount of sats that could actually be received from peer
     */
    public synchronized int getPeerLiquidity() {
        return getPeerBalance()-getReserve()- getPeerPending();
    }

    /**
     * Update the channel balances according to a new commitment
     * @param initiator_balance
     * @param peer_balance
     * @return true if the commitment is valid
     */
    public synchronized boolean newCommitment(int initiator_balance, int peer_balance) {

        if (initiator_balance+peer_balance != this.getCapacity())
            return false;

        this.initiatorBalance = initiator_balance;
        this.peerBalance = peer_balance;
        this.commitNumber = getLastCommit_number()+1;
        return true;
    }

    @Override
    public String toString() {
        return "UVChannel{" +
                " Id:'" + channelId + '\'' +
                ", status:" + status +
                ", init:'" + initiatorPubkey + '\'' +
                ", peer:'" + peerPubkey + '\'' +
                ", init_sat=" + initiatorBalance +
                ", peer_sat=" + peerBalance +
                ", commit:" + commitNumber +
                ", n1:" + node1Policy +
                ", n2:" + node2Policy +
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
        return channelId.equals(uvChannel.channelId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(channelId);
    }
}
