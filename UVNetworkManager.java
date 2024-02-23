import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;

import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class UVNetworkManager {

    private UVConfig uvConfig;
    private CountDownLatch bootstrap_latch;
    private HashMap<String, UVNode> uvnodes;

    private ArrayList<String> pubkeys_list;

    private Random random;
    private UVTimechain UVTimechain;

    private boolean bootstrap_started = false;
    private boolean bootstrap_completed = false;
    private String imported_rootnode_graph;
    public static Consumer<String> Log = System.out::println;

    private final GlobalStats stats;

    private final ArrayList<String> aliasNames = new ArrayList<>();

    public UVConfig getConfig() {
        return uvConfig;
    }
    private static FileWriter logfile;

    private ExecutorService invoiceExecutor;
    private ScheduledExecutorService p2pExecutor;

    private int invoice_thread_pool_size;
    private int bootstrap_thread_pool_size;
    private int p2p_thread_pool_size;
    private int bootstraps_running;
    private int bootstraps_ended;


    private void adjustThreadPoolSizes() {
        // get it from ulimit -a
        int max_threads = uvConfig.getIntProperty("max_threads");

        print_log("Determing appropriate pool sizes... (max threads " + max_threads + ")");

        bootstrap_thread_pool_size = uvConfig.getIntProperty("bootstrap_nodes");
        p2p_thread_pool_size = bootstrap_thread_pool_size;

        // during the bootstrap, 2*nodes threads could be potentially executed
        // due to bootstrap + p2p

        int peek_bootstrap = bootstrap_thread_pool_size + p2p_thread_pool_size;
        if (peek_bootstrap> max_threads) {
            print_log("WARNING: bootstrap and p2p overall pool sizes "+peek_bootstrap+ " execeeds max threads...");
            print_log("       > Reducing bootstrap and p2p threads pool sizes to "+max_threads/2);
            bootstrap_thread_pool_size = max_threads/2;
            p2p_thread_pool_size = max_threads/2;
        }

        // when processing invoices, only the p2p services are running
        invoice_thread_pool_size = max_threads - p2p_thread_pool_size;
        print_log("Setting invoice pool size to "+invoice_thread_pool_size);

    }

    public int getBootstrapsRunning() {
        return bootstraps_running;
    }

    public int getBootstrapsEnded() {
        return bootstraps_ended;
    }

    private void loadAliasNames() {
        try {
            Scanner s = new Scanner(new FileReader("aliases.txt"));
            while (s.hasNextLine()) aliasNames.add(s.nextLine());

        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public synchronized String createAlias() {
        if (aliasNames.size()<1) return "no alias";
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
        random = new Random();
        if (uvConfig.getIntProperty("seed") !=0) random.setSeed(uvConfig.getIntProperty("seed"));

        try {
            logfile = new FileWriter(uvConfig.getStringProperty("logfile"));
        } catch (IOException e) {
            log("Cannot open logfile for writing:"+ uvConfig.getStringProperty("lofile"));
            throw new RuntimeException(e);
        }

        this.uvnodes = new HashMap<>();
        UVTimechain = new UVTimechain(uvConfig.getIntProperty("blocktime"),this);

        print_log(new Date() +":Initializing UVManager...");
        stats = new GlobalStats(this);
        adjustThreadPoolSizes();
        loadAliasNames();
    }

    /**
     * Reset the UV Network Manager
     * @return
     */
    public synchronized boolean resetUVM() {
        if (isBootstrapStarted() && !isBootstrapCompleted()) {
            print_log("Cannot reset, bootstrap in progress. Please quit UVM");
            return false;
        }

        if (UVTimechain !=null && isTimechainRunning())  {
            setTimechainRunning(false);
            stopP2PNetwork();
        }

        random = new Random();
        if (uvConfig.getIntProperty("seed") !=0) random.setSeed(uvConfig.getIntProperty("seed"));
        UVTimechain = new UVTimechain(uvConfig.getIntProperty("blocktime"),this);
        bootstrap_started = false;
        bootstrap_completed = false;
        bootstraps_ended = 0;
        bootstraps_running = 0;
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
            print_log("TIMEOUT!");
        }
        p2pExecutor = null;

        System.gc();
        print_log("UVManager reset complete");
        return true;
    }

    /**
     * Bootstrapping is a process that lets an LN network emerge from the definition of each node’s behavioral policies,
     * such as initial onchain  funding, number of channels to open, along with their sizes and fees.
     * While it does not necessarily replicate the currently existing network,
     * it is useful to investigate the emergent complexity of different distributed local node policies.
     */
    public void bootstrapNetwork() {

        Thread.currentThread().setName("Bootstrap");
        var startTime = new Date();
        print_log(startTime +": Bootstrapping network from scratch...");

        final int n_nodes = uvConfig.getIntProperty("bootstrap_nodes");
        if (pubkeys_list==null) pubkeys_list = new ArrayList<>(n_nodes);

        bootstrap_started = true;
        bootstrap_latch = new CountDownLatch(n_nodes);
        bootstraps_running = 0;
        bootstraps_ended = 0;

        print_log("UVM: deploying nodes, configuration: "+ uvConfig);

        setTimechainRunning(true);
        startP2PNetwork();

        // given n node, we can expect a peak of 2*n threads during bootstrap, since each node with start its p2p services thread
         // the code below just tries to force a limit for the bootstrapping threads to avoid going beyond the limit

        int duration = uvConfig.getIntProperty("bootstrap_period");
        int median = uvConfig.getIntProperty("bootstrap_time_median");
        int average = uvConfig.getIntProperty("bootstrap_time_average");

        int[] boot_times = DistributionGenerator.generateIntSamples(n_nodes,1,duration,median,average);
        Arrays.sort(boot_times);

        ExecutorService bootstrapExecutor = Executors.newFixedThreadPool(bootstrap_thread_pool_size, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "Bootstrap-" + counter.incrementAndGet());
            }
        });


        print_log("Creating "+n_nodes+" node instancies...");
        for (int n=0;n<n_nodes;n++) {
            var profile = uvConfig.getRandomProfile();
            int max_capacity = profile.getIntAttribute("max_funding")/(int)1e3;
            int min_capacity = profile.getIntAttribute("min_funding") /(int)1e3;
            int funding = (int)1e3*(random.nextInt(min_capacity,max_capacity+1));
            var node = new UVNode(this,"pk"+n, createAlias(),funding,profile);
            uvnodes.put(node.getPubKey(),node);
        }

        int last_issue_block = 0;
        try {
            int current_index = 0;
            for (var node:uvnodes.values()) {
                int wait_for_next =boot_times[current_index]-last_issue_block;
                if (wait_for_next>0) {
                    waitForBlocks(wait_for_next);
                }
                bootstraps_running++;
                bootstrapExecutor.submit(()->bootstrapNode(node));
                last_issue_block = getTimechain().getCurrentBlock();
                current_index++;
                pubkeys_list.add(node.getPubKey());
            }
        }
        catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException(e);
        }

        try {
            System.gc();
            bootstrap_latch.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        bootstrapExecutor.shutdown();
        boolean term;

        try {
            term = bootstrapExecutor.awaitTermination(120, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        if (!term)  {
            print_log("Bootstrap timeout! terminating...");
        }
        else {
            var after = new Date();
            var d = after.getTime()-startTime.getTime();
            print_log(after+":Bootstrap Ended. Duration (ms):"+d);
        }

        System.gc();
    }

    /**
     * If not yet started, schedule the p2p threads to be executed periodically for each node
     */
    public void startP2PNetwork() {
        print_log("Launching p2p node service threads ...");
        // start p2p actions around every block
        if (p2pExecutor==null) {
            print_log("Initializing p2p scheduled executor...");
            p2pExecutor = Executors.newScheduledThreadPool(p2p_thread_pool_size, new ThreadFactory() {
                private final AtomicInteger counter = new AtomicInteger(0);
                @Override
                public Thread newThread(Runnable r) {
                    return new Thread(r, "p2p_service-" + counter.incrementAndGet());
                }
            });
        }
        int i = 0;
        for (UVNode n : uvnodes.values()) {
            n.setP2PServices(true);
            n.p2pHandler = p2pExecutor.scheduleAtFixedRate(n::runServices,0, uvConfig.getIntProperty("p2p_period"),TimeUnit.MILLISECONDS);
        }
    }
    public void stopP2PNetwork() {
        print_log("Stopping p2p nodes services...");

        for (UVNode n : uvnodes.values()) {
            n.setP2PServices(false);
            n.p2pHandler.cancel(false);
        }

        print_log("P2P Services stopped");
    }

    /**
     * Select a random node in the network using the list of the currently know pubkeys
     * @return a UVNode instance of the selected node
     */
    private UVNode getRandomNode() {
        // TODO: to change if nodes will be closed in future versions
        var n = random.nextInt(pubkeys_list.size());
        var some_random_key = pubkeys_list.get(n);
        return uvnodes.get(some_random_key);
    }

    /**
     *
     */
    public Optional<LNChannel> getChannelFromNodes(String pub1, String pub2) {
        // as the name suggests, the usage of this method assumes that at max ONE channel
        // is present between nodes. It should never be used when multiple channels
        // are present between two nodes, so this incoherent condition should be checked

        var n1 = getNode(pub1);
        var n2 = getNode(pub2);

        Optional<LNChannel> channel = Optional.empty();
        int found_channels = 0;

        for (LNChannel c: n1.getLNChannelList()) {
            if (c.getNode1PubKey().equals(pub2) || c.getNode2PubKey().equals(pub2)) {
                channel = Optional.of(c);
                found_channels++;
            }
        }

        if (found_channels>1) {
            throw new IllegalStateException(" Found multiple channels between "+n1.getPubKey()+" and "+n2.getPubKey());
        }

        return channel;
    }

    /**
     * Generate some random channel transfers
     * @param n number of events to be generated
     */
    public void generateRandomEvents(int n) {
        if (!this.isBootstrapCompleted()) return;
        for (int i=0;i<n;i++) {
            var some_node = getRandomNode();
            var some_channel_id = some_node.getRandomChannel().getId();
            var some_amount = random.nextInt(1000);
            some_amount *= 1000;
            log("RANDOM EVENT: pushing "+some_amount+ " sats from "+some_node.getPubKey()+" to "+some_channel_id);
            some_node.pushSats(some_channel_id,some_amount);
        }
    }


    /**
     *
     * @param json_file
     */
    public void importTopology(String json_file, String root_node) {
        JSONParser parser = new JSONParser();
        print_log("Beginning importing file "+json_file);
        try {
            Object obj = parser.parse(new FileReader(json_file));
            JSONObject jsonObject = (JSONObject) obj;

            JSONArray nodes = (JSONArray) jsonObject.get("nodes");
            for (Object node : nodes ) {
                JSONObject nodeObject = (JSONObject) node;
                String pub_key = (String) nodeObject.get("pub_key");
                String alias = (String) nodeObject.get("alias");

                var uvNode = new UVNode(this,pub_key,alias,12345678,uvConfig.getNodeProfiles().get("default"));
                uvnodes.put(pub_key,uvNode);
            }
            print_log("Node import ended, importing channels...");

            var root= uvnodes.get(root_node);

            JSONArray edges = (JSONArray) jsonObject.get("edges");
            for (Object edge : edges) {
                JSONObject edgeObject = (JSONObject) edge;
                String channel_id = (String) edgeObject.get("channel_id");
                String node1_pub = (String) edgeObject.get("node1_pub");
                String node2_pub = (String) edgeObject.get("node2_pub");
                int capacity = Integer.parseInt((String) edgeObject.get("capacity"));

                // TODO: get real init direction
                UVChannel ch = new UVChannel(channel_id,node1_pub,node2_pub,capacity,0,0,true);

                var uvnode1 = uvnodes.get(node1_pub);
                var uvnode2 = uvnodes.get(node2_pub);
                uvnode1.configureChannel(ch);
                uvnode2.configureChannel(ch);
                uvnode1.getChannelGraph().addLNChannel(ch);
                uvnode2.getChannelGraph().addLNChannel(ch);

                root.getChannelGraph().addLNChannel(ch);
            }
            print_log("Import completed");

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
    private void refreshPubkeyList() {
        pubkeys_list.clear();
        pubkeys_list.addAll(uvnodes.keySet());
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
    public void log(String s) {
        try {

            logfile.write("\n[block "+ UVTimechain.getCurrentBlock()+"]:"+s);
            logfile.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void print_log(String s) {
        System.out.println(s);
        log(s);
    }

    /**
     * Save the current network status to file
     * @param file destination file
     */
    public void saveStatus(String file) {
        if (isBootstrapStarted() && !isBootstrapCompleted())  {
            print_log("Bootstrap incomplete, cannot save");
            return;
        }

        print_log("Checking queues before saving...");
        boolean queue_empty = true;
        for (UVNode n: uvnodes.values()) {
           if (!n.checkQueuesStatus())  {
               print_log("WARNING queues not empty in "+n.getPubKey());
               n.showQueuesStatus();
               queue_empty = false;
           }
        }
        if (!queue_empty) {
            print_log("Cannot save: queues still not empty.");
            return;
        }
        print_log("Stopping timechain");
        setTimechainRunning(false);
        stopP2PNetwork();

        try (var f = new ObjectOutputStream(new FileOutputStream(file))){

            print_log("Saving config...");
            f.writeObject(uvConfig);
            print_log("Saving timechain...");
            f.writeObject(getTimechain());
            print_log("Saving UVNodes...");
            f.writeInt(uvnodes.size());

            for (UVNode n: uvnodes.values()) f.writeObject(n);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        print_log("Saving complete!");
    }

    /**
     * Load network status from file
     * @param file
     * @return False if is not possible to read the file
     */
    public boolean loadStatus(String file) {
        if (isTimechainRunning()) {
            setTimechainRunning(false);
            stopP2PNetwork();
        }
        uvnodes.clear();

        try (var s = new ObjectInputStream(new FileInputStream(file))) {

            print_log("Loading config...");
            this.uvConfig = (UVConfig)s.readObject();
            print_log("Loading timechain status...");
            this.UVTimechain = (UVTimechain)s.readObject();
            this.UVTimechain.setUVM(this);

            print_log("Loading UVNodes...");
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
            refreshPubkeyList();
            bootstrap_completed = true;

        } catch (IOException e) {
            return false;
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }

        print_log("Loading Ended (please notice: the timechain is currently stopped)");
        return true;
    }

    public synchronized boolean isTimechainRunning() {
        return getTimechain().isRunning();
    }

    public synchronized void setTimechainRunning(boolean running) {
        if (isTimechainRunning()==running) {
            print_log("Warning: status of timechain is already "+running);
            throw new RuntimeException("Error setting timechain satus...");
        }
        getTimechain().setRunning(running);
        if (running) {
            log("Starting timechain!");
            new Thread(getTimechain(),"Timechain").start();
        }
        else {
            log("Stopping timechain!");
            getTimechain().setRunning(false);
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

    public void bootstrapNode(UVNode node) {

        node.setP2PServices(true);
        node.p2pHandler = p2pExecutor.scheduleAtFixedRate(node::runServices,0, uvConfig.getIntProperty("p2p_period"),TimeUnit.MILLISECONDS);
        // TODO: should be this an config value?
        int max_attempts = 200;

        final var profile = node.getProfile();
        final int max_channels = profile.getIntAttribute("max_channels");
        final int min_channels = profile.getIntAttribute("min_channels");
        final int target_channel_openings = ThreadLocalRandom.current().nextInt(max_channels-min_channels)+min_channels;
        final int min_ch_size = profile.getIntAttribute("min_channel_size");

        log("BOOTSTRAPPING "+node.getPubKey()+", target channel openings: "+target_channel_openings);

        // we don't want granularity in channel sizes to be less than 100k
        // ...unless smaller channels are allowed
        int step = Math.min(100000,min_ch_size);

        while (max_attempts>0 && node.getChannelOpenings() < target_channel_openings) {
            // not mandatory, just to have different block timings in openings
            waitForBlocks(1);

            if (node.getOnchainLiquidity() <= min_ch_size) {
                log("WARNING:Exiting bootstrap on node "+node.getPubKey()+" due to insufficient onchain liquidity for min channel size "+min_ch_size);
                break; // exit while loop
            }

            var newChannelSize = (profile.getRandomSample("channel_sizes")/step)*step;

            if (node.getOnchainLiquidity()< newChannelSize) {
                log(node.getPubKey()+":Discarding attempt for channel size "+newChannelSize+": insufficient liquidity ("+node.getOnchainLiquidity()+")");
                max_attempts--;
                if (max_attempts==0)
                    log("WARNING: Exiting bootstrap due to max discards reached...");
                continue;
            }

            String peerPubkey;

            while ((peerPubkey = getRandomNode().getPubKey()).equals(node.getPubKey()));
            node.openChannel(peerPubkey,newChannelSize);
        } // while

        bootstraps_running--;
        bootstraps_ended++;
        log(node.getPubKey()+":Bootstrap Completed");
        getBootstrapLatch().countDown();
    }


    public void sendMessageToNode(String pubkey, P2PMessage message) {
        var uvnode = getNode(pubkey);
        uvnode.receiveMessage(message);
    }

    public void generateInvoiceEvents(double node_events_per_block, int blocks_duration, int min_amt, int max_amt, int max_fees) {
        if (!isBootstrapCompleted()) {
            print_log("ERROR: must execute bootstrap or load/import a network!");
            return;
        }

        // node_events_per_block = 0.01 -> each node has (on average) one event every 100 blocks
        // so, if there are 1000 nodes, 10 node events will happen globally at each block
        int events_per_block = (int)(pubkeys_list.size()*node_events_per_block);
        int total_events = events_per_block*blocks_duration;

        print_log("Generating " +total_events + " invoice events " + "(min/max amt:" + min_amt+","+ max_amt + ", max_fees" + max_fees + ")");

        int end = getTimechain().getCurrentBlock()+blocks_duration;

        print_log("Expected end after block "+end);

        if (invoiceExecutor ==null) {
            print_log("Instatianting new executor with "+invoice_thread_pool_size+ " threads...");
            ThreadFactory namedThreadFactory = new ThreadFactory() {
                private final AtomicInteger count = new AtomicInteger(0);

                @Override
                public Thread newThread(Runnable r) {
                    Thread thread = new Thread(r);
                    thread.setName("InvoiceProcess-" + count.incrementAndGet());
                    return thread;
                }
            };

            invoiceExecutor = Executors.newFixedThreadPool(invoice_thread_pool_size, namedThreadFactory);
        }
        else {
            print_log("Reusing existing thread executor (size "+invoice_thread_pool_size+")");
        }

        // so to be sure to align to a just found block timing
        waitForBlocks(1);
        for (int nb = 0; nb < blocks_duration; nb++) {
            int current_block = getTimechain().getCurrentBlock();
            for (int eb = 0; eb<events_per_block; eb++ ) {
                var sender = getRandomNode();
                UVNode dest;
                do {
                    dest = getRandomNode();
                }
                while (dest.equals(sender));

                if (max_amt==min_amt) max_amt++;
                int amount = random.nextInt(max_amt-min_amt)+min_amt;
                var invoice = dest.generateInvoice(amount, "to "+dest.getPubKey());
                invoiceExecutor.submit(()->sender.processInvoice(invoice, max_fees,false));
            }
            // we are still in the same block after all traffic has been generated
            // not sure if this check is required, but doing it for sanity check
            int current_block2 = getTimechain().getCurrentBlock();
            if (current_block2==current_block)
                waitForBlocks(1);
            //else print_log("Warning: skipping waiting for next block, starting: "+current_block+" current: "+current_block2);
        }
        print_log("Completed events generation");
    }


    /* set the channel balances of each node to the level
     Notice: for each channel only the initiators modify the balance
     */
    public void setChannelsBalances(double local_level, int min_delta) {

        for (UVNode node : this.getUVNodeList().values()) {
            for (UVChannel channel: node.getChannels().values()) {
                if (node.getPubKey().equals(channel.getInitiator())) {
                    final int current_local_liquidity = channel.getLiquidity(node.getPubKey());
                    int target_local = (int)(local_level* current_local_liquidity);
                    int delta = current_local_liquidity-target_local;
                    if (delta>min_delta)
                        node.pushSats(channel.getChannel_id(), delta);
                }
            }
        }
    }
}
