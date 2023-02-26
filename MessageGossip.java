import java.io.Serializable;

public abstract class MessageGossip extends Message implements Serializable {
    protected final String ID;
    protected final int forwardings;
    protected final int timestamp;

    public int getForwardings() {
        return forwardings;
    }


    public int getTimeStamp() {
        return timestamp;
    }
    abstract public MessageGossip getNext();

    public MessageGossip(String ID, int forwardings, int timestamp, Type msgType) {
        super(msgType);
        this.ID = ID;
        this.forwardings = forwardings;
        this.timestamp = timestamp;
    }

    @Override
    public String toString() {
        return "P2PMessage{" +
                "ID='" + ID + '\'' +
                ", forwardings=" + forwardings +
                ", timestamp=" + timestamp +
                ", msgType=" + msgType +
                '}';
    }
}
