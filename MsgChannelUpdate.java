public class MsgChannelUpdate extends P2PMessage {
    /**
     * @return 
     */
    private LNChannel update_channel;
    public MsgChannelUpdate(LNChannel channel,int timestamp, int forwardings) {
        super("ANN:"+channel.getId(),forwardings,timestamp);
        this.update_channel = channel;
        this.msgType = Type.CHANNEL_UPDATE;
    }

    public LNChannel getLNChannel() {
        return update_channel;
    }

    public synchronized MsgChannelUpdate getNext() {
        return new MsgChannelUpdate(this.update_channel,this.timestamp,this.forwardings+1);
    }
}
