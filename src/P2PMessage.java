import java.io.Serializable;

public abstract class P2PMessage implements Serializable {
    protected final Type msgType;
    enum Type { OPEN_CHANNEL, ACCEPT_CHANNEL, CHANNEL_ANNOUNCE, CHANNEL_UPDATE, UPDATE_ADD_HTLC,UPDATE_FULFILL_HTLC,UPDATE_FAIL_HTLC}

    public Type getType() {
        return msgType;
    }
    public P2PMessage(Type msgType) {
        this.msgType = msgType;
    }

    @Override
    public String toString() {
        return "Message{" +
                "msgType=" + msgType +
                '}';
    }
}
