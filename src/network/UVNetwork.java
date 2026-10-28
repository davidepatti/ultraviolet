package network;

import message.P2PMessage;
import misc.UVConfig;
import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import stats.*;
import protocol.*;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;

public class UVNetwork implements LNetwork {
    private static final Pattern SYNTHETIC_PUBKEY_PATTERN = Pattern.compile("^pk\\d+$");

    public enum TopologyMode {
        SIMULATED_DISTRIBUTED,
        IMPORTED_OBSERVER_VIEW
    }

    public enum SnapshotConfigPolicy { RESTORE_SAVED, APPLY_RUNTIME }

    private UVConfig uvConfig;
    private CountDownLatch bootstrap_latch;
    private final Map<String, UVNode> uvnodes;

    private List<String> pubkeys_list;
    private String imported_rootnode_graph;
    private TopologyMode topologyMode = TopologyMode.SIMULATED_DISTRIBUTED;

    private UVTimechain uvTimechain;
    private final HTLCResolution htlcResolution = new HTLCResolution(this);

    HTLCResolution htlcResolution() { return htlcResolution; }

    /** Chain observation runs independently of P2P delivery; never called under the clock lock. */
    public void onBlockCommitted(int height) {
        requireHealthy();
        if (height != uvTimechain.getCurrentBlockHeight()) throw new IllegalArgumentException("Resolution requires the committed block height");
        htlcResolution.onBlock(height);
        for (UVNode node : getUVNodeList().values()) node.reconcileHTLCs();
    }

    private volatile boolean bootstrap_started = false;
    private volatile boolean bootstrap_completed = false;
    public static final long DEFAULT_DRAIN_TIMEOUT_MS = 120_000;
    private volatile boolean bootstrapWorkersTerminated;
    private final Object admission = new Object();
    private final ReentrantLock lifecycle = new ReentrantLock();
    private final AtomicReference<String> integrityFailure = new AtomicReference<>();
    private final ThreadLocal<Integer> mutationDepth = ThreadLocal.withInitial(() -> 0);
    private final ThreadLocal<Boolean> admittedTask = ThreadLocal.withInitial(() -> false);
    private boolean draining;
    private volatile boolean closed;
    private int activeMutations;
    private int activePayments;
    private int activeInvoiceTasks;
    private long invoiceIdentity;
    private final Set<Long> issuedIdentities = new HashSet<>();
    private final Set<String> processedInvoices = new HashSet<>();
    private ExecutorService invoiceExecutor;
    private volatile ExecutorService bootstrapExecutor;
    private final java.util.concurrent.atomic.AtomicBoolean batchActive = new java.util.concurrent.atomic.AtomicBoolean();
    private final Set<String> batchIds = new HashSet<>();
    private volatile InvoiceBatch lastInvoiceBatch;
    private long batchSequence;
    private volatile Thread batchOwner;
    public static Consumer<String> Log = System.out::println;
    private static final int LOG_QUEUE_CAPACITY = 131072;
    private static final int LOG_BATCH_SIZE = 256;
    private static final long LOG_POLL_TIMEOUT_MS = 200L;

    private final GlobalStats stats;

    private final ArrayList<String> aliasNames = new ArrayList<>();

    public UVConfig getConfig() {
        return uvConfig;
    }

    public synchronized TopologyMode getTopologyMode() {
        return topologyMode;
    }

    public synchronized boolean isImportedObserverView() {
        return topologyMode == TopologyMode.IMPORTED_OBSERVER_VIEW && imported_rootnode_graph != null;
    }

    public synchronized String getImportedRootNodeGraph() {
        return imported_rootnode_graph;
    }

    public synchronized String describeTopologyMode() {
        if (isImportedObserverView()) {
            return "imported observer view, root=" + imported_rootnode_graph;
        }
        return "simulated distributed";
    }
    private final BlockingQueue<String> logQueue = new ArrayBlockingQueue<>(LOG_QUEUE_CAPACITY);
    private final AtomicInteger droppedLogMessages = new AtomicInteger(0);
    private volatile boolean logWorkerRunning = false;
    private Thread logWorkerThread;
    private BufferedWriter logfile;

    private volatile ScheduledExecutorService p2pExecutor;

    private int invoice_thread_pool_size;
    private int bootstrap_thread_pool_size;
    private int p2p_thread_pool_size;
    private int bootstraps_running;
    private int bootstraps_ended;

    private Random random;
    private long masterSeed;  // e.g. passed in via misc.UVConfig
    private String bootstrapSessionId;
    private String lastLoadStatusError;
    private BufferedWriter criticalLogWriter;
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

    private static final class LoadedStatus {
        private final UVConfig config;
        private final UVTimechain timechain;
        private final LinkedHashMap<String, UVNode> nodes;
        private final Random random;
        private final TopologyMode topologyMode;
        private final String importedRootNodeGraph;
        private long invoiceIdentity;
        private Set<String> processedInvoices = Set.of();

        private LoadedStatus(
                UVConfig config,
                UVTimechain timechain,
                LinkedHashMap<String, UVNode> nodes,
                Random random,
                TopologyMode topologyMode,
                String importedRootNodeGraph
        ) {
            this.config = config;
            this.timechain = timechain;
            this.nodes = nodes;
            this.random = random;
            this.topologyMode = topologyMode;
            this.importedRootNodeGraph = importedRootNodeGraph;
        }
    }

    private static final class TopologySnapshotMetadata implements Serializable {
        @Serial
        private static final long serialVersionUID = 1L;

        private final TopologyMode topologyMode;
        private final String importedRootNodeGraph;
        private final long invoiceIdentity;
        private final Set<String> processedInvoices;

        private TopologySnapshotMetadata(TopologyMode topologyMode, String importedRootNodeGraph, long invoiceIdentity, Set<String> processedInvoices) {
            this.topologyMode = topologyMode;
            this.importedRootNodeGraph = importedRootNodeGraph;
            this.invoiceIdentity = invoiceIdentity;
            this.processedInvoices = new HashSet<>(processedInvoices);
        }
    }


