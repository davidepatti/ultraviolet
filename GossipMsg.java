import java.io.Serializable;

public abstract class GossipMsg extends P2PMessage implements Serializable {
    private final String senderID;
    protected final int forwardings;
    protected final int timestamp;

    public int getForwardings() {
        return forwardings;
    }

    public String getSender() {
        return senderID;
    }

    public int getTimeStamp() {
        return timestamp;
    }
    abstract public GossipMsg nextMsgToForward(String sender);



    public GossipMsg(String fromID, int forwardings, int timestamp, Type msgType) {
        super(msgType);
        this.senderID = fromID;
        this.forwardings = forwardings;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "Gossip{" + "from='" + senderID + '\'' + ", fwds=" + forwardings + ", ts=" + timestamp + "," + msgType + '}';
    }
}
