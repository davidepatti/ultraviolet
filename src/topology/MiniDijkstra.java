package topology;

import java.util.*;

/* -------------------------------------------------------------------------
 * Uniform-cost queue  –  “mini-Dijkstra”
 *
 * -------------------------------------------------------------------------*/
    /**
     * A lightweight uniform-cost search ("mini-Dijkstra") over a {@link ChannelGraph}.
     * Expands the cheapest frontier first
     * to collect up to {@code topk} lowest-cost simple paths from {@code start} to {@code end}.
     * Paths longer than max hops are pruned; vertices already on the partial path are skipped to avoid cycles.
     */
public class MiniDijkstra extends PathFinder {
    public static final int DEFAULT_MAX_HOPS = 6;

    private final int maxHops;

    public MiniDijkstra() {
        this(DEFAULT_MAX_HOPS);
    }

    public MiniDijkstra(int maxHops) {
        this.maxHops = Math.max(0, maxHops);
    }

    /** Incremental cost of {@code e} as the last edge of {@code p}. */
    public double weight(ChannelGraph.Edge e, Path p) {
        return weight(e, p.getSize());
    }

    /**
     * Incremental cost of an edge given only its position: {@code hops} is the length of the
     * path from the start up to and including the edge. The search uses this form so that it
     * does not have to materialise a path for every expanded edge.
     */
    protected double weight(ChannelGraph.Edge e, int hops) {
        return 1.0;
    }

    /** Search state: a vertex reached through {@code edge} from {@code parent}. */
    private static final class Candidate {
        final String vertex;
        final double cost;
        final ChannelGraph.Edge edge;
        final Candidate parent;
        final int hops;

        Candidate(String vertex, double cost, ChannelGraph.Edge edge, Candidate parent) {
            this.vertex = vertex;
            this.cost = cost;
            this.edge = edge;
            this.parent = parent;
            this.hops = parent == null ? 0 : parent.hops + 1;
        }

        boolean visits(String v) {
            for (Candidate c = this; c != null; c = c.parent) {
                if (c.vertex.equals(v)) return true;
            }
            return false;
        }

        /** Edges from the last hop back to the first, the order {@link Path} expects. */
        Path toPath() {
            var edges = new ArrayList<ChannelGraph.Edge>(hops);
            for (Candidate c = this; c.parent != null; c = c.parent) edges.add(c.edge);
            return new Path(edges);
        }
    }

    @Override
    public SearchResult findPaths(ChannelGraph g, String start, String end, int topk) {
        if (topk <= 0) topk = 1;

        var queue = new PriorityQueue<Candidate>(Comparator.comparingDouble((Candidate c) -> c.cost));
        Map<String, List<Double>> bestCostsByVertex = new HashMap<>();
        List<Path> paths = new ArrayList<>();
        var stats = new SearchStatsCollector();

        queue.add(new Candidate(start, 0.0, null, null));
        bestCostsByVertex.put(start, new ArrayList<>(List.of(0.0)));

        while (!queue.isEmpty() && paths.size() < topk) {
            var currentCandidate = queue.poll();
            stats.investigatedStates++;
            if (currentCandidate.vertex.equals(end)) {            // found one of the k best
                paths.add(currentCandidate.toPath());
                continue;                              // do not expand it further
            }

            // A path already at the hop limit cannot be extended. Rejecting it here, before
            // its successors are costed, keeps over-length arrivals out of the per-vertex
            // best-cost slots, where they could displace valid shorter routes.
            if (currentCandidate.hops>=maxHops) {
                stats.excludedByMaxHops++;
                continue;
            }

            /* expand search frontier */
            for (ChannelGraph.Edge e :
                    g.getAdjMap().getOrDefault(currentCandidate.vertex, Set.<ChannelGraph.Edge>of())) {
                stats.expandedEdges++;
                if (!canTraverse(e)) {
                    stats.excludedByCapacity++;
                    continue;
                }

                String v = e.destination();

                if (currentCandidate.visits(v)) {
                    stats.excludedByCycle++;
                    continue;
                }

                double newCost = currentCandidate.cost + weight(e, currentCandidate.hops + 1);
                if (!Double.isFinite(newCost)) {
                    stats.excludedByCost++;
                    continue;
                }
                if (shouldEnqueue(bestCostsByVertex, v, newCost, topk)) {
                    queue.add(new Candidate(v, newCost, e, currentCandidate));
                } else {
                    stats.excludedByVisitedState++;
                }
            }
        }
        return buildSearchResult(paths, stats);
    }

    @Override
    public double totalCost(Path p) {
        double totalCost = 0.0;
        for (ChannelGraph.Edge e : p.edges()) {
            totalCost += weight(e, p);
        }

        return totalCost;
    }

    @Override
    public PathDetails describePath(Path path) {
        double total = totalCost(path);
        return new PathDetails(path, total, List.of(new CostComponent("uniform_path_cost", total)));
    }

    private boolean shouldEnqueue(Map<String, List<Double>> bestCostsByVertex, String vertex, double newCost, int topk) {
        List<Double> bestCosts = bestCostsByVertex.computeIfAbsent(vertex, __ -> new ArrayList<>());
        int insertAt = Collections.binarySearch(bestCosts, newCost);
        if (insertAt < 0) {
            insertAt = -insertAt - 1;
        }
        if (insertAt >= topk) {
            return false;
        }
        bestCosts.add(insertAt, newCost);
        if (bestCosts.size() > topk) {
            bestCosts.remove(topk);
        }
        return true;
    }
}
