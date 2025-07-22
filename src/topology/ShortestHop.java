package topology;

import java.util.*;


/* -------------------------------------------------------------------------
 * Multi-parent BFS  –  all shortest-hop ties
 *
 * Idea:      Still expands by hop depth, but keeps *every* parent edge
 *            that reaches a vertex at the current minimum depth.  After
 *            traversal, back-tracks through the parent lists to emit all
 *            minimum-hop paths.
 *
 * Result:    Produces every path whose hop count equals the global minimum
 *            between start and end; good to study hop-level diversity.
 *
 * Limitation:Edge weights are still ignored; if the fee-optimal route is
 *            longer than the hop minimum, it will not appear.  Memory
 *            grows with the number of same-depth parents.
 * -------------------------------------------------------------------------*/
public class ShortestHop implements PathFinder {
    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {
        /* frontier ordered by hop depth exactly as before */
        var queue = new LinkedList<String>();
        int found = 0;

        /* depth map instead of visited-set */
        var depth  = new HashMap<String, Integer>();
        depth.put(start, 0);

        /* parents[v] = list of incoming edges that reach v with minimum depth */
        var parents = new HashMap<String, ArrayList<ChannelGraph.Edge>>();

        queue.add(start);

        while (!queue.isEmpty()) {
            var u = queue.poll();
            int d = depth.get(u);

            for (ChannelGraph.Edge e : g.getAdjMap().getOrDefault(u, Set.<ChannelGraph.Edge>of())) {
                String v = e.destination();

                /* first time we reach v → record depth & parent, enqueue */
                if (!depth.containsKey(v)) {
                    depth.put(v, d + 1);
                    parents.computeIfAbsent(v, k -> new ArrayList<>()).add(e);
                    queue.add(v);
                }
                /* reached again at the SAME depth → additional shortest parent */
                else if (depth.get(v) == d + 1) {
                    parents.get(v).add(e);
                }
            }
        }

        /* ---------- reconstruct every shortest path ---------- */
        List<Path> paths = new ArrayList<>();
        if (!depth.containsKey(end)) return paths;      // unreachable

        buildPaths(end, parents, new ArrayList<>(), paths);

        return paths;
    }

    @Override
    public double totalCost(Path p) {
        return p.edges().size();
    }

    /* DFS back-tracking, collects paths reversed (end → start) */
    private void buildPaths(String v,
                            Map<String, ArrayList<ChannelGraph.Edge>> parents,
                            List<ChannelGraph.Edge> partial,
                            List<Path> out) {
        var plist = parents.get(v);
        if (plist == null) {               // reached the start
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
