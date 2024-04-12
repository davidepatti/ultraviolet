import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

@SuppressWarnings("CanBeFinal")
public class UVChannel implements LNChannel, Serializable, Comparable<LNChannel> {

    @Serial
    private static final long serialVersionUID = 120897L;
    private int htlc_id =0;
    private final int reserve;
    private final boolean init_direction; // true -> from 1 to 2
    private int commitNumber = 0;
    private final String channel_id;

    private class NodeData implements Serializable{
        final private String pubkey;
        private Policy policy;
        private int balance;
        private int pending;
        // Constructor
        public NodeData(String pubkey, Policy policy, int balance, int pending) {
            this.pubkey = pubkey;
            this.policy = policy;
            this.balance = balance;
            this.pending = pending;
        }

        public void setPolicy(Policy policy) {
            this.policy = policy;
        }

        public void setBalance(int balance) {
            this.balance = balance;
        }

        public void setPending(int pending) {
            this.pending = pending;
        }
    }

    private final NodeData node1,node2;

    public UVChannel(String channel_id, String pub1, String pub2, int fundingSatoshis, int channelReserveSatoshis, int pushMsat, boolean init_direction) {

        this.channel_id = channel_id;
        this.init_direction = init_direction;
        this.reserve = channelReserveSatoshis;

        if (init_direction) {
            node1 = new NodeData(pub1,null,fundingSatoshis,0);
            node2 = new NodeData(pub2,null,pushMsat,0);
        }
        else {
            node1 = new NodeData(pub1,null,pushMsat,0);
            node2 = new NodeData(pub2,null,fundingSatoshis,0);
        }

    }
    // constructor only fill the "proposal" for the channel
    public UVChannel(String initiator, String peer, int fundingSatoshis, int channelReserveSatoshis, int pushMsat) {
        if (initiator.compareTo(peer)<0) {
            node1 = new NodeData(initiator,null,fundingSatoshis,0);
            node2 = new NodeData(peer,null,pushMsat,0);
            init_direction = true;
        }
        else {
            node2 = new NodeData(initiator,null,fundingSatoshis,0);
            node1 = new NodeData(peer,null,pushMsat,0);
            init_direction = false;
        }
        this.channel_id = "ch_"+node1.pubkey +"-"+node2.pubkey;
        this.reserve = channelReserveSatoshis;
    }

    public String getChannel_id() {
        return channel_id;
    }

    private NodeData resolveNode(String pubkey) {
        if (node1.pubkey.equals(pubkey)) return node1;
        if (node2.pubkey.equals(pubkey)) return node2;

        throw new IllegalArgumentException("Not existing node:"+pubkey);
    }

    public synchronized boolean reservePending(String pubkey, int amt) {
        var node = resolveNode(pubkey);
        if (amt>getLiquidity(pubkey)) return false;
        node.pending+=amt;
        return true;
    }

    public synchronized void removePending(String pubkey, int amt) {
        var node = resolveNode(pubkey);
        node.pending -=amt;
    }

    public void setPolicy(String pubkey, Policy policy) {
        var node = resolveNode(pubkey);
        node.setPolicy(policy);
    }

    @Override
    public String getId() {
        return this.channel_id;
    }

    @Override
    public String getNode1PubKey() {
        return node1.pubkey;
    }

    @Override
    public String getNode2PubKey() {
        return node2.pubkey;
    }

    public String getInitiator(){
        if (init_direction)
            return node1.pubkey;
        else return node2.pubkey;
    }


    public synchronized int getCapacity() {
        return node1.balance + node2.balance;
    }

    public synchronized int getBalance(String node_id) {
        var node = resolveNode(node_id);
        return node.balance;
    }


    public Policy getPolicy(String pubkey) {
        var node = resolveNode(pubkey);
        //Objects.requireNonNull(node.policy);
        return node.policy;
    }


    public synchronized int getLastCommitNumber() {
        return this.commitNumber;
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

    /**
     * Evaluate the current liquidity on channel peer side, considering the reserve to be mantained and the pending transactions
     * @return the amount of sats that could actually be received from peer
     */
    public synchronized int getLiquidity(String pubkey) {
        var node = resolveNode(pubkey);
        return node.balance - getReserve() - node.pending;
    }

    public synchronized int getNextHTLCid() {
        this.htlc_id++;
        return htlc_id;
    }

    // TODO: check when used, must account for reserved/pending liquidity ?
    public synchronized void newCommitment(int node1Balance, int node2Balance) {

        if (node1Balance+node2Balance != this.getCapacity()) {
            throw new IllegalArgumentException("Ch["+this.getId()+"] Wrong balances in commitment:"+node1Balance+"+"+node2Balance+"!= "+getCapacity());
        }
        node1.balance = node1Balance;
        node2.balance = node2Balance;
        this.commitNumber = getLastCommitNumber()+1;
    }

    @Override
    public String toString() {

        StringBuilder p1 = new StringBuilder("(");
        p1.append(channel_id).append(") ").append("n1:").append(node1.pubkey);
        p1.append(" n2:").append(node2.pubkey).append(", [").append(node1.balance);

        if (node1.pending!=0) p1.append("(pending ").append(node1.pending).append(")");

        p1.append(",").append(node2.balance);
        if (node2.pending!=0) p1.append("(pending ").append(node2.pending).append(")");

        p1.append("]").append(node1.policy).append(node2.policy);
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
