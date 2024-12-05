import java.util.Optional;

public interface Network {
    Optional<LNChannel> getChannelFromNodes(String pub1, String pub2);

    UVNode findLNNode(String pubkey);

    UVNode getUVNode(String pubkey);

    P2PNode getP2PNode(String pubkey);
}
