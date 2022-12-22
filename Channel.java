public class Channel {

    public final int capacity;
    private final String channel_id;
    private final String channel_point;
    private int local_balance;
    private int remote_balance;

    private boolean initiator;
    private boolean active;
    // TODO: add other attribute if required


    public Channel(int capacity, String channel_id, String channel_point) {
        this.capacity = capacity;
        this.channel_id = channel_id;
        this.channel_point = channel_point;
    }
}
