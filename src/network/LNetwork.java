package network;

import message.P2PMessage;
import network.LNChannel;
import network.UVNode;

import java.util.Optional;

public interface LNetwork {
    // topology information
    Optional<LNChannel> getChannelFromNodes(String pub1, String pub2);
    UVNode getUVNode(String pubkey);
    void deliverMessage(UVNode peer, P2PMessage message);
}