    private void adjustThreadPoolSizes() {
        // get it from ulimit -a
        int max_threads = uvConfig.max_threads;
        if (max_threads < 2) throw new IllegalArgumentException("max_threads must be at least 2");

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
        if (config.max_threads < 2 || config.bootstrap_nodes < 1 || config.blocktime_ms <= 0 || config.node_services_tick_ms <= 0) {
            throw new IllegalArgumentException("Positive node/timing settings and at least two worker threads required");
        }
        this.uvConfig = config;
        masterSeed = config.getMasterSeed();
        this.random = new Random(masterSeed);
        this.bootstrapSessionId = null;
        this.criticalLogWriter = null;

        try {
            File previousLog = new File(uvConfig.logfile);
            if (previousLog.exists()) {
                File previousLogFile = new File(previousLog.getParent(), "previous." + previousLog.getName());
                if (!previousLog.renameTo(previousLogFile)) {
                    System.err.println("Warning: could not rename existing logfile to " + previousLogFile.getName());
                }
            }
            logfile = new BufferedWriter(new FileWriter(uvConfig.logfile));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot open logfile for writing: " + uvConfig.logfile, e);
        }
        startLogWorker();

        // Keep insertion order so imported topologies can be displayed as loaded.
        this.uvnodes = Collections.synchronizedMap(new LinkedHashMap<>());
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

        synchronized (admission) {
            requireHealthy();
            if (closed || draining || bootstrap_started || !uvnodes.isEmpty()) throw new IllegalStateException("Bootstrap requires a fresh network");
            bootstrap_started = true;
            bootstrapWorkersTerminated = false;
        }

        Thread.currentThread().setName("Bootstrap");
        var startTime = new Date();
        bootstrapSessionId = new SimpleDateFormat("yyyyMMddHHmmss").format(startTime);
        print_log(startTime +": Bootstrapping network from scratch...");

        if (pubkeys_list==null) pubkeys_list = new ArrayList<>(uvConfig.bootstrap_nodes);

        topologyMode = TopologyMode.SIMULATED_DISTRIBUTED;
        imported_rootnode_graph = null;
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

        synchronized (admission) {
            if (closed) throw new IllegalStateException("Simulation is closed");
            bootstrapExecutor = Executors.newFixedThreadPool(bootstrap_thread_pool_size, new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(0);
            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "Bootstrap-" + counter.incrementAndGet());
            }
        });
        }


        print_log("BOOTSTRAP: Creating "+uvConfig.bootstrap_nodes+" node instancies...");

        try {
            int last_issue_block = 0;

            for (int current_index=0;current_index<uvConfig.bootstrap_nodes;current_index++) {

                var profile = uvConfig.selectProfileBy(random,"prob");
                int max_capacity = profile.getIntAttribute("max_funding")/(int)1e3;
                int min_capacity = profile.getIntAttribute("min_funding") /(int)1e3;
                int funding = (int)1e3*(random.nextInt(min_capacity,max_capacity+1));

                var node = new UVNode(this,"pk"+current_index, createAlias(),funding,profile);

                synchronized (pubkeys_list) {
                    uvnodes.put(node.getPubKey(),node);
                    bootstraps_running++;
                    pubkeys_list.add(node.getPubKey());
                }

                int to_wait = boot_times[current_index] - last_issue_block;
                // Wait at least 1 block even if to_wait == 0, to serialize starts
                waitForBlocks(Math.max(to_wait,1));
                last_issue_block = getTimechain().getCurrentBlockHeight();

                bootstrapExecutor.submit(() -> bootstrapNode(node));
            }

            print_log("BOOTSTRAP: Launch complete, waiting for "+bootstrap_latch.getCount()+" threads to finish...");
            while (!bootstrap_latch.await(100, TimeUnit.MILLISECONDS)) {
                requireHealthy();
                if (closed) throw new IllegalStateException("Simulation closed during bootstrap");
            }

        } catch (InterruptedException e) {
            bootstrapExecutor.shutdownNow();
            recordFailure("Bootstrap interrupted", e);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Bootstrap interrupted", e);
        }
        finally {
            print_log("BOOTSTRAP: shutting down threads...");
            bootstrapExecutor.shutdown();
            try {
                var timeout = bootstrapExecutor.awaitTermination(60, TimeUnit.SECONDS);
                bootstrapWorkersTerminated = timeout;
                if (!timeout) {
                    bootstrapExecutor.shutdownNow();
                    recordFailure("Bootstrap workers did not terminate", null);
                    throw new IllegalStateException("Bootstrap worker termination deadline exceeded");
                }
            }
            catch (InterruptedException e) {
                bootstrapExecutor.shutdownNow();
                recordFailure("Bootstrap termination interrupted", e);
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Bootstrap termination interrupted", e);
            }

            requireHealthy();
            print_log("Waiting "+uvConfig.p2p_max_age+" blocks as p2p messages expire time... ");
            waitForBlocks(uvConfig.p2p_max_age+1);

            print_log("Waiting for message queue to empty....");
            requireHealthy();
            pauseAndDrain();

            print_log("Pruning empty policies from graphs...");
            int pruned = 0;
            for (UVNode node : getUVNodeList().values()) {
                pruned += node.getChannelGraph().purgeNullPolicyChannels();
            }
            print_log("(Pruned null entries: "+pruned+") DONE!");

            bootstrap_completed = true;
            resume();
            closeCriticalLog();
            var after = new Date();
            var d = after.getTime()-startTime.getTime();
            print_log("BOOTSTRAP: Completed at "+after+", duration (ms):"+d);

        }
    }


    /** Mutation admission is shared by direct node calls, terminal commands and scripts. */
    public final class Mutation implements AutoCloseable {
        private boolean released;
        private Mutation() {}
        @Override public void close() {
            synchronized (admission) {
                if (!released) { activeMutations--; mutationDepth.set(mutationDepth.get() - 1); released = true; admission.notifyAll(); }
            }
        }
    }

    public Mutation beginMutation() {
        synchronized (admission) {
            requireHealthy();
            if (closed || (draining && mutationDepth.get() == 0)) throw new IllegalStateException("Simulation is closed or draining");
            activeMutations++;
            mutationDepth.set(mutationDepth.get() + 1);
            return new Mutation();
        }
    }

    public void requireHealthy() {
        String failure = integrityFailure.get();
        if (failure != null) throw new IllegalStateException("Simulation integrity failure: " + failure);
    }

    void checkPaymentProgress() {
        requireHealthy();
        if (closed) throw new IllegalStateException("Simulation closed with unresolved payment state");
    }

    public boolean isDraining() { synchronized (admission) { return draining; } }

    public boolean isHealthy() { return integrityFailure.get() == null; }
    public String getIntegrityFailure() { return integrityFailure.get(); }

    public void recordFailure(String context, Throwable error) {
        integrityFailure.compareAndSet(null, context + (error == null ? "" : ": " + error));
        if (error != null) logException(context, error);
        else log("INTEGRITY FAILURE: " + context);
    }

    public long nextInvoiceIdentity() {
        synchronized (admission) {
            requireHealthy();
            if (closed || (draining && mutationDepth.get() == 0)) throw new IllegalStateException("Simulation is closed or draining");
            invoiceIdentity = Math.incrementExact(invoiceIdentity);
            return invoiceIdentity;
        }
    }

    public void reserveInvoiceIdentity(long identity) {
        synchronized (admission) {
            if (identity <= 0 || !issuedIdentities.add(identity)) throw new IllegalStateException("Duplicate or invalid invoice identity");
            invoiceIdentity = Math.max(invoiceIdentity, identity);
        }
    }

    void beginPayment(String hash) {
        synchronized (admission) {
            requireHealthy();
            if (!isRunning() || !isBootstrapCompleted() || (draining && !admittedTask.get())) throw new IllegalStateException("Simulation is not accepting payments");
            if (!processedInvoices.add(hash)) throw new IllegalStateException("Invoice already submitted: " + hash);
            activePayments++;
        }
    }

    void endPayment() {
        synchronized (admission) { activePayments--; admission.notifyAll(); }
    }

    public Future<GlobalStats.NodeStats.InvoiceReport> submitInvoice(UVNode sender, LNInvoice invoice, int maxFees, boolean showUi) {
        return submitInvoice(sender, invoice, maxFees, showUi, report -> {}, error -> {});
    }

    private Future<GlobalStats.NodeStats.InvoiceReport> submitInvoice(UVNode sender, LNInvoice invoice, int maxFees,
            boolean showUi, Consumer<GlobalStats.NodeStats.InvoiceReport> completed, Consumer<Throwable> failed) {
        synchronized (admission) {
            requireHealthy();
            if (!isRunning() || !isBootstrapCompleted() || (draining && mutationDepth.get() == 0)) throw new IllegalStateException("Simulation is not accepting payments");
            if (getUVNode(sender.getPubKey()) != sender) throw new IllegalArgumentException("Sender belongs to another network");
            if (invoiceExecutor == null) invoiceExecutor = Executors.newFixedThreadPool(invoice_thread_pool_size);
            activeInvoiceTasks++;
            var task = new FutureTask<GlobalStats.NodeStats.InvoiceReport>(() -> {
                admittedTask.set(true);
                try {
                    var report = sender.processInvoice(invoice, maxFees, showUi);
                    completed.accept(report);
                    return report;
                } catch (Throwable error) {
                    failed.accept(error);
                    if (showUi) System.out.println("Payment execution failed: " + error.getMessage());
                    recordFailure("Invoice task failed", error);
                    throw error;
                } finally { admittedTask.remove(); }
            }) {
                @Override protected void done() {
                    synchronized (admission) { activeInvoiceTasks--; admission.notifyAll(); }
                }
            };
            try { invoiceExecutor.execute(task); }
            catch (RuntimeException error) { task.cancel(false); throw error; }
            return task;
        }
    }

    public boolean isRunning() {
        return !closed && isHealthy() && uvTimechain.getStatus()
                && p2pExecutor != null && !p2pExecutor.isShutdown();
    }

    public boolean isPaused() {
        synchronized (admission) {
            return !closed && isHealthy() && isBootstrapCompleted() && !draining
                    && activeInvoiceTasks == 0 && activePayments == 0 && activeMutations == 0
                    && !uvTimechain.getStatus() && !uvTimechain.isThreadAlive()
                    && (p2pExecutor == null || p2pExecutor.isTerminated());
        }
    }

    public Map<String, Map<String, Integer>> unsettledState() {
        Map<String, Map<String, Integer>> state = new TreeMap<>();
        for (UVNode node : getUVNodeList().values()) {
            var pending = node.unsettledState();
            if (!pending.isEmpty()) state.put(node.getPubKey(), pending);
        }
        int pending = htlcResolution.pendingCount();
        if (pending != 0) state.put("@htlc-resolution", Map.of("pending", pending));
        return state;
    }

    private boolean hasActiveWork() {
        synchronized (admission) { return activeInvoiceTasks != 0 || activePayments != 0 || activeMutations != 0; }
    }

    private static long remainingMillis(long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) throw new IllegalStateException("Simulation drain deadline exceeded");
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    public void resume() {
        lifecycle.lock();
        try {
            synchronized (admission) {
                requireHealthy();
                if (closed || (draining && mutationDepth.get() == 0)) throw new IllegalStateException("Simulation is closed or draining");
                if (isRunning()) return;
                if (batchActive.get() && batchOwner != Thread.currentThread()) throw new IllegalStateException("Invoice batch owns the lifecycle");
                if (hasActiveWork()) throw new IllegalStateException("Cannot resume during active work");
                uvTimechain.setStatus(true);
                startP2PWorkers();
            }
        } finally { lifecycle.unlock(); }
    }

    /** Stop admission, finish queued/direct payments, drain protocol state, join and recheck. */
    public void pauseAndDrain(long timeoutMillis) throws InterruptedException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("Positive timeout required");
        lifecycle.lockInterruptibly();
        try {
            synchronized (admission) {
                requireHealthy();
                if (closed || (bootstrap_started && !bootstrap_completed && !bootstrapWorkersTerminated)) {
                    throw new IllegalStateException("Cannot pause during bootstrap or after shutdown");
                }
                if (batchActive.get() && batchOwner != Thread.currentThread()) throw new IllegalStateException("Invoice batch owns the lifecycle");
                draining = true;
            }
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (true) {
                requireHealthy();
                if (hasActiveWork() || !unsettledState().isEmpty()) {
                    Thread.sleep(Math.min(10, remainingMillis(deadline)));
                    continue;
                }
                uvTimechain.stopAndAwait(remainingMillis(deadline));
                stopP2PWorkers(remainingMillis(deadline));
                requireHealthy();
                if (!hasActiveWork() && unsettledState().isEmpty()) return;
                remainingMillis(deadline);
                uvTimechain.setStatus(true);
                startP2PWorkers();
            }
        } catch (RuntimeException | InterruptedException error) {
            if (draining) recordFailure("Could not establish a settled pause", error);
            throw error;
        } finally {
            synchronized (admission) { draining = false; }
            lifecycle.unlock();
        }
    }

    public void pauseAndDrain() {
        try { pauseAndDrain(DEFAULT_DRAIN_TIMEOUT_MS); }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted draining simulation", e);
        }
    }

    /** Capture/export/save all use this exclusive boundary, including terminal callers. */
    public <T> T withSettledState(Supplier<T> capture) {
        lifecycle.lock();
        boolean running = isRunning();
        boolean capturedBoundary = false;
        try {
            pauseAndDrain();
            capturedBoundary = true;
            synchronized (admission) {
                draining = true;
                try { validateSettledState(); return capture.get(); }
                finally { draining = false; }
            }
        } finally {
            try { if (capturedBoundary && running && isHealthy() && !closed) resume(); }
            finally { lifecycle.unlock(); }
        }
    }

    private void validateSettledState() {
        requireHealthy();
        if (!unsettledState().isEmpty()) throw new IllegalStateException("Unsettled simulation state");
        var nodes = getUVNodeList();
        for (UVNode node : nodes.values()) {
            for (UVChannel channel : node.getChannels().values()) {
                String a = channel.getNode1PubKey(), b = channel.getNode2PubKey();
                if (!nodes.containsKey(a) || !nodes.containsKey(b)
                        || nodes.get(a).getChannels().get(channel.getChannelId()) != channel
                        || nodes.get(b).getChannels().get(channel.getChannelId()) != channel) {
                    throw new IllegalStateException("Inconsistent channel endpoints: " + channel.getChannelId());
                }
                int left = channel.getBalance(a), right = channel.getBalance(b);
                if (left < 0 || right < 0 || (long) left + right != channel.getCapacity()) {
                    throw new IllegalStateException("Channel balance conservation violation: " + channel.getChannelId());
                }
            }
        }
    }

    private void startP2PWorkers() {
        if (p2pExecutor == null || p2pExecutor.isTerminated()) {
            p2pExecutor = Executors.newScheduledThreadPool(p2p_thread_pool_size);
        }
        if (p2pExecutor.isShutdown()) throw new IllegalStateException("Previous P2P executor has not terminated");
        for (UVNode node : getUVNodeList().values()) startNodeServices(node);
    }

    private void startNodeServices(UVNode node) {
        synchronized (node) {
            if (node.p2pHandler != null && !node.p2pHandler.isDone()) return;
            node.setP2PServices(true);
            node.p2pHandler = p2pExecutor.scheduleAtFixedRate(node::runServices, 0, uvConfig.node_services_tick_ms, TimeUnit.MILLISECONDS);
        }
    }

    private void stopP2PWorkers(long timeoutMillis) throws InterruptedException {
        for (UVNode node : getUVNodeList().values()) {
            node.setP2PServices(false);
            if (node.p2pHandler != null) node.p2pHandler.cancel(false);
        }
        if (p2pExecutor != null) {
            p2pExecutor.shutdown();
            if (!p2pExecutor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IllegalStateException("P2P termination deadline exceeded");
        }
    }

    /** Legacy entry points now obey the same lifecycle as all other callers. */
    public void startP2PNetwork() { resume(); }
    public void stopP2PNetwork() { pauseAndDrain(); }

    public InvoiceBatch getLastInvoiceBatch() { return lastInvoiceBatch; }

    private void waitBlock(long timeoutMillis) throws InterruptedException {
        var latch = uvTimechain.getWaitBlocksLatch(1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        try {
            while (!latch.await(Math.min(100, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
                requireHealthy();
                if (closed) throw new IllegalStateException("Simulation closed while waiting for blocks");
            }
        } finally { uvTimechain.cancelWait(latch); }
    }

    public InvoiceBatch generateInvoiceBatch(String batchId, double rate, int blocks,
            int minAmount, int maxAmount, int maxFees, long drainMillis) throws InterruptedException {
        return generateInvoiceBatch(batchId, rate, blocks, minAmount, maxAmount, maxFees, drainMillis, null);
    }

    private InvoiceBatch generateInvoiceBatch(String batchId, double rate, int blocks,
            int minAmount, int maxAmount, int maxFees, long drainMillis, UVNode fixedSender) throws InterruptedException {
        if (!isRunning() || getUVNodeList().size() < 2 || !Double.isFinite(rate) || rate <= 0
                || (fixedSender == null ? rate * getUVNodeList().size() : rate) >= Integer.MAX_VALUE
                || blocks <= 0 || minAmount <= 0 || minAmount > maxAmount
                || maxFees < 0 || drainMillis <= 0 || batchId == null || batchId.isBlank()) {
            throw new IllegalArgumentException("Invalid invoice batch/state");
        }
        synchronized (admission) {
            if (closed || draining || !batchActive.compareAndSet(false, true)) throw new IllegalStateException("Another operation owns the lifecycle");
            batchOwner = Thread.currentThread();
            if (!batchIds.add(batchId)) { batchOwner = null; batchActive.set(false); throw new IllegalArgumentException("duplicate batch ID"); }
        }
        var batch = new InvoiceBatch(batchId, rate, blocks, minAmount, maxAmount, maxFees,
                invoice_thread_pool_size, uvTimechain.getCurrentBlockHeight(), fixedSender == null ? null : fixedSender.getPubKey());
        lastInvoiceBatch = batch;
        var futures = new ArrayList<Future<GlobalStats.NodeStats.InvoiceReport>>();
        // Injection and every submitted task remain accounted for, even if injection fails.
        Exception injectionFailure = null;
        try (var injection = beginMutation()) {
            waitBlock(drainMillis);
            double events = fixedSender == null ? getUVNodeList().size() * rate : rate;
            for (int block = 0; block < blocks; block++) {
                requireHealthy();
                int height = uvTimechain.getCurrentBlockHeight();
                int count = (int) events + (random.nextDouble(1) < events - (int) events ? 1 : 0);
                for (int event = 0; event < count; event++) {
                    UVNode sender = fixedSender == null ? getRandomNode() : fixedSender, destination;
                    do { destination = getRandomNode(); } while (destination.equals(sender));
                    int amount = minAmount == maxAmount ? minAmount : random.nextInt(maxAmount - minAmount + 1) + minAmount;
                    long identity = nextInvoiceIdentity();
                    var invoice = destination.generateInvoiceWithIdentity(amount, amount + " " + sender.getPubKey() + " to " + destination.getPubKey(), identity);
                    batch.request(identity, sender.getPubKey(), invoice, uvTimechain.getCurrentBlockHeight());
                    futures.add(submitInvoice(sender, invoice, maxFees, false,
                            report -> batch.complete(identity, report, uvTimechain.getCurrentBlockHeight()),
                            error -> batch.exception(identity, error, uvTimechain.getCurrentBlockHeight())));
                }
                if (uvTimechain.getCurrentBlockHeight() == height) waitBlock(drainMillis);
            }
        } catch (Exception error) {
            injectionFailure = error;
            batch.fail(error); recordFailure("Invoice injection failed", error);
        } finally { batch.injectionStopped(uvTimechain.getCurrentBlockHeight()); }
        try {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(drainMillis);
            for (var future : futures) {
                try { future.get(remainingMillis(deadline), TimeUnit.MILLISECONDS); }
                catch (ExecutionException error) { /* outcome recorded; account for remaining tasks too */ }
                catch (TimeoutException error) { throw new IllegalStateException("Invoice completion deadline exceeded", error); }
            }
            if (injectionFailure instanceof InterruptedException interrupted) throw interrupted;
            if (injectionFailure != null) throw new IllegalStateException("Invoice injection failed", injectionFailure);
            batch.requireCompleted();
            long started = System.nanoTime();
            pauseAndDrain(remainingMillis(deadline));
            batch.markSettled(uvTimechain.getCurrentBlockHeight(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
            return batch;
        } catch (Exception error) {
            batch.fail(error); recordFailure("Invoice batch failed", error); throw error;
        } finally { batchOwner = null; batchActive.set(false); }
    }

    /**
     * Select a random node in the network using the list of the currently known pubkeys
     * @return a network.UVNode instance of the selected node
     */
    private UVNode getRandomNode() {
        var nodes = new ArrayList<>(getUVNodeList().values());
        return nodes.get(random.nextInt(nodes.size()));
    }

    /** Register before starting services; callers cannot mutate the internal registry map. */
    public void registerNode(UVNode node) {
        try (var mutation = beginMutation()) {
            if (uvnodes.putIfAbsent(node.getPubKey(), node) != null) throw new IllegalArgumentException("Duplicate node");
            refreshPubkeyList();
        }
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
        withSettledState(() -> { generateRandomPushEventsInternal(n); return null; });
    }

    private void generateRandomPushEventsInternal(int n) {
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


    public void importTopology(String json_file, String root_node) {
        withSettledState(() -> { importTopologyInternal(json_file, root_node); return null; });
    }

    private void importTopologyInternal(String json_file, String root_node) {
        print_log("Beginning importing file " + json_file);

        try {
            Object parsed = Json.parse(new FileReader(json_file));
            if (!(parsed instanceof JsonObject jsonObject)) {
                print_log("Import failed: root JSON is not an object");
                return;
            }

            JsonArray nodes = (JsonArray) jsonObject.get("nodes");
            if (nodes == null) {
                print_log("Import failed: missing 'nodes' array");
                return;
            }

            var defaultProfile = uvConfig.getNodeProfiles().get("default");
            if (defaultProfile == null) {
                print_log("Import failed: missing default node profile");
                return;
            }

            // Build imported state in isolation; commit only when fully valid.
            Map<String, UVNode> importedNodes = new LinkedHashMap<>();
            for (Object node : nodes) {
                if (!(node instanceof JsonObject nodeObject)) {
                    print_log("Import failed: invalid node entry " + node);
                    return;
                }
                String pub_key = (String) nodeObject.get("pub_key");
                String alias = (String) nodeObject.get("alias");
                if (pub_key == null || pub_key.isBlank()) {
                    print_log("Import failed: node with missing pub_key");
                    return;
                }
                if (alias == null) alias = "";
                importedNodes.put(pub_key, new UVNode(this, pub_key, alias, 12345678, defaultProfile));
            }

            UVNode root = resolveImportedRootNode(importedNodes, root_node);
            if (root == null) {
                return;
            }

            JsonArray edges = (JsonArray) jsonObject.get("edges");
            if (edges == null) {
                print_log("Import failed: missing 'edges' array");
                return;
            }

            for (Object edge : edges) {
                if (!(edge instanceof JsonObject edgeObject)) {
                    print_log("Import failed: invalid edge entry " + edge);
                    return;
                }

                String channel_id = (String) edgeObject.get("channel_id");
                String node1_pub = (String) edgeObject.get("node1_pub");
                String node2_pub = (String) edgeObject.get("node2_pub");
                Object capacityObj = edgeObject.get("capacity");

                if (channel_id == null || channel_id.isBlank() || node1_pub == null || node2_pub == null) {
                    print_log("Import failed: malformed edge " + edgeObject);
                    return;
                }

                int capacity;
                if (capacityObj instanceof Number n) {
                    capacity = n.intValue();
                } else if (capacityObj instanceof String s) {
                    capacity = Integer.parseInt(s);
                } else {
                    print_log("Import failed: invalid capacity in edge " + channel_id + ": " + capacityObj);
                    return;
                }

                UVNode uvnode1 = importedNodes.get(node1_pub);
                UVNode uvnode2 = importedNodes.get(node2_pub);
                if (uvnode1 == null || uvnode2 == null) {
                    print_log("Import failed: edge " + channel_id + " references unknown node(s): "
                            + node1_pub + ", " + node2_pub);
                    return;
                }

                // UVChannel expects lexicographic order of endpoints.
                String left = node1_pub;
                String right = node2_pub;
                if (left.compareTo(right) > 0) {
                    String tmp = left;
                    left = right;
                    right = tmp;
                }

                // TODO: get real init direction
                boolean direction = random.nextBoolean();
                UVChannel ch = new UVChannel(channel_id,left,right,capacity,0,0,direction);

                uvnode1.configureChannel(ch);
                uvnode2.configureChannel(ch);
                uvnode1.getChannelGraph().addLNChannel(ch);
                uvnode2.getChannelGraph().addLNChannel(ch);

                root.getChannelGraph().addLNChannel(ch);
            }

            synchronized (this) {
                htlcResolution.reset();
                uvnodes.clear();
                uvnodes.putAll(importedNodes);
                refreshPubkeyList();
                imported_rootnode_graph = root.getPubKey();
                topologyMode = TopologyMode.IMPORTED_OBSERVER_VIEW;
                bootstrap_started = true;
                bootstrap_completed = true;
                bootstrap_latch = null;
                bootstraps_running = uvnodes.size();
                bootstraps_ended = uvnodes.size();
            }

            print_log("Import completed (observer graph rooted at " + root.getPubKey() + ")");

        } catch (IOException | NumberFormatException | ClassCastException e) {
            print_log("Import failed: " + e.getMessage());
        }
    }

    private UVNode resolveImportedRootNode(Map<String, UVNode> importedNodes, String rootIdentifier) {
        String identifier = rootIdentifier == null ? "" : rootIdentifier.trim();
        if (identifier.isBlank()) {
            print_log("Import failed: missing root node pubkey or alias");
            return null;
        }

        UVNode byPubkey = importedNodes.get(identifier);
        if (byPubkey != null) {
            return byPubkey;
        }

        ArrayList<UVNode> aliasMatches = new ArrayList<>();
        for (UVNode node : importedNodes.values()) {
            if (identifier.equals(node.getAlias())) {
                aliasMatches.add(node);
            }
        }

        if (aliasMatches.size() == 1) {
            return aliasMatches.get(0);
        }
        if (aliasMatches.size() > 1) {
            print_log("Import failed: root alias " + identifier + " matches multiple nodes; use the pubkey instead");
            return null;
        }

        ArrayList<UVNode> caseInsensitiveAliasMatches = new ArrayList<>();
        for (UVNode node : importedNodes.values()) {
            if (node.getAlias() != null && identifier.equalsIgnoreCase(node.getAlias())) {
                caseInsensitiveAliasMatches.add(node);
            }
        }

        if (caseInsensitiveAliasMatches.size() == 1) {
            return caseInsensitiveAliasMatches.get(0);
        }
        if (caseInsensitiveAliasMatches.size() > 1) {
            print_log("Import failed: root alias " + identifier + " matches multiple nodes; use the pubkey instead");
            return null;
        }

        print_log("Import failed: root node " + identifier + " not found in topology file");
        return null;
    }
    /***************************************************************************************
     * Getters/Setters/Helpers
     */

    public boolean isBootstrapCompleted() { return bootstrap_completed; }

    public synchronized boolean isBootstrapStarted() {
        return bootstrap_started;
    }


    public HashMap<String, UVNode> getUVNodeList(){
        synchronized (uvnodes) { return new LinkedHashMap<>(uvnodes); }
    }


    public List<UVNode> getSortedNodeListByPubkey() {
        var nodes = new ArrayList<>(getUVNodeList().values());
        boolean allSyntheticPubkeys = nodes.stream()
                .map(UVNode::getPubKey)
                .allMatch(pubkey -> pubkey != null && SYNTHETIC_PUBKEY_PATTERN.matcher(pubkey).matches());

        if (!allSyntheticPubkeys) {
            return nodes;
        }

        nodes.sort(Comparator.comparingInt(node -> Integer.parseInt(node.getPubKey().substring(2))));
        return nodes;
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
        pubkeys_list.addAll(getUVNodeList().keySet());
    }

    public GlobalStats getStats() {
        return stats;
    }
    private CountDownLatch getBootstrapLatch() {
        return bootstrap_latch;
    }

    private void startLogWorker() {
        logWorkerRunning = true;
        logWorkerThread = new Thread(this::runLogWriter, "UVLogWriter");
        logWorkerThread.setDaemon(true);
        logWorkerThread.start();
    }

    private void runLogWriter() {
        ArrayList<String> batch = new ArrayList<>(LOG_BATCH_SIZE);
        while (logWorkerRunning || !logQueue.isEmpty()) {
            try {
                String first = logQueue.poll(LOG_POLL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    flushLogfile();
                    continue;
                }
                batch.add(first);
                logQueue.drainTo(batch, LOG_BATCH_SIZE - 1);
                writeLogBatch(batch);
                batch.clear();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }

        String tail;
        while ((tail = logQueue.poll()) != null) {
            batch.add(tail);
            if (batch.size() >= LOG_BATCH_SIZE) {
                writeLogBatch(batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            writeLogBatch(batch);
            batch.clear();
        }
        flushLogfile();
    }

    private void writeLogBatch(List<String> lines) {
        if (logfile == null || lines.isEmpty()) {
            return;
        }
        try {
            for (String line : lines) {
                logfile.write(line);
                logfile.newLine();
            }
        } catch (IOException e) {
            System.err.println("ERROR: cannot write logfile batch: " + e.getMessage());
        }
    }

    private void flushLogfile() {
        if (logfile == null) {
            return;
        }
        try {
            logfile.flush();
        } catch (IOException e) {
            System.err.println("ERROR: cannot flush logfile: " + e.getMessage());
        }
    }

    private int getCurrentBlockForLogs() {
        return uvTimechain != null ? uvTimechain.getCurrentBlockHeight() : -1;
    }

    private String decorateLogLine(String message) {
        return "[block " + getCurrentBlockForLogs() + "]:" + message;
    }

    private void enqueueLogLine(String line) {
        if (!logWorkerRunning) {
            return;
        }
        if (logQueue.offer(line)) {
            return;
        }
        int dropped = droppedLogMessages.incrementAndGet();
        if (dropped == 1 || dropped % 1000 == 0) {
            System.err.println("WARNING: logfile queue full, dropped messages: " + dropped);
        }
    }

    public void log(String s) {
        if (s == null) {
            return;
        }
        enqueueLogLine(decorateLogLine(s));
    }

    public void print_log(String s) {
        if (Log != null) {
            Log.accept(s);
        } else {
            System.out.println(s);
        }
        log(s);
    }

    public void logException(String context, Throwable t) {
        if (t == null) {
            return;
        }
        String prefix = context == null || context.isBlank() ? "ERROR" : "ERROR: " + context;
        log(prefix + " -> " + t);
        StringWriter sw = new StringWriter();
        t.printStackTrace(new PrintWriter(sw));
        for (String line : sw.toString().split("\\R")) {
            if (!line.isBlank()) {
                log(line);
            }
        }
    }

    public void shutdown() {
        lifecycle.lock();
        try {
            synchronized (admission) { closed = true; draining = true; }
            stopWorkers();
            closeCriticalLog();
            shutdownLogging();
        } finally { lifecycle.unlock(); }
    }

    private void stopWorkers() {
        try {
            if (bootstrapExecutor != null) {
                bootstrapExecutor.shutdownNow();
                if (!bootstrapExecutor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Bootstrap workers did not stop");
            }
            if (invoiceExecutor != null) {
                for (Runnable queued : invoiceExecutor.shutdownNow()) {
                    if (queued instanceof Future<?> future) future.cancel(false);
                }
                if (!invoiceExecutor.awaitTermination(30, TimeUnit.SECONDS)) throw new IllegalStateException("Invoice workers did not stop");
                invoiceExecutor = null;
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (hasActiveWork()) Thread.sleep(Math.min(10, remainingMillis(deadline)));
            stopP2PWorkers(remainingMillis(deadline));
            uvTimechain.stopAndAwait(remainingMillis(deadline));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted terminating simulation workers", e);
        }
    }

    private synchronized void shutdownLogging() {
        if (!logWorkerRunning) {
            return;
        }

        int dropped = droppedLogMessages.get();
        if (dropped > 0) {
            enqueueLogLine(decorateLogLine("WARNING: dropped log messages before shutdown: " + dropped));
        }

        logWorkerRunning = false;
        if (logWorkerThread != null) {
            try {
                logWorkerThread.join(5000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                logWorkerThread = null;
            }
        }

        flushLogfile();
        if (logfile != null) {
            try {
                logfile.close();
            } catch (IOException e) {
                System.err.println("ERROR: cannot close logfile: " + e.getMessage());
            } finally {
                logfile = null;
            }
        }
    }

    private synchronized void openCriticalLogIfNeeded() {
        if (criticalLogWriter != null) {
            return;
        }
        try {
            // Derive the critical log file name from the main logfile path, using the bootstrap session id
            File baseLogFile = new File(uvConfig.logfile);
            File parentDir = baseLogFile.getParentFile();
            String baseName = baseLogFile.getName();
            String criticalName = baseName + ".critical_" + (bootstrapSessionId != null ? bootstrapSessionId : "unknown");
            File criticalFile = (parentDir != null) ? new File(parentDir, criticalName) : new File(criticalName);
            criticalLogWriter = new BufferedWriter(new FileWriter(criticalFile, true));
        } catch (IOException e) {
            // Fall back to standard logging if critical log cannot be opened
            print_log("ERROR: cannot open critical log file: " + e.getMessage());
        }
    }

    private synchronized void closeCriticalLog() {
        if (criticalLogWriter != null) {
            try {
                criticalLogWriter.close();
            } catch (IOException e) {
                print_log("ERROR: cannot close critical log file: " + e.getMessage());
            } finally {
                criticalLogWriter = null;
            }
        }
    }

    /**
     * Report a critical situation related to the current bootstrap session.
     * The message is appended to a dedicated critical log file whose name
     * includes the bootstrap session identifier (e.g. YYYYMMDDHHMMSS).
     * The file is created lazily only when this method is first invoked.
     */
    public void reportCritical(String message) {
        recordFailure(message, null);
        if (message == null) {
            return;
        }

        Log.accept("***CRITICAL LOG MESSAGE*** (check report)");

        synchronized (this) {
            // Ensure we have some session id; if bootstrap has not set it, derive one now
            if (bootstrapSessionId == null) {
                bootstrapSessionId = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
            }
            openCriticalLogIfNeeded();
            if (criticalLogWriter == null) {
                return; // if opening failed, do not proceed
            }
            try {
                criticalLogWriter.write(message);
                criticalLogWriter.newLine();
                criticalLogWriter.flush();
            } catch (IOException e) {
                print_log("ERROR: cannot write to critical log file: " + e.getMessage());
            }
        }
    }

    public void saveStatus(String filename) {
        withSettledState(() -> {
            if (!isBootstrapCompleted()) throw new IllegalStateException("Network is not initialized");
            Path target = Path.of(filename).toAbsolutePath();
            Path temporary = null;
            try {
                temporary = Files.createTempFile(target.getParent(), ".uv-snapshot-", ".tmp");
                try (var output = new ObjectOutputStream(Files.newOutputStream(temporary))) {
                    output.writeObject(uvConfig);
                    output.writeObject(uvTimechain);
                    var nodes = getUVNodeList();
                    output.writeInt(nodes.size());
                    for (UVNode node : nodes.values()) output.writeObject(node);
                    output.writeObject(random);
                    output.writeObject(new TopologySnapshotMetadata(topologyMode, imported_rootnode_graph, invoiceIdentity, processedInvoices));
                }
                try { Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
                catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) { throw new UncheckedIOException(e); }
            finally {
                if (temporary != null) try { Files.deleteIfExists(temporary); }
                catch (IOException e) { logException("Cannot remove temporary snapshot", e); }
            }
            return null;
        });
    }

    public boolean loadStatus(String file) {
        return loadStatus(file, SnapshotConfigPolicy.RESTORE_SAVED);
    }

    /** APPLY_RUNTIME validates the current config before changing live state, rebuilds
     * workers/pathfinders from it, and preserves the saved topology, clock and RNG. */
    public boolean loadStatus(String file, SnapshotConfigPolicy configPolicy) {
        Objects.requireNonNull(configPolicy, "Snapshot config policy");
        lastLoadStatusError = null;

        LoadedStatus loadedStatus;
        try {
            loadedStatus = readStatusSnapshot(file);
        } catch (IOException | ClassNotFoundException | RuntimeException e) {
            lastLoadStatusError = describeLoadStatusError(file, e);
            log(lastLoadStatusError);
            logException("Failed to load status from " + file, e);
            return false;
        }

        lifecycle.lock();
        try {
            if (batchActive.get()) { lastLoadStatusError = "Cannot load during an invoice batch"; return false; }
            if (configPolicy == SnapshotConfigPolicy.APPLY_RUNTIME) {
                try { uvConfig.validateSnapshotRuntimeSettings(loadedStatus.config); }
                catch (IllegalArgumentException conflict) {
                    lastLoadStatusError = "Cannot apply runtime settings to snapshot: " + conflict.getMessage();
                    log(lastLoadStatusError);
                    return false;
                }
                var selected = new LoadedStatus(uvConfig, loadedStatus.timechain, loadedStatus.nodes,
                        loadedStatus.random, loadedStatus.topologyMode, loadedStatus.importedRootNodeGraph);
                selected.invoiceIdentity = loadedStatus.invoiceIdentity;
                selected.processedInvoices = loadedStatus.processedInvoices;
                loadedStatus = selected;
            }
            if (isHealthy()) pauseAndDrain();
            synchronized (admission) { draining = true; }
            stopWorkers();
            applyLoadedStatus(loadedStatus);
            integrityFailure.set(null);
            closed = false;
            batchActive.set(false);
            batchIds.clear();
            lastInvoiceBatch = null;
            issuedIdentities.clear();
            invoiceIdentity = 0;
            processedInvoices.clear();
            for (UVNode node : getUVNodeList().values()) {
                issuedIdentities.addAll(node.getGeneratedInvoices().keySet());
                for (long identity : node.getGeneratedInvoices().keySet()) invoiceIdentity = Math.max(invoiceIdentity, identity);
                processedInvoices.addAll(node.getPayedInvoices().keySet());
            }
            invoiceIdentity = Math.max(invoiceIdentity, loadedStatus.invoiceIdentity);
            processedInvoices.addAll(loadedStatus.processedInvoices);
            validateSettledState();
        } catch (RuntimeException error) {
            recordFailure("Snapshot restore failed", error);
            lastLoadStatusError = error.toString();
            return false;
        } finally {
            synchronized (admission) { draining = false; }
            lifecycle.unlock();
        }
        print_log("Loading Ended (please notice: the timechain is currently stopped)");
        return true;
    }

    public String getLastLoadStatusError() {
        return lastLoadStatusError;
    }

    private LoadedStatus readStatusSnapshot(String file) throws IOException, ClassNotFoundException {
        try (var s = new ObjectInputStream(new FileInputStream(file))) {
            print_log("Loading config...");
            UVConfig loadedConfig = (UVConfig) s.readObject();

            print_log("Loading timechain status...");
            UVTimechain loadedTimechain = (UVTimechain) s.readObject();

            print_log("Loading UVNodes...");
            int num_nodes = s.readInt();
            var loadedNodes = new LinkedHashMap<String, UVNode>();
            for (int i = 0; i < num_nodes; i++) {
                UVNode node = (UVNode) s.readObject();
                loadedNodes.put(node.getPubKey(), node);
            }

            print_log("Load random generator");
            Random loadedRandom = (Random) s.readObject();
            TopologyMode loadedTopologyMode = TopologyMode.SIMULATED_DISTRIBUTED;
            String loadedImportedRootNodeGraph = null;

            TopologySnapshotMetadata snapshotMetadata = null;
            try {
                Object metadata = s.readObject();
                if (metadata instanceof TopologySnapshotMetadata topologyMetadata) {
                    snapshotMetadata = topologyMetadata;
                    loadedTopologyMode = topologyMetadata.topologyMode == null
                            ? TopologyMode.SIMULATED_DISTRIBUTED
                            : topologyMetadata.topologyMode;
                    loadedImportedRootNodeGraph = topologyMetadata.importedRootNodeGraph;
                }
            } catch (EOFException ignored) {
                // Snapshots written before topology metadata default to simulated distributed mode.
            }

            if (loadedConfig == null || loadedTimechain == null || loadedRandom == null) {
                throw new InvalidObjectException("snapshot is missing required serialized fields");
            }

            var loaded = new LoadedStatus(
                    loadedConfig,
                    loadedTimechain,
                    loadedNodes,
                    loadedRandom,
                    loadedTopologyMode,
                    loadedImportedRootNodeGraph
            );
            if (snapshotMetadata != null) {
                loaded.invoiceIdentity = snapshotMetadata.invoiceIdentity;
                loaded.processedInvoices = snapshotMetadata.processedInvoices == null ? Set.of() : snapshotMetadata.processedInvoices;
            }
            return loaded;
        }
    }

    private void applyLoadedStatus(LoadedStatus loadedStatus) {
        this.uvConfig = loadedStatus.config;
        this.masterSeed = uvConfig.getMasterSeed();
        this.uvTimechain = loadedStatus.timechain;
        this.uvTimechain.initialize(this);
        this.random = loadedStatus.random;
        this.topologyMode = loadedStatus.topologyMode == null
                ? TopologyMode.SIMULATED_DISTRIBUTED
                : loadedStatus.topologyMode;
        this.imported_rootnode_graph = loadedStatus.importedRootNodeGraph;
        if (this.topologyMode == TopologyMode.IMPORTED_OBSERVER_VIEW && this.imported_rootnode_graph == null) {
            this.topologyMode = TopologyMode.SIMULATED_DISTRIBUTED;
        }

        htlcResolution.reset();
        uvnodes.clear();
        for (UVNode node : loadedStatus.nodes.values()) {
            node.setUVM(this);
            uvnodes.put(node.getPubKey(), node);
        }

        for (UVNode node : getUVNodeList().values()) {
            node.restorePersistentData();
        }

        refreshPubkeyList();
        bootstrap_started = true;
        bootstrap_completed = true;
        bootstrap_latch = null;
        bootstraps_running = uvnodes.size();
        bootstraps_ended = uvnodes.size();
        adjustThreadPoolSizes();
    }

    private String describeLoadStatusError(String file, Throwable failure) {
        Throwable root = rootCause(failure);
        String fileName = new File(file).getName();
        String detail = cleanFailureMessage(root);

        if (root instanceof InvalidClassException) {
            return "Cannot load " + fileName + ": the snapshot was created by an incompatible UltraViolet version"
                    + suffixDetail(detail) + ".";
        }
        if (root instanceof StreamCorruptedException) {
            return "Cannot load " + fileName + ": the file is not a valid UltraViolet snapshot"
                    + suffixDetail(detail) + ".";
        }
        if (root instanceof OptionalDataException || root instanceof EOFException) {
            return "Cannot load " + fileName + ": the snapshot is truncated or incompatible with this build"
                    + suffixDetail(detail) + ".";
        }
        if (root instanceof ClassNotFoundException || root instanceof ClassCastException) {
            return "Cannot load " + fileName + ": the snapshot structure does not match this UltraViolet build"
                    + suffixDetail(detail) + ".";
        }
        if (root instanceof InvalidObjectException) {
            return "Cannot load " + fileName + ": the snapshot is missing required data"
                    + suffixDetail(detail) + ".";
        }
        if (root instanceof FileNotFoundException) {
            return "Cannot load " + fileName + ": file not found.";
        }
        if (root instanceof IOException) {
            return "Cannot load " + fileName + suffixDetail(detail) + ".";
        }
        return "Cannot load " + fileName + ": incompatible or corrupted snapshot" + suffixDetail(detail) + ".";
    }

    private Throwable rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }

    private String cleanFailureMessage(Throwable failure) {
        if (failure == null) {
            return "";
        }
        String message = failure.getMessage();
        if (message == null) {
            return "";
        }
        return message.replaceAll("\\s+", " ").trim();
    }

    private String suffixDetail(String detail) {
        if (detail == null || detail.isBlank()) {
            return "";
        }
        return " (" + detail + ")";
    }

    // notice that status refer to the condition to which the tc is set to be
    // but actual thread and block generation could still be in progress
    public synchronized boolean getTimechainStatus() {
        return getTimechain().getStatus();
    }

    public void setTimechainStatus(boolean status) {
        if (status) resume();
        else pauseAndDrain();
    }

    private void waitForBlocks(int blocks) {
        if (blocks <= 0) return;
        var latch = uvTimechain.getWaitBlocksLatch(blocks);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(
                Math.addExact(Math.multiplyExact((long) blocks, uvConfig.blocktime_ms), DEFAULT_DRAIN_TIMEOUT_MS));
        try {
            while (!latch.await(Math.min(100, remainingMillis(deadline)), TimeUnit.MILLISECONDS)) {
                requireHealthy();
                if (closed) throw new IllegalStateException("Simulation closed while waiting for blocks");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for blocks", e);
        } finally { uvTimechain.cancelWait(latch); }
    }

    public void bootstrapNode(UVNode node) {

        try {
            // get a deterministic thread-related seed to create the Random
            Random thread_rng = threadRng.get();

            node.setLocalRndGenerator(thread_rng);

            final var profile = node.getProfile();
            final int max_channels = profile.getIntAttribute("max_channels");
            final int min_channels = profile.getIntAttribute("min_channels");
            int target_channel_openings = thread_rng.nextInt(max_channels+1-min_channels)+min_channels;
            if (target_channel_openings>uvnodes.size()/2) target_channel_openings = uvnodes.size()/2;

            log("BOOTSTRAP: Starting on "+node.getPubKey()+", target channel openings: "+target_channel_openings);

            startNodeServices(node);

            final int min_ch_size = profile.getIntAttribute("min_channel_size");
            // we don't want granularity in channel sizes to be less than 100k
            // ...unless smaller channels are allowed
            int step = Math.min(100000,min_ch_size);

            // TODO: should be this an config value?
            int max_attempts = 5*target_channel_openings;

            while (max_attempts-- >0 && node.getChannelOpenings() < target_channel_openings) {
                if (max_attempts==0) log("WARNING: Exiting bootstrap due to max attempts reached...");
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
                    continue;
                }

                boolean opened = false;
                int peer_retries = 50;
                var target_profile = uvConfig.selectProfileBy(thread_rng,"hubness");

                // once the channel and target profiles are defined, try to open a channel...
                while (peer_retries-- >0 && !opened ) {
                    String some_random_key;
                    UVNode some_node;

                    synchronized (pubkeys_list) {
                        var n = thread_rng.nextInt(pubkeys_list.size());
                        some_random_key = pubkeys_list.get(n);
                        if (some_random_key==null)  continue;
                        some_node = uvnodes.get(some_random_key);
                    }

                    if (some_node == null) {
                        log("WARNING: Null value for key "+some_random_key);
                        // This should now be extremely rare / symptomatic of something else.
                        // Keeping a defensive check is cheap, though.
                        continue;
                    }
                    if (node.hasChannelWith(some_random_key)) {
                        continue;
                    }

                    if (some_node.getProfile().equals(target_profile) && !some_random_key.equals(node.getPubKey())) {
                        opened = node.openChannel(some_random_key,newChannelSize);
                    }
                }
            } // while

            log("BOOTSTRAP: Completed on "+node.getPubKey());
            decreaseBootStrapCount();
        } catch (Throwable t) {
            recordFailure("BOOTSTRAP ERROR on "+node.getPubKey(), t);
            decreaseBootStrapCount();
        } finally {
            log("BOOTSTRAP: Completed on "+node.getPubKey()+" (finally block)");
        }
    }

    private synchronized void decreaseBootStrapCount() {
        bootstraps_running--;
        bootstraps_ended++;
        getBootstrapLatch().countDown();
    }


    public void generateInvoiceEvents(double rate, int blocks, int minAmount, int maxAmount, int maxFees) {
        if (isImportedObserverView()) throw new IllegalStateException("Use root-origin traffic in observer-view mode");
        runStandardBatch(rate, blocks, minAmount, maxAmount, maxFees, null);
    }

    public void generateRootInvoiceEvents(double rate, int blocks, int minAmount, int maxAmount, int maxFees) {
        if (!isImportedObserverView()) throw new IllegalStateException("Root traffic requires observer-view mode");
        runStandardBatch(rate, blocks, minAmount, maxAmount, maxFees, getUVNode(imported_rootnode_graph));
    }

    private void runStandardBatch(double rate, int blocks, int minAmount, int maxAmount, int maxFees, UVNode sender) {
        if (!isBootstrapCompleted()) throw new IllegalStateException("Initialize the network before traffic");
        try {
            generateInvoiceBatch("batch-" + (++batchSequence), rate, blocks, minAmount, maxAmount, maxFees, DEFAULT_DRAIN_TIMEOUT_MS, sender);
            resume();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted invoice batch", e);
        }
    }

    /* set the channel balances of each node to the level
     Notice: for each channel only the initiators modify the balance
     */

    public void setLocalBalances(double local_level, int min_delta) {
        withSettledState(() -> { setLocalBalancesInternal(local_level, min_delta); return null; });
    }

    private void setLocalBalancesInternal(double local_level, int min_delta) {

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
        withSettledState(() -> { setRandomLiquidityInternal(min_delta); return null; });
    }

    private void setRandomLiquidityInternal(int min_delta) {

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
