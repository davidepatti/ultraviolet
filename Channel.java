public class Channel {

    private final int capacity;
    private int local_balance;
    private int remote_balance;
    private final String channel_id;
    private final String initiator_public_key;
    private final String peer_public_key;

    private int local_fee;
    private int remote_fee;

    private boolean initiator;
    private boolean active;

    // constructor only fill the "proposal" for the channel
    public Channel(int capacity, int local_balance, int remote_balance, String channel_id, String initiator_public_key, String peer_public_key, int local_fee, int remote_fee, boolean initiator, boolean active) {
        this.capacity = capacity;
        this.local_balance = local_balance;
        this.remote_balance = remote_balance;
        this.channel_id = channel_id;
        this.initiator_public_key = initiator_public_key;
        this.peer_public_key = peer_public_key;
        this.local_fee = local_fee;
        this.remote_fee = remote_fee;
        this.initiator = initiator;
        this.active = active;
    }


    public int getCapacity() {
        return capacity;
    }

    public int getLocal_balance() {
        return local_balance;
    }

    public void setLocal_balance(int local_balance) {
        this.local_balance = local_balance;
    }

    public int getRemote_balance() {
        return remote_balance;
    }

    public void setRemote_balance(int remote_balance) {
        this.remote_balance = remote_balance;
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

    public int getLocal_fee() {
        return local_fee;
    }

    public void setLocal_fee(int local_fee) {
        this.local_fee = local_fee;
    }

    public int getRemote_fee() {
        return remote_fee;
    }

    public void setRemote_fee(int remote_fee) {
        this.remote_fee = remote_fee;
    }

    public boolean isInitiator() {
        return initiator;
    }

    public void setInitiator(boolean initiator) {
        this.initiator = initiator;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public String toString() {
        return "Ch{" +
                " initpubkey='" + initiator_public_key + '\'' +
                ", peer='" + peer_public_key + '\'' +
                ", capacity=" + capacity +
                ", local=" + local_balance +
                ", remote=" + remote_balance +
                ", id=" + channel_id +
                ", initiator=" + initiator +
                ", local_fee=" + local_fee +
                ", remote_fee=" + remote_fee +
                '}';
    }
}
