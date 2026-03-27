package topology;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;

/* -------------------------------------------------------------------------
 * Original BFS  –  one-parent, hop-oriented
 *
 * Idea:      Traverse layer-by-layer with a FIFO queue; mark a vertex the
 *            first time it is reached; record a path as soon as an edge
 *            touches the destination.
 *
 * Result:    Returns one minimum-hop path plus any longer routes found
 *            later that end at the same target.  Ignores edge weights.
 *
 * Limitation:May miss the fee-cheapest route when weights differ; later,
 *            cheaper arrivals to an already-visited vertex are discarded.
 * -------------------------------------------------------------------------*/

public class BFS extends PathFinder {
    @Override
    public SearchResult findPaths(ChannelGraph g, String start, String end, int topk) {

        int found = 0;
        var visited_vertex = new ArrayList<String>();
        var queue_vertex = new LinkedList<String>();
        List<Path> paths = new ArrayList<>();
        var stats = new SearchStatsCollector();

        var last_parent = new HashMap<String, ChannelGraph.Edge>();
        last_parent.put("ROOT",null);

        visited_vertex.add(start);
        queue_vertex.add(start);

        while (!queue_vertex.isEmpty()) {
            var current_vertex = queue_vertex.poll();
            stats.investigatedStates++;

            var list_edges =g.getAdjMap().get(current_vertex);
            if (list_edges == null) {
                continue;
            }

            for (ChannelGraph.Edge e :list_edges) {
                stats.expandedEdges++;
                if (!canTraverse(e)) {
                    stats.excludedByCapacity++;
                    continue;
                }
                if (e.destination().equals(end))  {
                    List<ChannelGraph.Edge> edges = new ArrayList<>();
                    edges.add(e);

                    ChannelGraph.Edge current = last_parent.get(e.source());
                    while (current!=null) {
                        edges.add(current);
                        current = last_parent.get(current.source());
                    }
                    paths.add(new Path(edges));
                    found++;
                    if (found == topk) return buildSearchResult(paths, stats);
                    // no need to go deeper along that path
                    visited_vertex.add(e.destination());
                    continue;
                }
                if (!visited_vertex.contains(e.destination())) {
                    // check whether destination has been pruned, being empty
                    if (g.getAdjMap().get(e.destination())!=null) {
                        last_parent.put(e.destination(),e);
                        visited_vertex.add(e.destination());
                        queue_vertex.add(e.destination());
                    }
                } else {
                    stats.excludedByVisitedState++;
                }
            }
        }
        return buildSearchResult(paths, stats);
    }

    @Override
    public double totalCost(Path p) {
        return p.edges().size();
    }

    @Override
    public PathDetails describePath(Path path) {
        double hops = path.getSize();
        return new PathDetails(path, hops, List.of(new CostComponent("hop_count", hops)));
    }
}
