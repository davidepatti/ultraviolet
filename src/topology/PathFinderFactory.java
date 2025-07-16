package topology;

public final class PathFinderFactory {
    private PathFinderFactory() {}
    public enum Strategy { UNIFORM_COST, MULTI_PARENT_BFS, BFS }

    public static PathFinder of(Strategy s) {
        return switch (s) {
            case UNIFORM_COST      -> new UniformCost();
            case MULTI_PARENT_BFS  -> new MultiParentBFS();
            case BFS        -> new BFS();
        };
    }
}
