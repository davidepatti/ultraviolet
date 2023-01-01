import java.io.*;
import java.util.Date;
import java.util.HashMap;
import java.util.Random;

public class UVManager {

    FileWriter logfile;

    Log log = (String s) ->  {
        System.out.println(s);
        try {
            logfile.write("\n"+s);
            logfile.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    };

    //private final String LOGFILE_BASEPATH = "./out/production/ultraviolet/";
    private final String LOGFILE_BASEPATH = "";
    private final int DEFAULT_PORT = 7777;
    private final int Msat = (int)1e6;
    public final int total_nodes;
    // in Msat
    public int min_node_funding;
    public int max_node_funding;

    private HashMap<String,Node> nodeMap = new HashMap<>();
    private String[] nodePubkeysArray;

    private Random random = new Random();
    private final Timechain timechain;

    private boolean boostrapped = false;

    public boolean isBoostrapped() {
        return boostrapped;
    }

    // playground
    public static void main(String[] args) {
        var uvm = new UVManager(10,10*(int)1e6,100*(int)1e6);
        if (args.length==2)
            uvm.startServer(Integer.parseInt(args[1]));
        else {
            System.out.println("No port provided for UVM Server, using default "+uvm.DEFAULT_PORT);
            uvm.startServer(uvm.DEFAULT_PORT);
        }
    }


    public synchronized HashMap<String, Node> getNodeSet(){

        return this.nodeMap;
    }

    public Timechain getTimechain() {
        return timechain;
    }

    public synchronized void bootstrapNetwork() {
        boostrapped = true;
        log.print("UVM: Bootstrapping network with "+ this.total_nodes+" nodes ("+ min_node_funding +" - "+ max_node_funding +")");

        log.print("UVM: Starting timechain thread...");
        new Thread(timechain,"timechain").start();

        for (int i =0;i<total_nodes; i++) {

            int max = max_node_funding /(int)Msat;
            int min = min_node_funding /(int)Msat;
            var funding = Msat*(random.nextInt(max-min)+min);
            var n = new Node(this,"pk_"+i,"alias_"+i,funding,0);
            var config = new NodeBehavior(5,1*Msat,5*Msat);
            n.setBehavior(config);
            nodeMap.put(n.getPubkey(),n);
        }

        for (Node n : nodeMap.values()) {
            new Thread(n, "T_"+n.getPubkey()).start();
        }
    }

    public UVManager(int total_nodes, int min_node_funding, int max_node_funding) {
        this.total_nodes = total_nodes;
        this.min_node_funding = min_node_funding;
        this.max_node_funding = max_node_funding;
        timechain = new Timechain(1000);
        try {
            logfile = new FileWriter(LOGFILE_BASEPATH+"log_uvm_" + new Date().toString() + ".txt");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.print("Initializing UVManager...");
        log.print(this.toString());
    }

    public void startServer(int port) {
        log.print("Starting UVM Server...");
        var uvm_server = new UVMServer(this,port);
        new Thread(uvm_server).start();
    }

    public Node getRandomNode() {
        var n = random.nextInt(nodeMap.size()-1);
        var some_random_key = nodeMap.keySet().toArray()[n];
        var some_node = nodeMap.get(some_random_key);
        return some_node;
    }

    public void testRandomEvent() {
        getRandomNode().updateChannel();
    }


    public void showNetwork() {

        for (Node n: nodeMap.values()) {
            System.out.println("----------------------------------------");
            System.out.println(n);
            for (Channel c:n.getChannels()) {
                System.out.println(c);
            }
            System.out.println("----------------------------------------");
        }

    }

    public Node findPeer(Node node) {

        log.print("UV: searching peer for "+node.getPubkey());

        var candidate = getRandomNode();

        if (candidate.getPubkey().compareTo(node.getPubkey())!=0)
            return candidate;

        return null;
    }

    @Override
    public String toString() {
        return "UVManager{" +
                " total_nodes=" + total_nodes +
                ", min_node_funding=" + min_node_funding +
                ", max_node_funding=" + max_node_funding +
                ", timechain=" + timechain +
                ", boostrapped=" + boostrapped +
                '}';
    }
}
