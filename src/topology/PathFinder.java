package topology;

import java.util.List;

public interface PathFinder {
    List<Path> findPaths(ChannelGraph g, String start, String end, int topk);
}