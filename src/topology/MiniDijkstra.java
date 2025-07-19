package topology;

import java.util.*;

/* -------------------------------------------------------------------------
 * Uniform-cost queue  –  “mini-Dijkstra”
 *
 * We stop as soon as the destination is popped, so we return only one (or the tied)
 * cheapest path(s) instead of filling the entire dist table—hence “mini”.
 *
 * Idea:      Swap the FIFO queue for a min-priority queue keyed by the
 *            accumulated edge weight.  Relax a vertex whenever a cheaper
 *            cost is found; stop when the destination pops from the queue.
 *
 * Result:    First (or only) path returned is globally cheapest by weight;
 *            optional tie-handling can return all equal-cost alternatives.
 *
 * Limitation:Slightly slower than BFS (O(E + V log V)); still trivial for
 *            LN-sized graphs.  Requires non-negative edge weights.
 * -------------------------------------------------------------------------*/
public class MiniDijkstra implements PathFinder{
    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {

            /* PQ node = (vertex, accumulated-cost)                               */
            record Node(String vertex, double cumulated_cost) {}
            var queue    = new PriorityQueue<Node>( Comparator.comparingDouble(Node::cumulated_cost));

            var bestCost = new HashMap<String, Double>();

            /* parents[vertex]   = *list* of incoming edges that hit vertex at bestCost[vertex]  */
            var parents  = new HashMap<String, List<ChannelGraph.Edge>>();

            bestCost.put(start, 0.0);
            queue.add(new Node(start, 0.0));

            Double finalCost = null;          // optimal cost to 'end' (when known)

            while (!queue.isEmpty()) {
                var cur = queue.poll();
                String u     = cur.vertex();
                double costU = cur.cumulated_cost();

                if (costU > bestCost.get(u)) continue;          // stale entry

                if (u.equals(end)) {                            // destination popped
                    finalCost = costU;                          // cost is optimal
                    if (topk==1) break;                       // early exit option
                }

                for (ChannelGraph.Edge e : g.getAdj_map().getOrDefault(u, Set.<ChannelGraph.Edge>of())) {
                    String v       = e.destination();
                    double altCost = costU + e.weight();
                    double best    = bestCost.getOrDefault( v, Double.POSITIVE_INFINITY);

                    if (altCost < best) {                       // strictly better
                        bestCost.put(v, altCost);
                        var list = new ArrayList<ChannelGraph.Edge>();
                        list.add(e);
                        parents.put(v, list);
                        queue.add(new Node(v, altCost));
                    } else if (altCost == best) {               // tie → keep edge
                        parents.computeIfAbsent( v, k -> new ArrayList<>()).add(e);
                        /* no need to enqueue vertex again: same cost already queued   */
                    }
                }

                if (finalCost != null &&
                        (queue.isEmpty() || queue.peek().cumulated_cost() > finalCost))
                    break;
            }

            /* ---------- back-track to enumerate every optimal path ------------ */
            List<Path> paths = new ArrayList<>();
            if (!bestCost.containsKey(end)) return paths;       // unreachable

            buildPaths(end, parents, new ArrayList<>(), paths);

            if (topk==1 && !paths.isEmpty())
                return new ArrayList<>(List.of(paths.get(0)));
            return paths;
        }

        /* Depth-first back-tracking: builds paths reversed (end → start).        */
        private void buildPaths(String v, Map<String, List<ChannelGraph.Edge>> parents, List<ChannelGraph.Edge> partial, List<Path> out) {
            var plist = parents.get(v);
            if (plist == null) {                 // reached the source
                out.add(new Path(partial));
                return;
            }
            for (ChannelGraph.Edge e : plist) {
                partial.add(e);
                buildPaths(e.source(), parents, partial, out);
                partial.remove(partial.size() - 1);
            }

        }
}
