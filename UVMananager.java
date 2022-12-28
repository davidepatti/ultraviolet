import java.util.HashSet;
import java.util.Random;

public class UVMananager {

    Log log = s -> System.out.println("UV:"+s);
    private final int Msat = (int)1e6;
    public final int total_nodes;
    // in Msat
    public int min_node_capacity;
    public int max_node_capacity;

    private static HashSet<Node> nodeSet = new HashSet<>();
    private static Node[] nodeArray;

    static Random random = new Random();



    private void bootstrapNetwork() {
        log.print("bootstrapping network with "+ this.total_nodes+" nodes ("+min_node_capacity+" - "+max_node_capacity+")");

        for (int i =0;i<total_nodes; i++) {

            int max = max_node_capacity/(int)Msat;
            int min = min_node_capacity/(int)Msat;
            var funding = Msat*(random.nextInt(max-min)+min);
            var n = new Node("pk_"+i,"alias_"+i,funding,0);
            var config = new NodeBehavior(5,1*Msat,5*Msat);
            n.setBehavior(config);
            nodeSet.add(n);
        }
        nodeArray = nodeSet.toArray(new Node[nodeSet.size()]);

        for (Node n: nodeArray) {
            new Thread(n, "T_"+n.getPubkey()).start();
        }
    }

    public UVMananager(int total_nodes, int min_node_capacity, int max_node_capacity) {
        this.total_nodes = total_nodes;
        this.min_node_capacity = min_node_capacity;
        this.max_node_capacity = max_node_capacity;
    }

    public static void main(String[] args) {

        var uv = new UVMananager(10,(int)1e6,100*(int)1e6);
        uv.bootstrapNetwork();
        try {
            Thread.sleep(10000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        uv.showNetwork();

    }

    public void showNetwork() {

        for (Node n: nodeSet) {
            System.out.println("----------------------------------------");
            System.out.println(n);
            for (Channel c:n.getChannels()) {
                System.out.println(c);
            }
            System.out.println("----------------------------------------");
        }

    }

    public static Node findPeer(Node node) {

        System.out.println("UV: searching peer for "+node.getPubkey());


        var n = random.nextInt(nodeSet.size());
        var candidate = nodeArray[n];

        // check if already peer has a channell
        // remove init pk and leave only peer + initiator bool?
        if (candidate.getPubkey().compareTo(node.getPubkey())!=0)
            return candidate;

        /*
        for (Node x: nodeSet ) {
            if (x.getPubkey().compareTo(node.getPubkey())!=0) {
                System.out.println("RETURNING "+x.getPubkey()+" requested by "+node.getPubkey());
                return x;
            }
        }
        */
        return null;
    }
}
