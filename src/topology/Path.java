package topology;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An immutable sequence of edges from one node to another. */
public final class Path {
    private final List<ChannelGraph.Edge> edges;

    public Path(List<ChannelGraph.Edge> edges) {
        this.edges = List.copyOf(edges);      // defensive copy + immutability
    }

    /** Edges in order from source to destination. */
    public List<ChannelGraph.Edge> edges() {
        return Collections.unmodifiableList(edges);
    }

    /** Convenience accessor for the first vertex. */
    public String getStart() {
        return edges.isEmpty() ? null : edges.get(0).source();
    }

    /** Convenience accessor for the last vertex. */
    public String getEnd() {
        return edges.isEmpty() ? null : edges.get(edges.size() - 1).destination();
    }

    /** Optional: total weight / fee / latency, computed lazily or cached. */
    public double totalCost() {
        double cost = 0.0;
        for( var e : edges) {
            cost+=e.weight();
        }
        return cost;
    }

    @Override public String toString() {
        StringBuilder s = new StringBuilder("(");

        for (int i = edges.size();i>0;i--) {
            var e = edges.get(i-1);
            s.append(e.source()).append("->");
        }
        s.append(edges.get(0).destination()).append(")");
        return s.toString();
    }
    @Override public int hashCode()     { return Objects.hash(edges); }
    @Override public boolean equals(Object o) {
        return o instanceof Path p && edges.equals(p.edges);
    }
}