import java.io.Serializable;

public abstract class P2PMessage implements Serializable {
    protected final String ID;
    protected final int forwardings;
    protected final int timestamp;
    protected final Type msgType;

    enum Type { CHANNEL_ANNOUNCE, CHANNEL_UPDATE}

    public int getForwardings() {
        return forwardings;
    }

    public String getID() {
        return ID;
    }

    public Type getType() {
        return msgType;
    }

    public int getTimeStamp() {
        return timestamp;
    }
    abstract public P2PMessage getNext();

    public P2PMessage(String ID, int forwardings, int timestamp, Type msgType) {
        this.ID = ID;
        this.forwardings = forwardings;
        this.timestamp = timestamp;
        this.msgType = msgType;
    }
}
