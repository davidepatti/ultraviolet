package topology;

public final class PathFinderFactory {
    private PathFinderFactory() {}
    public enum Strategy {MINI_DIJKSTRA, SHORTEST_HOP, BFS }

    public static PathFinder of(Strategy s) {
        return switch (s) {
            case MINI_DIJKSTRA -> new MiniDijkstra();
            case SHORTEST_HOP -> new ShortestHop();
            case BFS        -> new BFS();
        };
    }
}
