import java.io.*;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

public class UVManager {

    CountDownLatch bootstrap_latch;
    private final HashMap<String, UVNode> nodeMap = new HashMap<>();

    private final Random random = new Random();
    private final static Timechain timechain = new Timechain(10000);

    private boolean boostrapped = false;
    private static FileWriter logfile;
    static Log log = System.out::println;

    public static void main(String[] args) {
        // redefining the log to write a file (optional) ////////////////////////////////////////////////

        if (args.length == 1) {
            log.print("Loading configuration file:"+args[0]);
            UVConfig.loadConfig(args[0]);
        }
        else {
            log.print("No config file provided, using defaults");
            UVConfig.setDefaults();
        }

        try {
            logfile = new FileWriter(UVConfig.logfile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        log = (String s) ->  {
            try {
                logfile.write("\n["+timechain.getCurrent_block()+"]"+s);
                logfile.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
        //////////////////////////////////////////////////////////////////////7

        var uvm = new UVManager();
        uvm.startServer(UVConfig.server_port);
    }

    public boolean isBoostrapped() {
        return boostrapped;
    }


    public synchronized HashMap<String, UVNode> getNodeMap(){

        return this.nodeMap;
    }

    public Timechain getTimechain() {
        return timechain;
    }

    public synchronized void bootstrapNetwork() {
        boostrapped = true;
        log.print("UVM: Bootstrapping network with "+ UVConfig.total_nodes+" nodes ("+ UVConfig.min_funding +" - "+ UVConfig.max_funding +")");
        log.print("UVM: Starting timechain thread...");
        new Thread(timechain,"timechain").start();

        for (int i =0;i<UVConfig.total_nodes; i++) {

            int max = UVConfig.max_funding/(int)1e6;
            int min = UVConfig.min_funding /(int)1e6;
            var funding = (int)1e6*(random.nextInt(max-min)+min);
            var n = new UVNode(this,"pk_"+i,"alias_"+i,funding);
            var behavior = new NodeBehavior(UVConfig.min_channels,UVConfig.min_channel_size,UVConfig.max_channel_size);
            n.setBehavior(behavior);
            nodeMap.put(n.getPubKey(),n);
        }

        bootstrap_latch = new CountDownLatch(UVConfig.total_nodes);
        log.print("Bootstrapping...");
        for (UVNode n : nodeMap.values()) {
            new Thread(n, "T_"+n.getPubKey()).start();
        }
        /*
        try {
            bootstrap_latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.print("End bootstrapping");
         */
    }

    public UVManager() {
        if (UVConfig.seed!=0) random.setSeed(UVConfig.seed);
        log.print("Initializing UVManager...");
        log.print(this.toString());
    }

    public void startServer(int port) {
        log.print("Starting UVM Server...");
        var uvm_server = new UVMServer(this,port);
        new Thread(uvm_server).start();
    }

    public UVNode getRandomNode() {
        var n = random.nextInt(nodeMap.size());
        var some_random_key = nodeMap.keySet().toArray()[n];
        return nodeMap.get(some_random_key);
    }

    public void testRandomEvent() {
        log.print("BOOTSTRAP LATCH:"+bootstrap_latch.getCount());
        /*
        var some_node = getRandomNode();
        var some_channel_id = some_node.getRandomChannel().getChannelId();
        var some_amount = random.nextInt(1000);
        some_amount *= 1000;

        log.print("TEST: pushing "+some_amount+ " sats from "+some_node.getPubKey()+" to "+some_channel_id);
        some_node.pushSats(some_channel_id,some_amount);

         */
    }

    public void generateRandomEvents(int n) {
        if (!boostrapped) {
            log.print("Network not bootstrapped: Cannot generate random events!");
            return;
        }
        for (int i=0;i<n;i++) {
            var some_node = getRandomNode();
            var some_channel_id = some_node.getRandomChannel().getChannelId();
            var some_amount = random.nextInt(1000);
            some_amount *= 1000;
            log.print("RANDOM EVENT: pushing "+some_amount+ " sats from "+some_node.getPubKey()+" to "+some_channel_id);
            some_node.pushSats(some_channel_id,some_amount);
        }
    }

    public void showNetwork() {

        for (UVNode n: nodeMap.values()) {
            System.out.println("----------------------------------------");
            System.out.println(n);
            for (UVChannel c:n.getUVChannels().values()) {
                System.out.println(c);
            }
            System.out.println("----------------------------------------");
        }

    }


    @Override
    public String toString() {
        return "UVManager{" +
                " config =" + UVConfig.printConfig()  +
                ", timechain=" + timechain +
                ", boostrapped=" + boostrapped +
                '}';
    }
}
