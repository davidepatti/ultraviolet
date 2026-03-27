package topology;

import java.util.ArrayList;
import java.util.List;

public abstract class PathFinder {
    public record CostComponent(String label, double value) {}

    public record PathDetails(Path path, double totalCost, List<CostComponent> components) {
        public PathDetails {
            components = List.copyOf(components);
        }
    }

    public record SearchStats(int investigatedStates,
                              int expandedEdges,
                              int excludedByCapacity,
                              int excludedByVisitedState,
                              int excludedByCycle,
                              int excludedByMaxHops,
                              int excludedByCost,
                              int returnedPaths) {}

    public record SearchResult(List<PathDetails> paths, SearchStats stats) {
        public SearchResult {
            paths = List.copyOf(paths);
        }
    }

    protected static final class SearchStatsCollector {
        int investigatedStates;
        int expandedEdges;
        int excludedByCapacity;
        int excludedByVisitedState;
        int excludedByCycle;
        int excludedByMaxHops;
        int excludedByCost;

        SearchStats snapshot(int returnedPaths) {
            return new SearchStats(
                    investigatedStates,
                    expandedEdges,
                    excludedByCapacity,
                    excludedByVisitedState,
                    excludedByCycle,
                    excludedByMaxHops,
                    excludedByCost,
                    returnedPaths
            );
        }
    }

    protected int paymentAmountSat = 0;

    public abstract SearchResult findPaths(ChannelGraph g, String start, String end, int topk);

    public void setPaymentAmount(int amountSat) {
        paymentAmountSat = Math.max(amountSat, 0);
    }

    protected boolean canTraverse(ChannelGraph.Edge edge) {
        return paymentAmountSat <= 0 || edge.capacity() >= paymentAmountSat;
    }

    public PathDetails describePath(Path path) {
        double total = totalCost(path);
        return new PathDetails(path, total, List.of(new CostComponent("total_cost", total)));
    }

    protected SearchResult buildSearchResult(List<Path> paths, SearchStatsCollector statsCollector) {
        ArrayList<PathDetails> details = new ArrayList<>(paths.size());
        for (Path path : paths) {
            details.add(describePath(path));
        }
        return new SearchResult(details, statsCollector.snapshot(paths.size()));
    }

    // this is to be intended as the cost function that pathfinder tries to minimize, not the cost itself
    public abstract double totalCost(Path p);
}
