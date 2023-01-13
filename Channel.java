public class Channel {

    private final Node initiator_node;
    private final Node peer_node;
    private final String channel_id;

    private int commit_number = 0;

    private int initiator_fee_ppm;
    private int peer_fee_ppm;
    @SuppressWarnings("FieldMayBeFinal")
    private int initiator_base_fee;
    private int peer_base_fee;
    private int initiator_balance;
    private int peer_balance;
    private int initiator_pending;
    private int peer_pending;
    private int reserve;


    // TODO: update_channel p2p
    // constructor only fill the "proposal" for the channel
    public Channel(Node initiator_node, Node peer_node, int initiator_balance, int peer_balance, String channel_id, int initiator_base_fee, int initiator_fee_ppm, int peer_base_fee,int peer_fee_ppm, int reserve) {
        this.initiator_node = initiator_node;
        this.peer_node = peer_node;
        this.initiator_balance = initiator_balance;
        this.peer_balance = peer_balance;
        this.channel_id = channel_id;
        this.initiator_fee_ppm = initiator_fee_ppm;
        this.peer_fee_ppm = peer_fee_ppm;
        this.initiator_base_fee = initiator_base_fee;
        this.peer_base_fee = peer_base_fee;
        this.initiator_pending = 0;
        this.peer_pending = 0;
        this.commit_number = 0;
    }

    public Node getInitiator_node() {
        return initiator_node;
    }

    public Node getPeer_node() {
        return peer_node;
    }

    public synchronized int getCapacity() {
        return initiator_balance+peer_balance;
    }

    public synchronized int getLastCommit_number() {
        return this.commit_number;
    }

    public synchronized int getInitiator_balance() {
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

    public String getChannel_id() {
        return channel_id;
    }

    public String getPeer_public_key() {
        return peer_node.getPubkey();
    }
    public String getInitiator_public_key() {
        return initiator_node.getPubkey();
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

        return getInitiator_balance()-getReserve()-getInitiator_pending();
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
                " initiator='" + initiator_node.getPubkey() + '\'' +
                ", peer='" + peer_node.getPubkey() + '\'' +
                ", balance=(" + initiator_balance +
                "," + peer_balance +
                "), id=" + channel_id + ", initiator_fee=" + initiator_fee_ppm + ", peer_fee=" + peer_fee_ppm + ", ncommits="+commit_number+'}';
    }
}
