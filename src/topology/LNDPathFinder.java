package topology;

import java.util.List;

public class LNDPathFinder extends MiniDijkstra{
    @Override
    public double weight(ChannelGraph.Edge edge, Path path) {
        double cost = 0.0;
        for (var e: path.edges())  {
            cost+=e.policy().getBaseFee()+e.policy().getFeePpm()+e.policy().getCLTVDelta();
        }

        return cost;
    }
}
