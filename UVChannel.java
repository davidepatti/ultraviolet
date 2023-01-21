import java.io.Serializable;

public class UVChannel implements LNChannel, Serializable {

    enum ChannelStatus { OPEN, CLOSED, PENDING, NONE }

    private ChannelStatus status;

    // TODO: made public to restore on load, fix later calling constructor
    // CAN THIS BE LNODE?
    transient public UVNode initiatorNode;
    transient public UVNode channelPeerNode;

    final private String initiator_pubkey;
    final private String peer_pubkey;
    private final String channel_id;

    private int commit_number = 0;

    private int initiator_fee_ppm;
    private int peer_fee_ppm;
    @SuppressWarnings("FieldMayBeFinal")
    private int initiator_base_fee;
    @SuppressWarnings("FieldMayBeFinal")
    private int peer_base_fee;
    private int initiator_locktimedelta;
    private int peer_locktimedelta;
    private int initiator_balance;
    private int peer_balance;
    @SuppressWarnings("FieldMayBeFinal")
    private int initiator_pending;
    @SuppressWarnings("FieldMayBeFinal")
    private int peer_pending;
    private int reserve;

    // TODO: update_channel p2p
    // constructor only fill the "proposal" for the channel
    public UVChannel(UVNode initiatorNode, UVNode channelPeerNode, int initiator_balance, int peer_balance, String channel_id, int initiator_base_fee, int initiator_fee_ppm, int peer_base_fee, int peer_fee_ppm, int reserve) {
        this.initiatorNode = initiatorNode;
        this.channelPeerNode = channelPeerNode;
        this.initiator_balance = initiator_balance;
        this.peer_balance = peer_balance;
        this.channel_id = channel_id;
        this.initiator_fee_ppm = initiator_fee_ppm;
        this.peer_fee_ppm = peer_fee_ppm;
        this.initiator_base_fee = initiator_base_fee;
        this.peer_base_fee = peer_base_fee;
        this.initiator_pending = 0;
        this.peer_pending = 0;
        this.status = ChannelStatus.NONE;
        this.initiator_pubkey = initiatorNode.getPubKey();
        this.peer_pubkey = channelPeerNode.getPubKey();
    }

    public String getPeerPubKey() {
        return peer_pubkey;
    }

    public String getInitiatorPubKey() {
        return initiator_pubkey;
    }

    public UVNode getInitiator() {
        return initiatorNode;
    }

    public UVNode getChannelPeer() {
        return channelPeerNode;
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
        return getInitiatorPubKey();
    }

    /**
     * @return 
     */
    @Override
    public String getNode2PubKey() {
        return getPeerPubKey();
    }

    /**
     * @return 
     */
    @Override
    public LNode getNode1() {
        return initiatorNode;
    }

    /**
     * @return 
     */
    @Override
    public LNode getNode2() {
        return channelPeerNode;
    }

    public synchronized int getCapacity() {
        return initiator_balance+peer_balance;
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
    public int getNode1TimeLockDelta() {
        return initiator_locktimedelta;
    }

    /**
     * @return 
     */
    @Override
    public int getNode2TimeLockDelta() {
        return peer_locktimedelta;
    }

    /**
     * @return 
     */
    @Override
    public int getNode1FeeBase() {
        return initiator_base_fee;
    }

    /**
     * @return 
     */
    @Override
    public int getNode1FeePpm() {
        return initiator_fee_ppm;
    }

    /**
     * @return 
     */
    @Override
    public int getNode2FeeBase() {
        return peer_base_fee;
    }

    /**
     * @return 
     */
    @Override
    public int getNode2FeePpm() {
        return peer_fee_ppm;
    }

    public synchronized int getLastCommit_number() {
        return this.commit_number;
    }

    public synchronized int getInitiatorBalance() {
        return initiator_balance;
    }
    public synchronized int getPeer_balance() {
        return peer_balance;
    }

    public synchronized int getInitiator_pending() {
        return initiator_pending;
    }

    public synchronized int getPeer_pending() {
        return peer_pending;
    }


    public synchronized int getInitiator_fee_ppm() {
        return initiator_fee_ppm;
    }
    public synchronized void setInitiator_fee_ppm(int initiator_fee_ppm) {
        this.initiator_fee_ppm = initiator_fee_ppm;
    }

    public synchronized int getPeer_fee_ppm() {
        return peer_fee_ppm;
    }
    public synchronized void setPeer_fee_ppm(int peer_fee_ppm) {
        this.peer_fee_ppm = peer_fee_ppm;
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

        return getInitiatorBalance()-getReserve()-getInitiator_pending();
    }

    /**
     * Evaluate the current liquidity on channel peer side, considering the reserve to be mantained and the pending transactions
     * @return the amount of sats that could actually be received from peer
     */
    public synchronized int getPeerLiquidity() {
        return getPeer_balance()-getReserve()-getPeer_pending();
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

        this.initiator_balance = initiator_balance;
        this.peer_balance = peer_balance;
        this.commit_number = getLastCommit_number()+1;
        return true;
    }


    @Override
    public String toString() {
        return "Ch{" +
                " status:"+this.status.toString()+
                " initiator:'" + initiatorNode.getPubKey() + '\'' +
                ", peer:" + channelPeerNode.getPubKey() + '\'' +
                ", balance:(" + initiator_balance +
                "," + peer_balance +
                "), id:" + channel_id + ", initiator_fee:" + initiator_fee_ppm + ", peer_fee:" + peer_fee_ppm + ", ncommits:"+commit_number+'}';
    }
}
