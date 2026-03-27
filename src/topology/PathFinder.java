package topology;

import java.util.List;

public interface PathFinder {
    List<Path> findPaths(ChannelGraph g, String start, String end, int topk);
    default void setPaymentAmount(int amountSat) {}
    // this is to be intended as the cost function that pathfinder tries to minimize, not the cost itself
    double totalCost(Path p);
}
