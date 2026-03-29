package topology;

import misc.UVConfig;

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

    public static PathFinder of(Strategy s, UVConfig config) {
        if (config == null) {
            return of(s);
        }
        return switch (s) {
            case MINI_DIJKSTRA -> new MiniDijkstra(config.pathfinding_max_hops);
            case SHORTEST_HOP -> new ShortestHop();
            case BFS        -> new BFS();
            case LND        -> new LNDPathFinder(
                    config.pathfinding_max_hops,
                    config.pathfinding_lnd_risk_factor,
                    config.pathfinding_lnd_base_attempt_cost_msat,
                    config.pathfinding_lnd_attempt_cost_ppm,
                    config.pathfinding_lnd_default_path_probability,
                    config.pathfinding_lnd_default_payment_amount_sat
            );
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
