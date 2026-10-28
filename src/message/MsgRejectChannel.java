package message;

/** A terminal response to one opening proposal; duplicate/stale responses are harmless. */
public final class MsgRejectChannel extends P2PMessage {
    private static final long serialVersionUID = 1L;
    private final String temporaryChannelId;
    private final String peer;

    public MsgRejectChannel(String temporaryChannelId, String peer) {
        super(Type.REJECT_CHANNEL);
        this.temporaryChannelId = temporaryChannelId;
        this.peer = peer;
    }

    public String getTemporaryChannelId() { return temporaryChannelId; }
    public String getPeer() { return peer; }
}
