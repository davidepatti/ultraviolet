import java.util.Optional;

public class GlobalStats {

    private UVManager uvm;

    public GlobalStats(UVManager uv_manager) {
        uvm = uv_manager;
    }

    public UVNode getMaxGraphSizeNode() {

        Optional<UVNode> max = uvm.getUVnodes().values().stream().max((e1, e2)->Integer.compare(e1.getChannelGraph().getNodeCount(), e2.getChannelGraph().getNodeCount()));
        return max.get();
    }
    public UVNode getMinGraphSizeNode() {

        Optional<UVNode> max = uvm.getUVnodes().values().stream().min((e1, e2)->Integer.compare(e1.getChannelGraph().getNodeCount(), e2.getChannelGraph().getNodeCount()));
        return max.get();
    }

}
