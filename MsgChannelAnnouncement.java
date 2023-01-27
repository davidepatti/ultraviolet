import java.lang.reflect.Member;

public class MsgChannelAnnouncement implements P2PMessage{
    String ID;
    private final LNChannel channel;
    private final int forwardings;
    private final Type type = Type.CHANNEL_ANNOUNCE;
    private final int timestamp;
    MsgChannelAnnouncement next;
    
    

    public MsgChannelAnnouncement(LNChannel channel,int timestamp) {
        this.ID = "ANN_"+channel.getId();
        this.channel = channel;
        this.timestamp = timestamp;
        this.forwardings = 0;
    }
    private MsgChannelAnnouncement(LNChannel channel,int timestamp, int forwardings) {
        this.ID = "ANN_"+channel.getId();
        this.channel = channel;
        this.timestamp = timestamp;
        this.forwardings = forwardings;
    }

    public Object getData() {
        return channel;
    }

    public synchronized MsgChannelAnnouncement getNext() {
        if (next==null) {
            next = new MsgChannelAnnouncement(this.channel,this.timestamp,this.forwardings+1);
        }
        return next;
    }

    public int getForwardings() {
        return forwardings;
    }

    /**
     * @return 
     */
    @Override
    public String getID() {
        return ID;
    }

    /**
     * @return 
     */
    @Override
    public Type getType() {
        return type;
    }

    /**
     * @return
     */
    @Override
    public int getTimeStamp() {
        return timestamp;
    }


}
