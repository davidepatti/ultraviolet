package network;

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

    public static class NodeData implements Serializable{
        @Serial
        private static final long serialVersionUID = -2966204623782850872L;
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

        public synchronized void setPolicy(Policy policy) {
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
    private int capacity;

    public UVChannel(String channel_id, String pub1, String pub2, int fundingSatoshis, int channelReserveSatoshis, int pushMsat, boolean init_direction) {

        // node1 and node2 field are assumed according lexographical order
        if (pub1.compareTo(pub2)>=0) {
            System.out.println("FATAL: wrong lexographical order node1, node2 = "+pub1+","+pub2);
            System.exit(-1);
        }

        if (fundingSatoshis <= 0 || channelReserveSatoshis < 0 || pushMsat < 0 || pushMsat > fundingSatoshis) {
            throw new IllegalArgumentException("Invalid channel funding/reserve");
        }
        this.capacity = fundingSatoshis;
        this.channel_id = channel_id;
        this.init_direction = init_direction;
        this.reserve = channelReserveSatoshis;

        if (init_direction) {
            node1 = new NodeData(pub1, null, fundingSatoshis-pushMsat, 0);
            node2 = new NodeData(pub2, null, pushMsat, 0);
        }
        else {
            node1 = new NodeData(pub1, null, pushMsat, 0);
            node2 = new NodeData(pub2, null, fundingSatoshis-pushMsat, 0);
        }

    }
    // constructor only fill the "proposal" for the channel
    // node1 and node2 field are arranged according lexographical order
    public static UVChannel buildFromProposal(String initiator, String peer, int fundingSatoshis, int channelReserveSatoshis, int pushMsat) {
        String node1,node2;
        boolean init_direction;

        if (initiator.compareTo(peer)<0) {
            node1 = initiator;
            node2 = peer;
            init_direction = true;
        }
        else {
            node1 = peer;
            node2 = initiator;
            init_direction = false;
        }
        String channel_id = "ch_"+initiator +"_"+peer;
        return new UVChannel(channel_id,node1,node2,fundingSatoshis,channelReserveSatoshis,pushMsat,init_direction);
    }

    public String getChannelId() {
        return channel_id;
    }

    public NodeData resolveNode(String pubkey) {
        if (node1.pubkey.equals(pubkey)) return node1;
        if (node2.pubkey.equals(pubkey)) return node2;

        throw new IllegalArgumentException("Not existing node:"+pubkey);
    }

    public synchronized boolean reservePending(String pubkey, int amt) {
        var node = resolveNode(pubkey);
        if (amt < 0) throw new IllegalArgumentException("Negative reservation");
        if (amt>getLiquidity(pubkey)) return false;
        node.pending+=amt;
        return true;
    }

    public synchronized void removePending(String pubkey, int amt) {
        var node = resolveNode(pubkey);
        if (amt < 0 || amt > node.pending) throw new IllegalStateException("Invalid reservation release");
        node.pending -=amt;
    }

    public synchronized void setPolicy(String pubkey, Policy policy) {
        var node = resolveNode(pubkey);
        node.setPolicy(policy);
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
    public String getNonInitiator(){
        if (init_direction)
            return node2.pubkey;
        else return node1.pubkey;
    }


    public synchronized int getCapacity() {
        return capacity;
    }

    public synchronized int getBalance(String node_id) {
        var node = resolveNode(node_id);
        return node.balance;
    }
    /**
     * Evaluate the current liquidity on channel peer side, considering the reserve to be mantained and the pending transactions
     * @return the amount of sats that could actually be received from peer
     */
    public synchronized int getLiquidity(String pubkey) {
        var node = resolveNode(pubkey);
        return node.balance - getReserve() - node.pending;
    }


    public synchronized Policy getPolicy(String pubkey) {
        var node = resolveNode(pubkey);
        //Objects.requireNonNull(node.policy);
        return node.policy;
    }

    public synchronized int getLastCommitNumber() {
        return this.commitNumber;
    }

    public synchronized int getPending(String pubkey) {
        return resolveNode(pubkey).pending;
    }

    public synchronized int getReserve() {
        return reserve;
    }

    public synchronized int getNextHTLCid() {
        this.htlc_id++;
        return htlc_id;
    }

    // TODO: check when used, must account for reserved/pending liquidity ?
    public synchronized void newCommitment(int node1Balance, int node2Balance) {

        if (node1Balance < 0 || node2Balance < 0 || (long) node1Balance + node2Balance != this.getCapacity()) {
            throw new IllegalArgumentException("Channel "+this.getChannelId()+": Wrong balances in commitment:"+node1Balance+"+"+node2Balance+"!= "+getCapacity());
        }
        node1.balance = node1Balance;
        node2.balance = node2Balance;
        this.commitNumber = getLastCommitNumber()+1;
    }
    @java.io.Serial
    private void readObject(java.io.ObjectInputStream input) throws java.io.IOException, ClassNotFoundException {
        input.defaultReadObject();
        // Pre-consistency snapshots stored capacity only as the sum of endpoint balances.
        long sum = (long) node1.balance + node2.balance;
        if (capacity == 0 && sum > 0 && sum <= Integer.MAX_VALUE) capacity = (int) sum;
        if (capacity <= 0 || node1.balance < 0 || node2.balance < 0 || sum != capacity) {
            throw new java.io.InvalidObjectException("Invalid saved channel capacity");
        }
    }

    public static String generateLabels() {
        final String labelFormat = "%-15s %-6s %-6s %-20s %-12s %-12s";

        return String.format(labelFormat, "Channel ID", "Node 1", "Node 2",
                "balances", "n1 base/ppm", "n2 base/ppm");
    }

    @Override
    public String toString() {
        StringBuilder s =  new StringBuilder(String.format("%-15s %-6s %-6s %-10d %-10d %-12s %-12s", channel_id, node1.pubkey, node2.pubkey, node1.balance, node2.balance,node1.policy, node2.policy));

        if (node1.pending!=0) s.append(" (pending n1:").append(node1.pending).append(")");
        if (node2.pending!=0) s.append(" (pending n2:").append(node2.pending).append(")");

        return s.toString();
    }

    /**
     * @param channel
     * @return
     */
    public int compareTo(LNChannel channel) {
        return this.getChannelId().compareTo(channel.getChannelId());
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
