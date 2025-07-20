package network;

import message.P2PMessage;
import misc.UVConfig;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import protocol.UVTimechain;
import stats.*;
import protocol.*;

import java.io.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class UVNetwork implements LNetwork {

    private UVConfig uvConfig;
    private CountDownLatch bootstrap_latch;
    private final HashMap<String, UVNode> uvnodes;

    private ArrayList<String> pubkeys_list;

    private UVTimechain uvTimechain;

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

    private ScheduledExecutorService p2pExecutor;

    private int invoice_thread_pool_size;
    private int bootstrap_thread_pool_size;
    private int p2p_thread_pool_size;
    private int bootstraps_running;
    private int bootstraps_ended;

    private Random random;
    private long masterSeed;  // e.g. passed in via misc.UVConfig
    private final ThreadLocal<Random> threadRng =
            ThreadLocal.withInitial(() -> {
                String threadName = Thread.currentThread().getName();
                // derive a numeric seed component from the thread name (e.g., parsing suffix or using hashCode)
                long nameHash;
                String[] parts = threadName.split("-");
                if (parts.length > 1) {
                    try {
                        nameHash = Long.parseLong(parts[parts.length - 1]);
                    } catch (NumberFormatException e) {
                        nameHash = threadName.hashCode();
                    }
                } else {
                    nameHash = threadName.hashCode();
                }
                long seed = masterSeed ^ nameHash;
                return new Random(seed);
            });


    private void adjustThreadPoolSizes() {
        // get it from ulimit -a
        int max_threads = uvConfig.max_threads;

        log("Determing appropriate pool sizes... (max threads " + max_threads + ")");

        bootstrap_thread_pool_size = uvConfig.bootstrap_nodes;
        p2p_thread_pool_size = bootstrap_thread_pool_size;

        int peek_bootstrap = bootstrap_thread_pool_size + p2p_thread_pool_size;
        // during the bootstrap, 2*nodes threads could be potentially executed
        // due to bootstrap + p2p
        log(" > Threads required for bootstrap nodes: "+uvConfig.bootstrap_nodes);
        log(" > Threads required for p2p: "+p2p_thread_pool_size);
        log(" ---> estimated peek of threads during bootstrap: "+peek_bootstrap);

        if (peek_bootstrap> max_threads) {
            log("WARNING: bootstrap peek threads "+peek_bootstrap+ " execeeds max threads...");
            log(" ---> Reducing both bootstrap and p2p threads pool sizes to "+max_threads/2);
            bootstrap_thread_pool_size = max_threads/2;
            p2p_thread_pool_size = max_threads/2;
        }

        // when processing invoices, only the p2p services are running
        invoice_thread_pool_size = max_threads - p2p_thread_pool_size;
        log("Setting invoice pool size to "+invoice_thread_pool_size);

    }

    public int getBootstrapsRunning() {
        return bootstraps_running;
    }

    public int getBootstrapsEnded() {
        return bootstraps_ended;
    }

    private void loadAliasNames() {
        try {
            Scanner s = new Scanner(new FileReader("src/aliases.txt"));
            while (s.hasNextLine()) aliasNames.add(s.nextLine());

        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public String createAlias() {
        if (aliasNames.isEmpty()) return "no alias";
        var i = random.nextInt(0,aliasNames.size());
        String alias = aliasNames.get(i);
        aliasNames.remove(i);
        return alias;
    }

    /**
     * Constructor
     */
    public UVNetwork(UVConfig config) {
        this.uvConfig = config;
        masterSeed = config.getMasterSeed();
        this.random = new Random(masterSeed);

        try {
            File logFile = new File(uvConfig.logfile);
            if (logFile.exists()) {
                File previousLogFile = new File(logFile.getParent(), "previous." + logFile.getName());
                if (!logFile.renameTo(previousLogFile)) {
                    System.err.println("Warning: could not rename existing logfile to " + previousLogFile.getName());
                }
            }
            logfile = new FileWriter(uvConfig.logfile);
        } catch (IOException e) {
            log("Cannot open logfile for writing:"+ uvConfig.logfile);
            throw new RuntimeException(e);
        }

        this.uvnodes = new HashMap<>();
        uvTimechain = new UVTimechain(uvConfig.blocktime_ms,this);

        log(new Date() +":Initializing UVManager...");
        stats = new GlobalStats(this);
        adjustThreadPoolSizes();
        loadAliasNames();
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

        if (pubkeys_list==null) pubkeys_list = new ArrayList<>(uvConfig.bootstrap_nodes);

        bootstrap_started = true;
        bootstrap_latch = new CountDownLatch(uvConfig.bootstrap_nodes);
        bootstraps_running = 0;
        bootstraps_ended = 0;

        log("UVM: deploying nodes, configuration: "+ uvConfig);

        setTimechainStatus(true);
        startP2PNetwork();

        // given n node, we can expect a peak of 2*n threads during bootstrap, since each node with start its p2p services thread
         // the code below just tries to force a limit for the bootstrapping threads to avoid going beyond the limit

        double median_start_time = uvConfig.bootstrap_time_median*uvConfig.bootstrap_blocks;
        double mean_start_time = uvConfig.bootstrap_time_mean*uvConfig.bootstrap_blocks;
        int n_samples = uvConfig.bootstrap_nodes;
        int min_val = 1;
        int max_val = uvConfig.bootstrap_blocks;

        int[] boot_times = DistributionGenerator.generateIntSamples(random,n_samples,min_val,max_val,median_start_time,mean_start_time);

        Arrays.sort(boot_times);

        ExecutorService bootstrapExecutor = Executors.newFixedThreadPool(bootstrap_thread_pool_size, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "Bootstrap-" + counter.incrementAndGet());
            }
        });


        print_log("BOOTSTRAP: Creating "+uvConfig.bootstrap_nodes+" node instancies...");

        try {
            int last_issue_block = 0;

            for (int current_index=0;current_index<uvConfig.bootstrap_nodes;current_index++) {

                var profile = uvConfig.selectProfileBy(random,"prob");
                int max_capacity = profile.getIntAttribute("max_funding")/(int)1e3;
                int min_capacity = profile.getIntAttribute("min_funding") /(int)1e3;
                int funding = (int)1e3*(random.nextInt(min_capacity,max_capacity+1));

                var node = new UVNode(this,"pk"+current_index, createAlias(),funding,profile);
                uvnodes.put(node.getPubKey(),node);

                int wait_for_next = boot_times[current_index] - last_issue_block;
                if (wait_for_next > 0) {
                    waitForBlocks(wait_for_next);
                } else {
                    // Wait at least 1 block even if wait_for_next == 0, to serialize starts
                    waitForBlocks(1);
                }
                bootstraps_running++;
                bootstrapExecutor.submit(() -> bootstrapNode(node));
                last_issue_block = getTimechain().getCurrentBlockHeight();
                pubkeys_list.add(node.getPubKey());
            }

            print_log("BOOTSTRAP: Launch complete, waiting for "+bootstrap_latch.getCount()+" threads to finish...");
            bootstrap_latch.await();

        } catch (InterruptedException e) {
            System.out.println("Interrupted Bootstrap Exception!");
            Thread.currentThread().interrupt(); // handle interruption
        }
        finally {
            print_log("BOOTSTRAP: shutting down threads...");
            bootstrapExecutor.shutdown();
            try {
                var timeout = bootstrapExecutor.awaitTermination(60, TimeUnit.SECONDS);
                if (!timeout)
                    print_log("BOOTSTRAP: timeout! terminating threads...");
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

            System.out.println("Waiting "+uvConfig.p2p_max_age+" blocks as p2p messages expire time... ");
            waitForBlocks(uvConfig.p2p_max_age+1);
            System.out.println("Done!");


            System.out.println("Waiting for message queue to empty....");
            waitForEmptyQueues(100);
            System.out.println("DONE!");

            print_log("Pruning empty policies from graphs...");
            int pruned = 0;
            for (UVNode node : getUVNodeList().values()) {
                pruned += node.getChannelGraph().purgeNullPolicyChannels();
            }
            print_log("(Pruned null entries: "+pruned+") DONE!");

            var after = new Date();
            var d = after.getTime()-startTime.getTime();
            print_log("BOOTRAP: Completed at "+after+", duration (ms):"+d);

        }
    }


   private void waitForEmptyQueues(int check_period) {

        int warning_timeout = 10000; // 10 sec
        boolean enable_warning = false;
        boolean queues_empty = false;

        while (!queues_empty) {
            queues_empty = true;

            for (UVNode node : getUVNodeList().values()) {
                if (!node.areQueuesEmpty())  {
                    System.out.println("** Waiting to empty queues on "+node.getPubKey());
                    if (enable_warning)  {
                        System.out.printf("-->");
                        node.showQueuesStatus();
                        System.out.printf("------------------------------------------");
                    }
                    queues_empty = false;
                }
            }
            try {
                Thread.sleep(check_period);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (warning_timeout>0) {
                warning_timeout -= check_period;
            }
            else {
                check_period = 1000;
                enable_warning = true;
            }
        }
    }

    /**
     * If not yet started, schedule the p2p threads to be executed periodically for each node
     */
    public void startP2PNetwork() {
        print_log("Launching p2p node service threads ...");
        // start p2p actions around every block
        if (p2pExecutor==null) {
            log("Initializing p2p scheduled executor...");
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
            n.p2pHandler = p2pExecutor.scheduleAtFixedRate(n::runServices,0, uvConfig.node_services_tick_ms,TimeUnit.MILLISECONDS);
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
     * Select a random node in the network using the list of the currently known pubkeys
     * @return a network.UVNode instance of the selected node
     */
    private UVNode getRandomNode() {
        var n = random.nextInt(pubkeys_list.size());
        var some_random_key = pubkeys_list.get(n);
        return uvnodes.get(some_random_key);
    }

    /**
     *
     */
    @Override
    public Optional<LNChannel> getChannelFromNodes(String pub1, String pub2) {
        // as the name suggests, the usage of this method assumes that at max ONE channel
        // is present between nodes. It should never be used when multiple channels
        // are present between two nodes, so this incoherent condition should be checked

        var n1 = getUVNode(pub1);
        var n2 = getUVNode(pub2);

        if (n1==null || n2==null ) throw new IllegalArgumentException("Non existing nodes "+pub1+" or "+pub2);

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
    public void generateRandomPushEvents(int n) {
        if (!this.isBootstrapCompleted()) return;
        for (int i=0;i<n;i++) {
            var some_node = getRandomNode();
            var some_channel_id = some_node.getRandomChannel().getChannelId();
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
                boolean direction = random.nextBoolean();
                UVChannel ch = new UVChannel(channel_id,node1_pub,node2_pub,capacity,0,0,direction);

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


    public List<UVNode> getSortedNodeListByPubkey() {
        return this.uvnodes.values()
                .stream()
                .sorted(Comparator.comparing(node -> Integer.valueOf(node.getPubKey().substring(2))))
                .collect(Collectors.toList());
    }

    public UVNode searchNode(String pubkey) {

        var node = getUVNode(pubkey);
        while (node==null) {
            System.out.println("Node "+pubkey+" not found! ");
            System.out.print("Please enter a valid pubkey:");
            pubkey = new Scanner(System.in).nextLine();
            node = getUVNode(pubkey);
        }
        return node;
    }

    @Override
    public UVNode getUVNode(String pubkey) {
        return uvnodes.get(pubkey);
    }

    @Override
    public void deliverMessage(UVNode peer, P2PMessage message) {
        peer.deliverMessage(message);
    }


    public UVTimechain getTimechain() {
        return uvTimechain;
    }

    /**
     * Updates the list the currently known pubkeys, to be used for genel sim purposes
     */
    private void refreshPubkeyList() {
        if (pubkeys_list==null) pubkeys_list = new ArrayList<>(uvConfig.bootstrap_nodes);
        else
            pubkeys_list.clear();
        pubkeys_list.addAll(uvnodes.keySet());
    }

    public GlobalStats getStats() {
        return stats;
    }
    private CountDownLatch getBootstrapLatch() {
        return bootstrap_latch;
    }


    /**
     *
     * @param s
     */
    public void log(String s) {
        try {

            logfile.write("\n[block "+ uvTimechain.getCurrentBlockHeight()+"]:"+s);
            logfile.flush();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void print_log(String s) {
        System.out.println(s);
        log(s);
    }

    public void saveStatus(String filename) {
        if (isBootstrapStarted() && !isBootstrapCompleted())  {
            print_log("Bootstrap incomplete, cannot save");
            return;
        }

        print_log("Checking queues before saving...");
        waitForEmptyQueues(1000);
        print_log("Stopping timechain...");
        setTimechainStatus(false);

        // when stopping the timechain, the while loop could still be completing
        // another final iteration, triggering tictocNextBlock that generates
        // a concurrent modification exception when saving the object

        while (getTimechain().isThreadAlive()) {
            print_log("Waiting timchain thread to stop...");
            try {
                Thread.sleep(uvConfig.blocktime_ms);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

        stopP2PNetwork();

        var file = new File(filename);
        print_log("Saving status to "+file.getAbsolutePath());

        try (var f = new ObjectOutputStream(new FileOutputStream(file))){

            print_log("Saving config...");
            f.writeObject(uvConfig);
            print_log("Saving timechain...");
            f.writeObject(uvTimechain);
            print_log("Saving UVNodes...");
            f.writeInt(uvnodes.size());

            for (UVNode n: uvnodes.values()) f.writeObject(n);
            print_log("Saving random generator...");
            f.writeObject(random);
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
        if (getTimechainStatus()) {
            setTimechainStatus(false);
            stopP2PNetwork();
        }
        uvnodes.clear();

        try (var s = new ObjectInputStream(new FileInputStream(file))) {

            print_log("Loading config...");
            this.uvConfig = (UVConfig)s.readObject();
            print_log("Loading timechain status...");
            this.uvTimechain = (UVTimechain)s.readObject();
            this.uvTimechain.setUVM(this);

            print_log("Loading UVNodes...");
            int num_nodes = s.readInt();
            for (int i=0;i<num_nodes;i++) {
                UVNode n = (UVNode) s.readObject();
                n.setUVM(this);
                uvnodes.put(n.getPubKey(),n);
            }

            print_log("Load random generator");
            this.random = (Random)s.readObject();

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

    // notice that status refer to the condition to which the tc is set to be
    // but actual thread and block generation could still be in progress
    public synchronized boolean getTimechainStatus() {
        return getTimechain().getStatus();
    }

    public synchronized void setTimechainStatus(boolean status) {
        if (getTimechainStatus()==status) {
            print_log("Warning: status of timechain is already "+status);
            return;
        }

        getTimechain().setStatus(status);

        while (getTimechainStatus()!=status) {
            print_log("Waiting protocol.UVTimechain to update status to "+status);
        }

        if (status) print_log("Timechain set to start!");
        else
            print_log("Timechain set to stop!");
    }

    private void waitForBlocks(int blocks) {
        if (blocks==0) return;
        var ready_to_go = getTimechain().getWaitBlocksLatch(blocks);
        try {
            ready_to_go.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    public void bootstrapNode(UVNode node) {
        // get a deterministic thread-related seed to create the Random
        Random thread_rng = threadRng.get();

        node.setLocalRndGenerator(thread_rng);

        final var profile = node.getProfile();
        final int max_channels = profile.getIntAttribute("max_channels");
        final int min_channels = profile.getIntAttribute("min_channels");
        final int target_channel_openings = thread_rng.nextInt(max_channels+1-min_channels)+min_channels;
        log("BOOTSTRAP: Starting on "+node.getPubKey()+", target channel openings: "+target_channel_openings);

        node.setP2PServices(true);
        node.p2pHandler = p2pExecutor.scheduleAtFixedRate(node::runServices,0, uvConfig.node_services_tick_ms,TimeUnit.MILLISECONDS);
        // TODO: should be this an config value?
        int max_attempts = 200;

        final int min_ch_size = profile.getIntAttribute("min_channel_size");
        // we don't want granularity in channel sizes to be less than 100k
        // ...unless smaller channels are allowed
        int step = Math.min(100000,min_ch_size);

        while (max_attempts>0 && node.getChannelOpenings() < target_channel_openings) {
            // not mandatory, just to have different block timings in openings
            waitForBlocks(1);

            if (node.getOnchainLiquidity() <= min_ch_size) {
                log("Exiting bootstrap on node "+node.getPubKey()+" due to insufficient onchain liquidity for min channel size "+min_ch_size);
                break; // exit while loop
            }

            var newChannelSize = (profile.getRandomSample(thread_rng,"channel_sizes")/step)*step;

            if (node.getOnchainLiquidity()< newChannelSize) {
                if (getConfig().debug)
                    log(node.getPubKey()+":Discarding attempt for channel size "+newChannelSize+": insufficient liquidity ("+node.getOnchainLiquidity()+")");
                max_attempts--;
                if (max_attempts==0)
                    log("WARNING: Exiting bootstrap due to max attempts reached...");
                continue;
            }

            boolean opened = false;
            int peer_retries = 50;
            var target_profile = uvConfig.selectProfileBy(thread_rng,"hubness");

            while (peer_retries-- >0 && !opened ) {
                var n = thread_rng.nextInt(pubkeys_list.size());
                var some_random_key = pubkeys_list.get(n);
                if (node.hasChannelWith(some_random_key)) {
                    continue;
                }
                var some_node = uvnodes.get(some_random_key);

                if (some_node.getProfile().equals(target_profile) && !some_random_key.equals(node.getPubKey())) {
                    node.openChannel(some_random_key,newChannelSize);
                    opened = true;
                }
            }
            if (!opened) max_attempts--;
        } // while

        log("BOOTSTRAP: Completed on "+node.getPubKey());
        decreaseBootStrapCount();
    }

    private synchronized void decreaseBootStrapCount() {
        bootstraps_running--;
        bootstraps_ended++;
        getBootstrapLatch().countDown();
    }


    public void generateInvoiceEvents(double node_events_per_block, int blocks_duration, int min_amt, int max_amt, int max_fees) {
        if (!isBootstrapCompleted()) {
            print_log("ERROR: must execute bootstrap or load/import a network!");
            return;
        }

        // node_events_per_block = 0.01 -> each node has (on average) one event every 100 blocks
        // so, if there are 1000 nodes, 10 node events will happen globally at each block
        double events_per_block = pubkeys_list.size()*node_events_per_block;
        int expected_total_events = (int)(events_per_block*blocks_duration);

        print_log("Generating " +expected_total_events + " invoice events " + "(min/max amt:" + min_amt+","+ max_amt + ", max_fees" + max_fees + ")");

        int end = getTimechain().getCurrentBlockHeight()+blocks_duration;

        print_log("Expected end after block "+end);


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

        var invoiceExecutor = Executors.newFixedThreadPool(invoice_thread_pool_size, namedThreadFactory);

        // so to be sure to align to a just found block timing
        waitForBlocks(1);
        Instant start_gen = Instant.now();

        for (int nb = 0; nb < blocks_duration; nb++) {
            int current_block = getTimechain().getCurrentBlockHeight();

            // when events per block are < 1, use a probabilistic approach to determine if the are 0 or one event
            if (events_per_block < 1) {
                double r = random.nextDouble(1);
                if (r<=events_per_block) events_per_block =1;
            }

            for (int eb = 0; eb<events_per_block; eb++ ) {
                var sender = getRandomNode();
                UVNode dest;
                do {
                    dest = getRandomNode();
                }
                while (dest.equals(sender));

                if (max_amt==min_amt) max_amt++;
                int amount = random.nextInt(max_amt+1-min_amt)+min_amt;
                var invoice = dest.generateInvoice(amount,amount+ " "+ sender.getPubKey()+" to "+dest.getPubKey(),true);
                invoiceExecutor.submit(()->sender.processInvoice(invoice, max_fees,false));
            }
            // we are still in the same block after all traffic has been generated
            // not sure if this check is required, but doing it for sanity check
            int current_block2 = getTimechain().getCurrentBlockHeight();
            if (current_block2==current_block)
                waitForBlocks(1);
            //else print_log("Warning: skipping waiting for next block, starting: "+current_block+" current: "+current_block2);
        }
        print_log("Completed events generation");

        print_log("Waiting for queues to flush...");
        // the wait interval is just a reasonable value of ms between checks
        waitForEmptyQueues(uvConfig.bootstrap_nodes*5);
        print_log("DONE !!");
        Instant end_gen = Instant.now();
        Duration timeElapsed = Duration.between(start_gen, end_gen);
        System.out.println("Time elapsed: " + timeElapsed.toMillis()/1000 + " seconds");
        invoiceExecutor.shutdown();
    }


    /* set the channel balances of each node to the level
     Notice: for each channel only the initiators modify the balance
     */

    public void setLocalBalances(double local_level, int min_delta) {

        for (UVNode node : this.getUVNodeList().values()) {
            for (UVChannel channel: node.getChannels().values()) {
                // rebalancing action is started from initiator's side
                if (node.getPubKey().equals(channel.getInitiator())) {
                    final int current_local_liquidity = channel.getLiquidity(node.getPubKey());

                    int target_local = (int)(local_level* channel.getCapacity());

                    int delta = current_local_liquidity-target_local;
                    if (delta> 0) {
                        if (delta>min_delta)
                            node.pushSats(channel.getChannelId(), delta);
                    }
                    else {
                        UVNode peer = getUVNode(channel.getNonInitiator());
                        peer.pushSats(channel.getChannelId(), -delta);
                    }
                }
            }
        }
    }

    public void setRandomLiquidity(int min_delta) {

        // for each node, scan each channel
        // if the node is the initiator of the channel, compute a random local balance
        // ensure don't falling below reserve balance

        for (UVNode node : this.getUVNodeList().values()) {
            for (UVChannel channel: node.getChannels().values()) {
                if (node.getPubKey().equals(channel.getInitiator())) {
                    final int current_local_liquidity = channel.getLiquidity(node.getPubKey());
                    double rand = random.nextDouble(0,1);
                    int target_local = (int)(rand* channel.getCapacity());
                    int delta = current_local_liquidity-target_local;
                    if (target_local>channel.getReserve()) {
                        if (delta> 0) {
                            if (delta>min_delta)
                                node.pushSats(channel.getChannelId(), delta);
                        }
                        else {
                            UVNode peer = getUVNode(channel.getNonInitiator());
                            peer.pushSats(channel.getChannelId(), -delta);
                        }
                    }
                }
            }
        }
    }

    public String generateChannelIdFromTimechain(UVTransaction tx, UVNode uvNode) {
        var searchPos = getTimechain().findTxLocation(tx,0);
        var position = searchPos.get();

        return position.height() + "x" + position.tx_index();

    }
}
