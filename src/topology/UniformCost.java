package topology;

import java.util.*;

/* -------------------------------------------------------------------------
 * Uniform-cost queue  –  “mini-Dijkstra”
 *
 * -------------------------------------------------------------------------*/
public class UniformCost implements PathFinder{
    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {
        if (topk <= 0) topk = 1;

        /* ------------------------------------------------------------
         * k‑shortest (simple) paths using a uniform‑cost search over
         * complete paths instead of partial distances.
         * ------------------------------------------------------------ */
        record Node(String vertex, double cost, List<ChannelGraph.Edge> path) {}

        var queue = new PriorityQueue<Node>(Comparator.comparingDouble(Node::cost));
        List<Path> paths = new ArrayList<>();

        queue.add(new Node(start, 0.0, new ArrayList<>()));

        while (!queue.isEmpty() && paths.size() < topk) {
            var cur = queue.poll();

            if (cur.vertex().equals(end)) {            // found one of the k best
                // Path expects edges in reverse (end→start) order; we currently hold start→end.
                var edgeList = new ArrayList<ChannelGraph.Edge>(cur.path());
                Collections.reverse(edgeList);
                paths.add(new Path(edgeList));
                continue;                              // do not expand it further
            }

            /* expand search frontier */
            for (ChannelGraph.Edge e :
                    g.getAdj_map().getOrDefault(cur.vertex(), Set.<ChannelGraph.Edge>of())) {

                String v = e.destination();

                /* avoid cycles so that we only output simple paths */
                if (pathContainsVertex(cur.path(), v)) continue;

                var newPath = new ArrayList<ChannelGraph.Edge>(cur.path());
                newPath.add(e);
                queue.add(new Node(v, cur.cost() + e.weight(), newPath));
            }
        }
        return paths;
    }

    /**
     * Check whether the current partial path already visits vertex {@code v}.
     * This prevents cycles, ensuring we only enumerate simple paths.
     */
    private boolean pathContainsVertex(List<ChannelGraph.Edge> path, String v) {
        for (ChannelGraph.Edge e : path) {
            if (e.source().equals(v) || e.destination().equals(v)) {
                return true;
            }
        }
        return false;
    }
}
