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
public class MiniDijkstra implements PathFinder{

    public final int max_hops = 6;
    public double weight(ChannelGraph.Edge e, Path p) {
        return 1.0;
    }

    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {
        if (topk <= 0) topk = 1;

        //record candidatePath(String vertex, double cost, List<ChannelGraph.Edge> path) {}
        record candidatePath(String vertex, double cost, Path path) {}

        var queue = new PriorityQueue<candidatePath>(Comparator.comparingDouble(candidatePath::cost));
        List<Path> paths = new ArrayList<>();

        queue.add(new candidatePath(start, 0.0, new Path(null)));

        while (!queue.isEmpty() && paths.size() < topk) {
            var currentCandidate = queue.poll();
            if (currentCandidate.path().getSize()>max_hops) continue;

            if (currentCandidate.vertex().equals(end)) {            // found one of the k best
                // Path expects edges in reverse (end→start) order; we currently hold start→end.
                var edgeList = new ArrayList<ChannelGraph.Edge>(currentCandidate.path().edges());
                Collections.reverse(edgeList);
                var found = new Path(edgeList);
                paths.add(found);
                continue;                              // do not expand it further
            }

            /* expand search frontier */
            for (ChannelGraph.Edge e :
                    g.getAdjMap().getOrDefault(currentCandidate.vertex(), Set.<ChannelGraph.Edge>of())) {

                String v = e.destination();

                if (pathContainsVertex(currentCandidate.path(), v)) continue;

                var newPath = currentCandidate.path().getExtendedPath(e);
                queue.add(new candidatePath(v, currentCandidate.cost() + weight(e,newPath), newPath));
            }
        }
        return paths;
    }

    @Override
    public double totalCost(Path p) {
        double totalCost = 0.0;
        for (ChannelGraph.Edge e : p.edges()) {
            totalCost += weight(e, p);
        }

        return totalCost;
    }

    private boolean pathContainsVertex(Path path, String v) {
        for (ChannelGraph.Edge e : path.edges()) {
            if (e.source().equals(v) || e.destination().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
