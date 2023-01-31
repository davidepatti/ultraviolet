import java.util.Optional;

public class GlobalStats {

    private UVNetworkManager uvm;

    public GlobalStats(UVNetworkManager uv_manager) {
        uvm = uv_manager;
    }

    public UVNode getMaxGraphSizeNode() {

        Optional<UVNode> max = uvm.getUVNodes().values().stream().max((e1, e2)->Integer.compare(e1.getChannelGraph().getNodeCount(), e2.getChannelGraph().getNodeCount()));
        if (max.isPresent()) return max.get();
        else return null;
    }
    public UVNode getMinGraphSizeNode() {

        Optional<UVNode> max = uvm.getUVNodes().values().stream().min((e1, e2)->Integer.compare(e1.getChannelGraph().getNodeCount(), e2.getChannelGraph().getNodeCount()));
        if (max.isPresent())
            return max.get();
        else return null;
    }

    public double getAverageGraphSize() {
        return uvm.getUVNodes().values().stream().mapToDouble(e->e.getChannelGraph().getNodeCount()).average().getAsDouble();
    }

}
