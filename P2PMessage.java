public interface P2PMessage {

    enum Type { CHANNEL_ANNOUNCE, CHANNEL_UPDATE};
    int getForwardings();
    String getID();
    Type getType();
    int getTimeStamp();
    P2PMessage getNext();
    Object getData();

}
