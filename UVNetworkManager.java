import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class UVNetworkManager {


    private CountDownLatch bootstrap_latch= new CountDownLatch(ConfigManager.getTotalNodes());
    private HashMap<String, UVNode> uvnodes = new HashMap<>();

    private String[] pubkeys_list;

    private Random random = new Random();
    private Timechain timechain;

    private boolean boostrap_started = false;
    private boolean bootstrap_completed = false;
    private String imported_rootnode_graph;
    private FileWriter logfile;
    private static Consumer<String> Log = System.out::println;

    private GlobalStats stats;


    /**
     *
     * @param args
     */
    public static void main(String[] args) {

        if (args.length == 1) {
            log("Loading configuration file:"+args[0]);
            ConfigManager.loadConfig(args[0]);
        }
        else {
            log("No config file provided, using defaults");
            ConfigManager.setDefaults();
        }

        var uvm = new UVNetworkManager();
    }

    /**
     * Initialize the logging functionality
     */
    private void initLog() {
        try {
            logfile = new FileWriter(ConfigManager.logfile);
        } catch (IOException e) {
            log("Cannot open logfile for writing:"+ ConfigManager.logfile);
            throw new RuntimeException(e);
        }
        Log = (s) ->  {
            try {
                logfile.write("\n["+timechain.getCurrent_block()+"]"+getClass().getName()+":"+s);
                logfile.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    /**
     * Constructor
     */
    public UVNetworkManager() {
        if (!ConfigManager.isInitialized()) ConfigManager.setDefaults();
        if (ConfigManager.getSeed() !=0) random.setSeed(ConfigManager.getSeed());
        initLog();

        timechain = new Timechain(ConfigManager.blocktime);

        log("Initializing UVManager...");
        log(this.toString());
        stats = new GlobalStats(this);
    }
    // TODO: not guaranteed to work perfectly
    public synchronized boolean resetUVM() {
        log("Resetting UVManager (experimental!)");
        if (isBootstrapStarted() && !isBootstrapCompleted()) {
            log("Cannot reset, bootstrap in progress. Please quit UVM");
            return false;
        }

        if (timechain!=null && timechain.isRunning()) timechain.stop();

        random = new Random();
        if (ConfigManager.getSeed() !=0) random.setSeed(ConfigManager.getSeed());
        timechain = new Timechain(ConfigManager.blocktime);
        bootstrap_latch= new CountDownLatch(ConfigManager.getTotalNodes());
        boostrap_started = false;
        bootstrap_completed = false;
        this.uvnodes = new HashMap<>();
        System.gc();
        return true;
    }


    /**
     * Bootstraps the Lightning Network from scratch starting from configuration file
     */
    public void bootstrapNetwork() {
        synchronized (this) {
            boostrap_started = true;
        }

        log("UVM: Bootstrapping network from scratch...");
        log("UVM: Starting timechain: "+timechain);
        new Thread(timechain,"timechain").start();

        log("UVM: deploying nodes, configuration: "+ ConfigManager.getConfig());
        int max = ConfigManager.getMaxFunding() /(int)1e6;
        int min = ConfigManager.getMinFunding() /(int)1e6;

        for (int i = 0; i< ConfigManager.getTotalNodes(); i++) {
            int funding;
            if (max==min) funding = (int)1e6*min;
            else
                funding = (int)1e6*(random.nextInt(max-min)+min);
            var n = new UVNode(this,""+i,"LN"+i,funding);
            var behavior = new NodeBehavior(ConfigManager.getMinChannels(), ConfigManager.getMinChannelSize(), ConfigManager.getMaxChannelSize());
            n.setBehavior(behavior);
            uvnodes.put(n.getPubKey(),n);
        }

        updatePubkeyList();

        log("Starting node threads...");
        ExecutorService bootexec = Executors.newFixedThreadPool(ConfigManager.getTotalNodes());
        for (UVNode n : uvnodes.values()) {
           bootexec.submit(n::bootstrapNode);
        }

        bootexec.shutdown();

        boolean term;

        try {
            term = bootexec.awaitTermination(600, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!term) log("Bootstrap timout! terminating...") ;
        else
            log("Bootstrap ended correctly");

        log("Launching p2p nodes services...");
        var p2p_executor = Executors.newScheduledThreadPool(ConfigManager.getTotalNodes());
        var delay = getTimechain().getBlockToMillisecTimeDelay(1);
        for (UVNode n : uvnodes.values()) {
            //p2p_executor.scheduleAtFixedRate(()->n.runP2PServices(),100,getTimechain().getBlockToMillisecTimeDelay(1),TimeUnit.MILLISECONDS);
            p2p_executor.scheduleAtFixedRate(()->n.runP2PServices(),100,delay,TimeUnit.MILLISECONDS);
        }
    }


    /**
     * Select a random node in the network using the list of the currently know pubkeys
     * @return a UVNode instance of the selected node
     */
    public UVNode getRandomNode() {
        // TODO: to change if nodes will be closed in future versions
        var n = random.nextInt(pubkeys_list.length);
        var some_random_key = pubkeys_list[n];
        return uvnodes.get(some_random_key);
    }

    /**
     * Generate some random channel transfers
     * @param n number of events to be generated
     */
    public void generateRandomEvents(int n) {
        if (!this.isBootstrapCompleted()) {
            log("Bootstrap non completed, Cannot generate random events!");
            return;
        }
        for (int i=0;i<n;i++) {
            var some_node = getRandomNode();
            var some_channel_id = some_node.getRandomChannel().getId();
            var some_amount = random.nextInt(1000);
            some_amount *= 1000;
            log("RANDOM EVENT: pushing "+some_amount+ " sats from "+some_node.getPubKey()+" to "+some_channel_id);
            some_node.pushSats(some_channel_id,some_amount);
        }
        log("Random events generation ended!");
    }


    /**
     *
     * @param json_file
     */
    public void importTopology(String json_file, String root_node) {
        JSONParser parser = new JSONParser();
        log("Beginning importing file "+json_file);
        try {
            Object obj = parser.parse(new FileReader(json_file));
            JSONObject jsonObject = (JSONObject) obj;

            JSONArray nodes = (JSONArray) jsonObject.get("nodes");
            for (Object node : nodes ) {
                JSONObject nodeObject = (JSONObject) node;
                String pub_key = (String) nodeObject.get("pub_key");
                String alias = (String) nodeObject.get("alias");

                var uvNode = new UVNode(this,pub_key,alias,999);
                uvnodes.put(pub_key,uvNode);
            }
            log("Node import ended, importing channels...");

            var root= uvnodes.get(root_node);

            JSONArray edges = (JSONArray) jsonObject.get("edges");
            for (Object edge : edges) {
                JSONObject edgeObject = (JSONObject) edge;
                String channel_id = (String) edgeObject.get("channel_id");
                String node1_pub = (String) edgeObject.get("node1_pub");
                String node2_pub = (String) edgeObject.get("node2_pub");
                var uvnode1 = uvnodes.get(node1_pub);
                var uvnode2 = uvnodes.get(node2_pub);
                int capacity = Integer.parseInt((String) edgeObject.get("capacity"));

                UVChannel ch = new UVChannel(channel_id, uvnode1,uvnode2,capacity);

                uvnode1.configureChannel(ch);
                uvnode2.configureChannel(ch);
                uvnode1.getChannelGraph().addChannel(ch);
                uvnode2.getChannelGraph().addChannel(ch);

                root.getChannelGraph().addChannel(ch);
            }
            log("Import completed");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /***************************************************************************************
     * Getters/Setters/Helpers
     */

    String getStatusString() {
        StringBuilder s = new StringBuilder();
        s.append("\n-------------------------------------------------------------");
        s.append("\nConfiguration: ").append(ConfigManager.getConfig());
        s.append("\nTimechain: ").append(timechain);
        s.append("\nBootstrap completed: ").append(isBootstrapCompleted());

        if (!isBootstrapCompleted() && isBootstrapStarted())
            s.append("\nBootstrapped in progress for ").append(bootstrap_latch.getCount()).append(" of ").append(ConfigManager.getTotalNodes());
        s.append("\n-------------------------------------------------------------");
        return s.toString();
    }

    /**
     *
     * @return
     */
    public synchronized boolean isBootstrapCompleted() {
        if (bootstrap_completed) return true;

        if (bootstrap_latch.getCount()==0) {
            bootstrap_completed = true;
            return true;
        }
        return false;
    }

    /**
     *
     * @return
     */
    public synchronized boolean isBootstrapStarted() {
        return boostrap_started;
    }

    /**
     *
     * @param pubkey
     * @return
     */
    public LNode getLNode(String pubkey) {
        return uvnodes.get(pubkey);
    }

    /**
     *
     * @return
     */
    public HashMap<String, UVNode> getUVNodes(){

        return this.uvnodes;
    }

    /**
     *
     * @return
     */
    public Timechain getTimechain() {
        return timechain;
    }

    /**
     * Updates the list the currently known pubkeys, to be used for genel sim purposes
     */
    private void updatePubkeyList() {
        pubkeys_list = new String[uvnodes.keySet().size()];
        pubkeys_list = uvnodes.keySet().toArray(pubkeys_list);
    }


    public GlobalStats getStats() {
        return stats;
    }

    public CountDownLatch getBootstrapLatch() {
        return bootstrap_latch;
    }

    @Override
    public String toString() {
        return getStatusString();
    }

    /**
     *
     * @param s
     */
    static void log(String s) {
        Log.accept(s);
    }

    /**
     * Save the current network status to file
     * @param file destination file
     */
    public void saveStatus(String file) {

        if (isBootstrapStarted() && !isBootstrapCompleted())  {
            Log.accept("Bootstrap incomplete, cannot save");
            return;
        }
        log("Stopping timechain");
        this.getTimechain().stop();

        try (var f = new ObjectOutputStream(new FileOutputStream(file))){

            f.writeObject(ConfigManager.getConfig());
            f.writeInt(uvnodes.size());

            for (UVNode n: uvnodes.values())
                f.writeObject(n);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        log("End saving ");
    }

    /**
     * Load network status from file
     * @param file
     * @return False if is not possible to read the file
     */
    public boolean loadStatus(String file) {
        uvnodes.clear();

        try (var s = new ObjectInputStream(new FileInputStream(file))) {

            var config = (Properties)s.readObject();
            ConfigManager.setConfig(config);

            int num_nodes = s.readInt();
            for (int i=0;i<num_nodes;i++) {
                UVNode n = (UVNode) s.readObject();
                n.setUVM(this);
                uvnodes.put(n.getPubKey(),n);
            }

            // must restore channel partners, can be done only after all nodes have been restored in UVM
            for (UVNode n: uvnodes.values()) {
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
}
