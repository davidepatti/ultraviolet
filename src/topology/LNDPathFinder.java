package topology;

public class LNDPathFinder extends MiniDijkstra{
    @Override
    public double weight(ChannelGraph.Edge edge, Path path) {
        // MiniDijkstra already accumulates cost across expansions.
        // Return only the incremental cost of this newly added edge.
        var p = edge.policy();
        if (p == null) {
            return Double.POSITIVE_INFINITY;
        }
        return p.getBaseFee() + p.getFeePpm() + p.getCLTVDelta();
    }
}
