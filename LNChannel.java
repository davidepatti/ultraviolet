public interface LNChannel {
    String getChannelId();
    String getNode1PubKey();
    String getNode2PubKey();
    LNode getNode1();
    LNode getNode2();
    int getCapacity();
    int getLastUpdate();

    int getNode1TimeLockDelta();
    int getNode2TimeLockDelta();

    int getNode1FeeBase();
    int getNode1FeePpm();
    int getNode2FeeBase();
    int getNode2FeePpm();
}
