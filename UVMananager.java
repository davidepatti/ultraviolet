import java.util.HashSet;

public class UVMananager {

    private static HashSet<Node> nodeSet = new HashSet<>();

    public static void main(String[] args) {

        var n1 = new Node("pub1","uv1",5000000,0);
        var n2 = new Node("pub2","uv2",5000000,0);

        nodeSet.add(n1);
        nodeSet.add(n2);

        new Thread(n1).start();
        new Thread(n2).start();

    }

    public static Node findPeer(Node node) {

        for (Node x: nodeSet ) {
            if (x.getPubkey()!=node.getPubkey()) {
                return x;
            }
        }
        return null;
    }
}
