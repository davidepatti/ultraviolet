import java.util.HashSet;
import java.util.Random;

public class UVManager {

    Log log = s -> System.out.println("UV:"+s);
    private final int Msat = (int)1e6;
    public final int total_nodes;
    // in Msat
    public int min_node_funding;
    public int max_node_funding;

    private HashSet<Node> nodeSet = new HashSet<>();
    private Node[] nodeArray;

    private Random random = new Random();
    private final Timechain timechain;

    private boolean boostrapped = false;

    public boolean isBoostrapped() {
        return boostrapped;
    }

    // playground
    public static void main(String[] args) {
        var uvm = new UVManager(10,10*(int)1e6,100*(int)1e6);
        uvm.startServer(7777);
    }


    public synchronized HashSet<Node> getNodeSet(){

        return this.nodeSet;
    }

    public Timechain getTimechain() {
        return timechain;
    }

    public synchronized void bootstrapNetwork() {
        boostrapped = true;
        log.print("bootstrapping network with "+ this.total_nodes+" nodes ("+ min_node_funding +" - "+ max_node_funding +")");

        log.print("Starting timechain...");
        new Thread(timechain,"timechain").start();

        for (int i =0;i<total_nodes; i++) {

            int max = max_node_funding /(int)Msat;
            int min = min_node_funding /(int)Msat;
            var funding = Msat*(random.nextInt(max-min)+min);
            var n = new Node(this,"pk_"+i,"alias_"+i,funding,0);
            var config = new NodeBehavior(5,1*Msat,5*Msat);
            n.setBehavior(config);
            nodeSet.add(n);
        }
        nodeArray = nodeSet.toArray(new Node[nodeSet.size()]);

        for (Node n: nodeArray) {
            new Thread(n, "T_"+n.getPubkey()).start();
        }
    }

    public UVManager(int total_nodes, int min_node_funding, int max_node_funding) {
        System.out.println("Initializing UVManager...");
        System.out.println(this);
        this.total_nodes = total_nodes;
        this.min_node_funding = min_node_funding;
        this.max_node_funding = max_node_funding;
        timechain = new Timechain(1000);
    }

    public void startServer(int port) {
        System.out.println("Starting UVM Server...");
        var uvm_server = new UVMServer(this,port);
        new Thread(uvm_server).start();

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

    public Node findPeer(Node node) {

        System.out.println("UV: searching peer for "+node.getPubkey());

        var n = random.nextInt(nodeSet.size());
        var candidate = nodeArray[n];

        if (candidate.getPubkey().compareTo(node.getPubkey())!=0)
            return candidate;

        return null;
    }

    @Override
    public String toString() {
        return "UVManager{" +
                "total_nodes=" + total_nodes +
                ", min_node_funding=" + min_node_funding +
                ", max_node_funding=" + max_node_funding +
                '}';
    }
}
