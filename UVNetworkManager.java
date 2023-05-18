import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class UVNetworkManager {

    private UVConfig uvConfig;
    private CountDownLatch bootstrap_latch;
    private HashMap<String, UVNode> uvnodes;

    private String[] pubkeys_list;

    private Random random;
    private UVTimechain UVTimechain;

    private boolean bootstrap_started = false;
    private boolean bootstrap_completed = false;
    private String imported_rootnode_graph;
    private FileWriter logfile;
    public static Consumer<String> Log = System.out::println;

    private final GlobalStats stats;
    private ScheduledExecutorService p2pExecutor;


    private final ArrayList<String> aliasNames = new ArrayList<>();

    public UVConfig getConfig() {
        return uvConfig;
    }

    /**
     * Initialize the logging functionality
     */
    private void initLog() {
        try {
            logfile = new FileWriter(""+ uvConfig.getStringProperty("logfile"));
        } catch (IOException e) {
            log("Cannot open logfile for writing:"+ uvConfig.getStringProperty("lofile"));
            throw new RuntimeException(e);
        }
        Log = (s) ->  {
            try {

                logfile.write("\n[timechain "+ UVTimechain.getCurrentBlock()+"]:"+s);
                logfile.flush();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        };
    }

    private void loadAliasNames() {
        try {
            Scanner s = new Scanner(new FileReader("aliases.txt"));
            while (s.hasNextLine()) aliasNames.add(s.nextLine());

        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String getAlias() {
        if (aliasNames.size()<1) return "_";
        var i = random.nextInt(0,aliasNames.size());
        String alias = aliasNames.get(i);
        aliasNames.remove(i);
        return alias;
    }

    /**
     * Constructor
     */
    public UVNetworkManager(UVConfig config) {
        this.uvConfig = config;
        if (!uvConfig.isInitialized()) uvConfig.setDefaults();
        random = new Random();
        if (uvConfig.getIntProperty("seed") !=0) random.setSeed(uvConfig.getIntProperty("seed"));
        initLog();

        this.uvnodes = new HashMap<>();
        UVTimechain = new UVTimechain(uvConfig.getIntProperty("blocktime"));

        log(new Date() +":Initializing UVManager...");
        stats = new GlobalStats(this);

        loadAliasNames();
    }

    /**
     * Reset the UV Network Manager
     * @return
     */
    public synchronized boolean resetUVM() {
        System.out.println("Resetting UVManager (experimental!)");
        if (isBootstrapStarted() && !isBootstrapCompleted()) {
            log("Cannot reset, bootstrap in progress. Please quit UVM");
            return false;
        }

        if (UVTimechain !=null && isTimechainRunning())  {
            setTimechainRunning(false);
            stopP2PNetwork();
        }

        random = new Random();
        if (uvConfig.getIntProperty("seed") !=0) random.setSeed(uvConfig.getIntProperty("seed"));
        UVTimechain = new UVTimechain(uvConfig.getIntProperty("blocktime"));
        bootstrap_started = false;
        bootstrap_completed = false;
        this.uvnodes = new HashMap<>();


        boolean no_timeout = true;

        if (p2pExecutor!=null) {
            p2pExecutor.shutdown();
            try {
                no_timeout = p2pExecutor.awaitTermination(600,TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        if (!no_timeout) {
            log("WARNING: timeout");
            System.out.println("TIMEOUT!");
        }
        p2pExecutor = null;

        System.gc();
        return true;
    }

    /**
     * Bootstraps the Lightning Network from scratch starting from configuration file
     */
    public void bootstrapNetwork() {

        Thread.currentThread().setName("Bootstrap");

        var startTime = new Date();
        bootstrap_started = true;

        log(startTime.toString()+": Bootstrapping network from scratch...");
        bootstrap_latch = new CountDownLatch(uvConfig.getIntProperty("bootstrap_nodes"));

        log("UVM: deploying nodes, configuration: "+ uvConfig);

        for (int i = 0; i< uvConfig.getIntProperty("bootstrap_nodes"); i++) {
            var random_profile = uvConfig.getRandomProfile();
            int max_capacity = Integer.parseInt(random_profile.get("max_funding")) /(int)1e3;
            int min_capacity = Integer.parseInt(random_profile.get("min_funding")) /(int)1e3;
            int funding;
            if (max_capacity==min_capacity) funding = (int)1e3*min_capacity;
            else
                funding = (int)1e3*(random.nextInt(max_capacity-min_capacity)+min_capacity);

            var node = new UVNode(this,"pk"+i,getAlias(),funding,random_profile);
            uvnodes.put(node.getPubKey(),node);
        }

        updatePubkeyList();

        log("UVM: Starting timechain: "+ UVTimechain);
        setTimechainRunning(true);
        log("Starting node threads...");
        var bootexec = Executors.newFixedThreadPool(uvConfig.getIntProperty("bootstrap_nodes"));
        for (UVNode uvNode : uvnodes.values()) {
           bootexec.submit(()->bootstrapNode(uvNode));
        }

        try {
            System.gc();
            bootstrap_latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        bootexec.shutdown();
        boolean term;

        try {
            term = bootexec.awaitTermination(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!term)  {
            log("Bootstrap timeout! terminating...") ;
            System.out.println("Bootstrap timeout! terminating...");
        }
        else {
            var after = new Date();
            var duration = after.getTime()-startTime.getTime();
            log(after+":Bootstrap Ended. Duration (ms):"+duration);
        }

    }

    /**
     * If not yet started, schedule the p2p threads to be executed periodically for each node
     */
    private void startP2PNetwork() {
        log("Launching p2p nodes services...");
        // start p2p actions around every block
        if (p2pExecutor==null) {
            log("Initializing p2p scheduled executor...");
            p2pExecutor = Executors.newScheduledThreadPool(uvConfig.getIntProperty("bootstrap_nodes"));
        }
        int i = 0;
        for (UVNode n : uvnodes.values()) {
            n.setP2PServices(true);
            n.p2pHandler = p2pExecutor.scheduleAtFixedRate(n::runServices,0, uvConfig.getIntProperty("p2p_period"),TimeUnit.MILLISECONDS);
        }
    }
    public void stopP2PNetwork() {
        log("Stopping p2p nodes services...");

        for (UVNode n : uvnodes.values()) {
            n.setP2PServices(false);
            n.p2pHandler.cancel(false);
        }

        log("P2P Services stopped");
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
     *
     */
    public Optional<LNChannel> getChannelFromNodes(String pub1, String pub2) {

        var n1 = getNode(pub1);
        var n2 = getNode(pub2);

        for (LNChannel c: n1.getLNChannelList()) {
            if (c.getNode1PubKey().equals(pub2) || c.getNode2PubKey().equals(pub2))
                return Optional.of(c);
        }
        return Optional.empty();
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

                var uvNode = new UVNode(this,pub_key,alias,12345678,uvConfig.getProfiles().get("default"));
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
                int capacity = Integer.parseInt((String) edgeObject.get("capacity"));

                UVChannel ch = new UVChannel(channel_id, node1_pub,node2_pub,capacity,0,0);

                var uvnode1 = uvnodes.get(node1_pub);
                var uvnode2 = uvnodes.get(node2_pub);
                uvnode1.configureChannel(ch);
                uvnode2.configureChannel(ch);
                uvnode1.getChannelGraph().addLNChannel(ch);
                uvnode2.getChannelGraph().addLNChannel(ch);

                root.getChannelGraph().addLNChannel(ch);
            }
            log("Import completed");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    /***************************************************************************************
     * Getters/Setters/Helpers
     */

    public synchronized boolean isBootstrapCompleted() {
        if (bootstrap_completed) return true;
        if (bootstrap_latch==null) return false;

        if (bootstrap_latch.getCount()==0) {
            bootstrap_completed = true;
            return true;
        }
        return false;
    }

    public synchronized boolean isBootstrapStarted() {
        return bootstrap_started;
    }


    public HashMap<String, UVNode> getUVNodeList(){
        return this.uvnodes;
    }

    public UVNode getNode(String pubkey) {
        UVNode node = uvnodes.get(pubkey);
        while (node==null) {
            var e = new RuntimeException("Cannot find node "+pubkey);
            e.printStackTrace();
            System.out.print("Enter valide node pubkey:");
            var p = new Scanner(System.in).nextLine();
            node = uvnodes.get(p);
        }
        return node;
    }

    public LNode getLNode(String pubkey) {
        return uvnodes.get(pubkey);
    }
    public P2PNode getP2PNode(String pubkey) {
        return uvnodes.get(pubkey);
    }

    public UVTimechain getTimechain() {
        return UVTimechain;
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
        setTimechainRunning(false);

        try (var f = new ObjectOutputStream(new FileOutputStream(file))){

            f.writeObject(uvConfig);
            f.writeObject(getTimechain());
            f.writeInt(uvnodes.size());

            for (UVNode n: uvnodes.values()) f.writeObject(n);
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
        setTimechainRunning(false);
        uvnodes.clear();

        try (var s = new ObjectInputStream(new FileInputStream(file))) {

            var config = (Properties)s.readObject();
            this.UVTimechain = (UVTimechain)s.readObject();
            uvConfig.setConfig(config);

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

    public synchronized boolean isTimechainRunning() {
        return getTimechain().isRunning();
    }

    public synchronized void setTimechainRunning(boolean running) {
        getTimechain().setRunning(running);
        if (running) {
            new Thread(getTimechain(),"Timechain").start();
            new Thread(this::startP2PNetwork,"P2P").start();
            log("Starting timechain!");
        }
        else {
            getTimechain().setRunning(false);
            stopP2PNetwork();
            log("Stopping timechain!");
        }
    }

    private void waitForBlocks(int blocks) {
        if (blocks==0) return;
        var ready_to_go = getTimechain().getTimechainLatch(blocks);
        try {
            ready_to_go.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     *
     */
    public void bootstrapNode(UVNode node) {

        ///----------------------------------------------------------
        var duration = ThreadLocalRandom.current().nextInt(0, uvConfig.getIntProperty("bootstrap_duration"));

        // Notice: no way of doing this deterministically, timing will be always in race condition with other threads
        // Also: large durations with short p2p message deadline can cause some node no to consider earlier node messages

        log("BOOTSTRAPPING "+node.getPubKey()+ " - waiting duration blocks: "+duration);

        waitForBlocks(duration);

        var profile = node.getProfile();
        int max_channels = Integer.parseInt(profile.get("max_channels"));
        int min_channels = Integer.parseInt(profile.get("min_channels"));
        final int target_channel_number = ThreadLocalRandom.current().nextInt(max_channels-min_channels)+min_channels;

        int max_ch_size = Integer.parseInt(profile.get("max_channel_size"));
        int min_ch_size = Integer.parseInt(profile.get("min_channel_size"));

        while (node.getLNChannelList().size() < target_channel_number) {
            // not mandatory, just to have different block timings in openings
            waitForBlocks(1);

            if (node.getOnchainLiquidity() <= min_ch_size) {
                log("Node "+node.getPubKey()+": No more onchain balance for opening channels of min size "+min_ch_size);
                break; // exit while loop
            }

            var peerPubkey = getRandomNode().getPubKey();

            // checking problems with peer
            if (peerPubkey.equals(node.getPubKey())) continue;
            if (node.hasChannelWith(peerPubkey)) continue;
            if (node.ongoingOpeningRequestWith(peerPubkey)) continue;

            log("Node "+node.getPubKey()+" Trying to open a channel with "+peerPubkey);

            int max = Math.min(max_ch_size, node.getOnChainBalance());
            int min = min_ch_size;
            var newChannelSize = ((ThreadLocalRandom.current().nextInt(min,max+1))/1000)*1000;

            if (node.getOnchainLiquidity()< newChannelSize) {
                continue;
            }

            node.openChannel(peerPubkey,newChannelSize);
        } // while

        log(node.getPubKey()+":Bootstrap Completed");
        getBootstrapLatch().countDown();
    }


    public void sendMessageToNode(String pubkey, P2PMessage message) {
        var uvnode = getNode(pubkey);
        uvnode.receiveMessage(message);
    }

    public void generateInvoiceEvents(int n_events, int max_amount, int max_fees) {
        if (!isBootstrapCompleted()) {
            System.out.println("ERROR: must execute bootstrap or load/import a network!");
            return;
        }
        log("Generating " + n_events + " invoice events" + "(max amt:" + max_amount + ",max_fees" + max_fees + ")");

        for (int n = 0; n < n_events; n++) {
            var sender = getRandomNode();
            var dest = getRandomNode();
            int amount = new Random().nextInt(max_amount);
            var invoice = dest.generateInvoice(amount);
            sender.processInvoice(invoice, max_fees);
        }
    }
}
