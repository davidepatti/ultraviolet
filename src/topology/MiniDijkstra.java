package topology;

import java.util.*;

/* -------------------------------------------------------------------------
 * Uniform-cost queue  –  “mini-Dijkstra”
 *
 * -------------------------------------------------------------------------*/
    /**
     * A lightweight uniform-cost search ("mini-Dijkstra") over a {@link ChannelGraph}.
     * Expands the cheapest frontier first (sum of edge policy base fee + fee ppm + CLTV delta)
     * to collect up to {@code topk} lowest-cost simple paths from {@code start} to {@code end}.
     * Paths longer than 6 hops are pruned; vertices already on the partial path are skipped to avoid cycles.
     */
public class MiniDijkstra implements PathFinder{

    private double weight(ChannelGraph.Edge e, List<ChannelGraph.Edge> p) {
        //return 1.0;
        return e.policy().getBaseFee()+e.policy().getFeePpm()+e.policy().getCLTVDelta();
    }

    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {
        if (topk <= 0) topk = 1;

        record Node(String vertex, double cost, List<ChannelGraph.Edge> path) {}

        var queue = new PriorityQueue<Node>(Comparator.comparingDouble(Node::cost));
        List<Path> paths = new ArrayList<>();

        queue.add(new Node(start, 0.0, new ArrayList<>()));

        while (!queue.isEmpty() && paths.size() < topk) {
            var cur = queue.poll();
            if (cur.path().size()>6) continue;

            if (cur.vertex().equals(end)) {            // found one of the k best
                // Path expects edges in reverse (end→start) order; we currently hold start→end.
                var edgeList = new ArrayList<ChannelGraph.Edge>(cur.path());
                Collections.reverse(edgeList);
                var found = new Path(edgeList);
                paths.add(found);
                continue;                              // do not expand it further
            }

            /* expand search frontier */
            for (ChannelGraph.Edge e :
                    g.getAdjMap().getOrDefault(cur.vertex(), Set.<ChannelGraph.Edge>of())) {

                String v = e.destination();

                if (pathContainsVertex(cur.path(), v)) continue;

                var newPath = new ArrayList<ChannelGraph.Edge>(cur.path());
                newPath.add(e);
                queue.add(new Node(v, cur.cost() + weight(e,newPath), newPath));
            }
        }
        return paths;
    }

    @Override
    public double totalCost(Path p) {
        double totalCost = 0.0;
        for (ChannelGraph.Edge e : p.edges()) {
            totalCost += weight(e, p.edges());
        }
        return totalCost;
    }

    private boolean pathContainsVertex(List<ChannelGraph.Edge> path, String v) {
        for (ChannelGraph.Edge e : path) {
            if (e.source().equals(v) || e.destination().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
