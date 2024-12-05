import java.util.Optional;

public interface LNetwork {
    Optional<LNChannel> getChannelFromNodes(String pub1, String pub2);

    UVNode getUVNode(String pubkey);
}
