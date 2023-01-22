// Java program to implement Graph
// with the help of Generics

import java.io.Serializable;
import java.util.*;

class Graph<T> {

    //private static final long serialVersionUID = 120679L;
    // We use Hashmap to store the edges in the graph
    private final Map<T, List<T> > map = new HashMap<>();

    public Map<T,List<T>> getMap() {
        return map;
    }


    // This function adds a new vertex to the graph
    public void addVertex(T s) {
        if (!map.containsKey(s))
            map.put(s, new LinkedList<T>());
    }

    // This function adds the edge
    // between source to destination
    public void addEdge(T source, T destination, boolean bidirectional) {

        if (!map.containsKey(source))
            addVertex(source);

        if (!map.containsKey(destination))
            addVertex(destination);

        if (this.hasEdge(source,destination)) return;

        map.get(source).add(destination);

        if (bidirectional) {
            map.get(destination).add(source);
        }
    }

    // This function gives the count of vertices
    public int getVertexCount() {
        int v = map.keySet().size();
        System.out.println("The graph has " + v + " vertex");
        return v;
    }

    // This function gives the count of edges
    public int getEdgesCount(boolean bidirection) {
        int count = 0;
        for (T v : map.keySet()) {
            count += map.get(v).size();
        }
        if (bidirection) {
            count = count / 2;
        }
        return count;
    }

    // This function gives whether
    // a vertex is present or not.
    public boolean hasVertex(T s) {
        return map.containsKey(s);
    }

    // This function gives whether an edge is present or not.
    @SuppressWarnings("RedundantIfStatement")
    public boolean hasEdge(T s, T d) {

        if (map.containsKey(s)) {
                if (map.get(s).contains(d)) return true;
                    else return false;
        }
        return false;
    }


    public void DFS_path_util(T current_node, T end_node, HashSet<T> visited) {
        visited.add(current_node);
        System.out.println("visiting:"+current_node);
        if (current_node.equals(end_node)) System.out.println("FOUND!");

        Iterator<T> i = map.get(current_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            System.out.print("   -> Considering:"+n+ " ");
            if (!visited.contains(n)) {
                DFS_path_util(n,end_node,visited);
            }
        }
    }

    public void DFS_path(T start_node,T end_node) {
        var visited = new HashSet<T>();
        System.out.println("Starting from "+start_node+" destination "+end_node);
        DFS_path_util(start_node,end_node,visited);
    }

    public void DFS_util(T current_node, HashSet<T> visited) {
        visited.add(current_node);
        System.out.println("visiting:"+current_node);

        Iterator<T> i = map.get(current_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            System.out.print("   -> Considering:"+n+ " ");
            if (!visited.contains(n)) {
                System.out.println("NOT VISITED");
                DFS_util(n,visited);
            }
        }
    }

    public void DFS(T start_node) {
        var visited = new HashSet<T>();
        System.out.println("Starting from "+start_node);
        DFS_util(start_node,visited);
    }

    // Prints the adjancency list of each vertex.
    @Override
    public String toString() {
        StringBuilder builder = new StringBuilder();

        for (T v : map.keySet()) {
            builder.append(v.toString()).append(": ");
            for (T w : map.get(v)) {
                builder.append(w.toString()).append(" ");
            }
            builder.append("\n");
        }
        return (builder.toString());
    }
}


