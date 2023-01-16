// Java program to implement Graph
// with the help of Generics

import java.util.*;

class Graph<T> {

    // We use Hashmap to store the edges in the graph
    private Map<T, List<T> > map = new HashMap<>();

    public Map<T,List<T>> getMap() {
        return map;
    }


    // This function adds a new vertex to the graph
    public void addVertex(T s)
    {
        if (!map.containsKey(s))
            map.put(s, new LinkedList<T>());
    }

    // This function adds the edge
    // between source to destination
    public void addEdge(T source,
                        T destination,
                        boolean bidirectional)
    {

        if (!map.containsKey(source))
            addVertex(source);

        if (!map.containsKey(destination))
            addVertex(destination);

        if (this.hasEdge(source,destination)) return;

        map.get(source).add(destination);

        if (bidirectional == true) {
            map.get(destination).add(source);
        }
    }

    // This function gives the count of vertices
    public int getVertexCount()
    {
        int v = map.keySet().size();
        System.out.println("The graph has " + v + " vertex");
        return v;
    }

    // This function gives the count of edges
    public void getEdgesCount(boolean bidirection)
    {
        int count = 0;
        for (T v : map.keySet()) {
            count += map.get(v).size();
        }
        if (bidirection == true) {
            count = count / 2;
        }
        System.out.println("The graph has "
                + count
                + " edges.");
    }

    // This function gives whether
    // a vertex is present or not.
    public boolean hasVertex(T s)
    {
        /*
        if (map.containsKey(s)) {
            System.out.println("The graph contains "
                    + s + " as a vertex.");
        }
        else {
            System.out.println("The graph does not contain "
                    + s + " as a vertex.");
        }
         */

        return map.containsKey(s);
    }

    // This function gives whether an edge is present or not.
    public boolean hasEdge(T s, T d)
    {
        /*
        if (map.get(s).contains(d)) {
            System.out.println("The graph has an edge between "
                    + s + " and " + d + ".");
        }
        else {
            System.out.println("The graph has no edge between "
                    + s + " and " + d + ".");
        }
         */

        if (map.containsKey(s)) {
                if (map.get(s).contains(d)) return true;
                    else return false;
        }
        return false;
    }

    public void DFS_util(T start_node, HashSet<T> visited) {
        visited.add(start_node);
        System.out.println("v:"+start_node);

        Iterator<T> i = map.get(start_node).listIterator();
        while (i.hasNext()) {
            var n = i.next();
            if (!visited.contains(n))
                DFS_util(n,visited);
        }
    }

    public void DFS(T start_node) {
        var visited = new HashSet<T>();
        DFS_util(start_node,visited);
    }

    // Prints the adjancency list of each vertex.
    @Override
    public String toString()
    {
        StringBuilder builder = new StringBuilder();

        for (T v : map.keySet()) {
            builder.append(v.toString() + ": ");
            for (T w : map.get(v)) {
                builder.append(w.toString() + " ");
            }
            builder.append("\n");
        }

        return (builder.toString());
    }


    public static void main(String args[])
    {

        // Object of graph is created.
        Graph<Integer> g = new Graph<Integer>();

        // edges are added.
        // Since the graph is bidirectional,
        // so boolean bidirectional is passed as true.
        g.addEdge(0, 1, true);
        g.addEdge(0, 4, true);
        g.addEdge(1, 2, true);
        g.addEdge(1, 3, true);
        g.addEdge(1, 4, true);
        g.addEdge(2, 3, true);
        g.addEdge(3, 4, true);

        // Printing the graph
        System.out.println("Graph:\n"
                + g.toString());

        // Gives the no of vertices in the graph.
        g.getVertexCount();

        // Gives the no of edges in the graph.
        g.getEdgesCount(true);

        // Tells whether the edge is present or not.
        g.hasEdge(3, 4);

        // Tells whether vertex is present or not
        g.hasVertex(5);
    }
}

// Driver Code

