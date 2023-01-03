public class Channel {

    private int initiator_balance;
    private int peer_balance;
    private final String channel_id;
    private final String initiator_public_key;
    private final String peer_public_key;

    private int commit_number = 0;

    private int initiator_fee;
    private int peer_fee;

    @SuppressWarnings("FieldMayBeFinal")
    private int state_hint = 0;

    // constructor only fill the "proposal" for the channel
    public Channel(int initiator_balance, int peer_balance, String channel_id, String initiator_public_key, String peer_public_key, int initiator_fee, int peer_fee) {
        this.initiator_balance = initiator_balance;
        this.peer_balance = peer_balance;
        this.channel_id = channel_id;
        this.initiator_public_key = initiator_public_key;
        this.peer_public_key = peer_public_key;
        this.initiator_fee = initiator_fee;
        this.peer_fee = peer_fee;
    }

    public synchronized int getState_hint() {
        return state_hint;
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

    public String getChannel_id() {
        return channel_id;
    }

    public String getPeer_public_key() {
        return peer_public_key;
    }
    public String getInitiator_public_key() {
        return initiator_public_key;
    }

    public int getInitiator_fee() {
        return initiator_fee;
    }
    public void setInitiator_fee(int initiator_fee) {
        this.initiator_fee = initiator_fee;
    }

    public int getPeer_fee() {
        return peer_fee;
    }

    public void setPeer_fee(int peer_fee) {
        this.peer_fee = peer_fee;
    }

    public synchronized boolean updateChannel(int initiator_balance,int peer_balance) {

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
                " initiator='" + initiator_public_key + '\'' +
                ", peer='" + peer_public_key + '\'' +
                ", balance=(" + initiator_balance +
                "," + peer_balance +
                "), id=" + channel_id + ", initiator_fee=" + initiator_fee + ", peer_fee=" + peer_fee + ", ncommits="+commit_number+'}';
    }
}
