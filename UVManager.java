import java.io.*;
import java.util.HashMap;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class UVManager {

    ExecutorService bootexec;
    CountDownLatch bootstrap_latch= new CountDownLatch(ConfigManager.total_nodes);
    private HashMap<String, UVNode> UVnodes = new HashMap<>();

    private String[] pubkeys_list;

    private Random random = new Random();
    private Timechain timechain;

    private boolean boostrap_started = false;
    private boolean bootstrap_completed = false;
    private FileWriter logfile;
    static Log log = System.out::println;


    /**
     * Save the current network status to file
     * @param file destination file
     */
    public void save(String file) {

        if (bootstrapStarted() && !bootstrapCompleted())  {
            log.print("Bootstrap incomplete, cannot save");
            return;
        }
        log.print("Stopping timechain");
        this.getTimechain().stop();

        try (var f = new ObjectOutputStream(new FileOutputStream(file));){

           f.writeInt(UVnodes.size());

           for (UVNode n: UVnodes.values())
                f.writeObject(n);
            f.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log.print("End saving ");
    }

    /**
     * Load network status from file
     * @param file
     * @return False if is not possible to read the file
     */
    public boolean load(String file) {
        UVnodes.clear();

        try (var s = new ObjectInputStream(new FileInputStream(file))) {

            int num_nodes = s.readInt();
            for (int i=0;i<num_nodes;i++) {
                UVNode n = (UVNode) s.readObject();
                n.setUVM(this);
                UVnodes.put(n.getPubKey(),n);
            }

            // must restore channel partners, can be done only after all nodes have been restored in UVM
            for (UVNode n:UVnodes.values()) {
                n.restorePersistentData();
            }
            updatePubkeyList();
            bootstrap_completed = true;

        } catch (IOException e) {
            return false;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    public static void main(String[] args) {

        if (args.length == 1) {
            log.print("Loading configuration file:"+args[0]);
            ConfigManager.loadConfig(args[0]);
        }
        else {
            log.print("No config file provided, using defaults");
            ConfigManager.setDefaults();
        }

        var uvm = new UVManager();
    }

    private void initLog() {
        try {
            logfile = new FileWriter(ConfigManager.logfile);
        } catch (IOException e) {
            log.print("Cannot open logfile for writing:"+ ConfigManager.logfile);
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
    }

    /**
     * Constructor
     */
    public UVManager() {
        initLog();

        timechain = new Timechain(ConfigManager.blocktiming);

        if (ConfigManager.seed!=0) random.setSeed(ConfigManager.seed);
        log.print("Initializing UVManager...");
        log.print(this.toString());
        startServer(ConfigManager.server_port);
    }
    // TODO: not guaranteed to work perfectly
    public synchronized void resetUVM() {
        log.print("Resetting UVManager (experimental!)");
        random = new Random();
        if (ConfigManager.seed!=0) random.setSeed(ConfigManager.seed);
        timechain = new Timechain(ConfigManager.blocktiming);
        bootstrap_latch= new CountDownLatch(ConfigManager.total_nodes);
        boostrap_started = false;
        bootstrap_completed = false;
        this.UVnodes.clear();
        System.gc();
    }

    /**
     * Start a server listening to client to issue UVM commands
     * @param port
     */
    public void startServer(int port) {
        log.print("Starting UVM Server...");
        var uvm_server = new UVMServer(this,port);
        new Thread(uvm_server).start();
    }

    public synchronized boolean bootstrapCompleted() {
        if (bootstrap_completed) return true;

        if (bootstrap_latch.getCount()==0) {
            bootstrap_completed = true;
            return true;
        }
        return false;
    }

    public synchronized boolean bootstrapStarted() {
        return boostrap_started;
    }

    public HashMap<String, UVNode> getUVnodes(){

        return this.UVnodes;
    }

    public Timechain getTimechain() {
        return timechain;
    }

    /**
     * Updates the list the currently known pubkeys, to be used for genel sim purposes
     */
    private void updatePubkeyList() {
        pubkeys_list = new String[UVnodes.keySet().size()];
        pubkeys_list = UVnodes.keySet().toArray(pubkeys_list);
    }

    @SuppressWarnings("CommentedOutCode")
    /**
     * Bootstraps the Lightning Network from scratch starting from configuration file
     */
    public void bootstrapNetwork() {
        synchronized (this) {
            boostrap_started = true;
        }

        log.print("UVM: Bootstrapping network...");
        log.print("UVM: Starting timechain: "+timechain);
        new Thread(timechain,"timechain").start();

        log.print("UVM: deploying nodes, configuration: "+ ConfigManager.printConfig());
        int max = ConfigManager.max_funding/(int)1e6;
        int min = ConfigManager.min_funding /(int)1e6;

        for (int i = 0; i< ConfigManager.total_nodes; i++) {
            int funding;
            if (max==min) funding = (int)1e6*min;
            else
                funding = (int)1e6*(random.nextInt(max-min)+min);
            var n = new UVNode(this,"pk_"+i,"LN"+i,funding);
            var behavior = new NodeBehavior(ConfigManager.min_channels, ConfigManager.min_channel_size, ConfigManager.max_channel_size);
            n.setBehavior(behavior);
            UVnodes.put(n.getPubKey(),n);
        }

        updatePubkeyList();

        log.print("Starting node threads...");
        bootexec = Executors.newFixedThreadPool(ConfigManager.total_nodes);
        for (UVNode n : UVnodes.values()) {
           bootexec.submit(()->n.bootstrapNode());
        }

        bootexec.shutdown();

        try {
            bootexec.awaitTermination(600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        log.print("Terminated bootstrap");
        log.print("Stopping timechain");
        this.getTimechain().stop();
    }


    /**
     * Select a random node in the network using the list of the currently know pubkeys
     * @return a UVNode instance of the selected node
     */
    public UVNode getRandomNode() {
        // TODO: to change if nodes will be closed in future versions
        var n = random.nextInt(pubkeys_list.length);
        var some_random_key = pubkeys_list[n];
        return UVnodes.get(some_random_key);
    }

    public UVNode getDeterministicNode(int n) {
       var some_key = pubkeys_list[n];
       return UVnodes.get(some_key);

    }

    /**
     * To be used for testing purposes
     */
    @SuppressWarnings("CommentedOutCode")
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

    /**
     * Generate some random channel transfers
     * @param n number of events to be generated
     */
    public void generateRandomEvents(int n) {
        if (!this.bootstrapCompleted()) {
            log.print("Bootstrap non completed, Cannot generate random events!");
            return;
        }
        for (int i=0;i<n;i++) {
            var some_node = getRandomNode();
            var some_channel_id = some_node.getRandomChannel().getId();
            var some_amount = random.nextInt(1000);
            some_amount *= 1000;
            log.print("RANDOM EVENT: pushing "+some_amount+ " sats from "+some_node.getPubKey()+" to "+some_channel_id);
            some_node.pushSats(some_channel_id,some_amount);
        }
        log.print("Random events generation ended!");
    }


    private String getStatus() {
        StringBuilder s = new StringBuilder();
        s.append("\n-------------------------------------------------------------");
        s.append("\nConfiguration: ").append(ConfigManager.printConfig());
        s.append("\nTimechain: ").append(timechain);
        s.append("\nBootstrap completed: ").append(bootstrapCompleted());

        if (!bootstrapCompleted() && bootstrapStarted())
            s.append("\nBootstrapped in progress for ").append(bootstrap_latch.getCount()).append(" of ").append(ConfigManager.total_nodes);
        s.append("\n-------------------------------------------------------------");
        return s.toString();
    }



    @Override
    public String toString() {
        return getStatus();
    }
}
