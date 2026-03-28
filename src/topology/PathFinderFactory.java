package topology;

public final class PathFinderFactory {
    private PathFinderFactory() {}
    public enum Strategy {MINI_DIJKSTRA, SHORTEST_HOP, BFS,LND }

    public static PathFinder of(Strategy s) {
        return switch (s) {
            case MINI_DIJKSTRA -> new MiniDijkstra();
            case SHORTEST_HOP -> new ShortestHop();
            case BFS        -> new BFS();
            case LND        -> new LNDPathFinder();
        };
    }

    public static Strategy strategyOf(PathFinder pathFinder) {
        if (pathFinder instanceof LNDPathFinder) {
            return Strategy.LND;
        }
        if (pathFinder instanceof MiniDijkstra) {
            return Strategy.MINI_DIJKSTRA;
        }
        if (pathFinder instanceof ShortestHop) {
            return Strategy.SHORTEST_HOP;
        }
        if (pathFinder instanceof BFS) {
            return Strategy.BFS;
        }
        throw new IllegalArgumentException("Unknown path finder implementation: " + pathFinder.getClass().getName());
    }
}
