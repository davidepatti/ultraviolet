package topology;

import java.util.List;

public interface PathFinder {
    List<List<ChannelGraph.Edge>> findPaths(ChannelGraph g,
                                            String start,
                                            String end,
                                            boolean stopFirst);
}