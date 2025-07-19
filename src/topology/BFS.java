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

public class BFS implements PathFinder {
    @Override
    public List<Path> findPaths(ChannelGraph g, String start, String end, int topk) {

        int found = 0;
        var visited_vertex = new ArrayList<String>();
        var queue_vertex = new LinkedList<String>();
        List<Path> paths = new ArrayList<>();

        var last_parent = new HashMap<String, ChannelGraph.Edge>();
        last_parent.put("ROOT",null);

        int nfound = 0;

        visited_vertex.add(start);
        queue_vertex.add(start);

        while (!queue_vertex.isEmpty()) {
            var current_vertex = queue_vertex.poll();

            var list_edges =g.getAdj_map().get(current_vertex);

            for (ChannelGraph.Edge e :list_edges) {
                if (e.destination().equals(end))  {
                    nfound++;
                    List<ChannelGraph.Edge> edges = new ArrayList<>();
                    edges.add(e);

                    ChannelGraph.Edge current = last_parent.get(e.source());
                    while (current!=null) {
                        edges.add(current);
                        current = last_parent.get(current.source());
                    }
                    paths.add(new Path(edges));
                    found++;
                    if (found == topk) return paths;
                    // no need to go deeper along that path
                    visited_vertex.add(e.destination());
                    continue;
                }
                if (!visited_vertex.contains(e.destination())) {
                    // check whether destination has been pruned, being empty
                    if (g.getAdj_map().get(e.destination())!=null) {
                        last_parent.put(e.destination(),e);
                        visited_vertex.add(e.destination());
                        queue_vertex.add(e.destination());
                    }
                }
            }
        }
        return paths;
    }
}
