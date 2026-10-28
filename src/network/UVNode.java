package network;

import message.*;
import topology.ChannelGraph;
import protocol.*;
import stats.*;
import misc.*;
import topology.Path;
import topology.PathFinder;
import topology.PathFinderFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

public class UVNode implements LNode, Serializable,Comparable<UVNode> {
    // Automated invoice generation runs many concurrent searches; keep the per-invoice frontier bounded.
    private static final int MAX_CANDIDATE_PATHS_PER_INVOICE = 64;

    private final UVConfig.NodeProfile profile;

    @Serial
    private static final long serialVersionUID = 120675L;

    private final String pubkey;
    private final String alias;
    private int onchainBalance;
    private int onchainPending = 0;
    private long last_gossip_flush = 0;
    private long last_mempool_check = 0;
    private int channel_openings = 0;
    private long openingSequence;
    private Random local_rnd_generator;

    transient private UVNetwork uvNetwork;
    transient private ChannelGraph channelGraph;
    transient private PathFinder pathFinder;
    private PathFinderFactory.Strategy savedPathFinderStrategy = PathFinderFactory.Strategy.BFS;
    transient private ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    transient private ConcurrentHashMap<String, UVNode> peers = new ConcurrentHashMap<>();
    transient private boolean p2pIsRunning = false;

    // p2p and messages
    transient private ArrayList<String> saved_peers_id;
    transient public ScheduledFuture<?> p2pHandler;
    transient private Queue<GossipMsg> GossipMessageQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgOpenChannel> channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgAcceptChannel> channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgRejectChannel> channelsRejectedQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<UVChannel> fundingLockedQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateAddHTLC> updateAddHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateFulFillHTLC> updateFulFillHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateFailHTLC> updateFailHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private ConcurrentHashMap<Long, LNInvoice> generatedInvoices = new ConcurrentHashMap<>();
    transient private ConcurrentHashMap<String, LNInvoice> pendingInvoices = new ConcurrentHashMap<>();
    transient private HashMap<String, LNInvoice> payedInvoices = new HashMap<>();
    transient private HashMap<String, MsgUpdateAddHTLC> receivedHTLC = new HashMap<>();
    transient private ConcurrentHashMap<String, MsgUpdateAddHTLC> pendingHTLC = new ConcurrentHashMap<>();
    transient private HashMap<String, MsgOpenChannel> sentChannelOpenings = new HashMap<>();
    transient private Set<String> pendingAcceptedChannelPeers = ConcurrentHashMap.newKeySet();
    // associate a tx with its broadcast height
    transient private HashMap<UVTransaction,Integer> waitingTxConf = new HashMap<>();

    transient GlobalStats.NodeStats nodeStats = new GlobalStats.NodeStats();
    // temporary store for an invoice failure reason
    transient private Map<String,String> failure_reason = new HashMap<>();
    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     *
     * @param pubkey  the public key to be used as node id
     * @param alias   an alias
     * @param funding initial onchain balance
     */
    public UVNode(UVNetwork network, String pubkey, String alias, int funding, UVConfig.NodeProfile profile) {
        this.uvNetwork = network;
        this.pubkey = pubkey;
        this.alias = alias;
        updateOnChainBalance(funding);
        channelGraph = new ChannelGraph(pubkey);
        pathFinder = PathFinderFactory.of(PathFinderFactory.Strategy.BFS, uvNetwork.getConfig());
        this.profile = profile;
    }

    public void setPathFinder(PathFinder pathFinder) {
        this.pathFinder = pathFinder;
        this.savedPathFinderStrategy = PathFinderFactory.strategyOf(pathFinder);
    }

    public PathFinder.SearchResult findPaths(String start, String end, int topk) {
        return pathFinder.findPaths(this.channelGraph, start,end,topk);
    }

    private PathFinder newSearchPathFinder(int paymentAmountSat) {
        // Invoice routing must not mutate the node-shared path finder while other searches are in flight.
        var searchPathFinder = PathFinderFactory.of(PathFinderFactory.strategyOf(pathFinder), uvNetwork.getConfig());
        searchPathFinder.setPaymentAmount(paymentAmountSat);
        return searchPathFinder;
    }

    public PathFinder getPathFinder() {
        return pathFinder;
    }

    public GlobalStats.NodeStats getNodeStats() {
        return nodeStats;
    }

    public UVConfig.NodeProfile getProfile() {
        return profile;
    }
    public Queue<GossipMsg> getGossipMessageQueue() {
        return GossipMessageQueue;
    }
    public synchronized int getChannelOpenings() {
        return channel_openings;
    }
    public synchronized void increaseChannelOpenings() {
        channel_openings++;
    }
    public Queue<MsgOpenChannel> getChannelsToAcceptQueue() {
        return channelsToAcceptQueue;
    }
    public Queue<MsgAcceptChannel> getChannelsAcceptedQueue() {
        return channelsAcceptedQueue;
    }
    public Queue<MsgUpdateAddHTLC> getUpdateAddHTLCQueue() {
        return updateAddHTLCQueue;
    }
    public ConcurrentHashMap<Long, LNInvoice> getGeneratedInvoices() {
        return generatedInvoices;
    }
    public HashMap<String, MsgUpdateAddHTLC> getReceivedHTLC() {
        return receivedHTLC;
    }
    public HashMap<String, MsgOpenChannel> getSentChannelOpenings() {
        return sentChannelOpenings;
    }
    public Queue<MsgUpdateFulFillHTLC> getUpdateFulFillHTLCQueue() {
        return updateFulFillHTLCQueue;
    }
    public Queue<MsgUpdateFailHTLC> getUpdateFailHTLCQueue() {
        return updateFailHTLCQueue;
    }
    public ConcurrentHashMap<String, LNInvoice> getPendingInvoices() {
        return pendingInvoices;
    }
    public synchronized HashMap<String, LNInvoice> getPayedInvoices() {
        return new HashMap<>(payedInvoices);
    }
    public ConcurrentHashMap<String, MsgUpdateAddHTLC> getPendingHTLC() {
        return pendingHTLC;
    }
    public Set<String> getPendingAcceptedChannelPeers() {
        return pendingAcceptedChannelPeers;
    }

    private void log(String s) {
        uvNetwork.log(this.getPubKey() + ':' + s);
    }
    private void debug(Supplier<String> messageSupplier) {
        if (uvNetwork.getConfig().debug) {
            log("_DEBUG_" + messageSupplier.get());
        }
    }

    public ArrayList<LNChannel> getLNChannelList() {
        return new ArrayList<>(this.channels.values());
    }
    public ConcurrentHashMap<String, UVNode> getPeers() {
        return peers;
    }
    public ChannelGraph getChannelGraph() {
        return this.channelGraph;
    }
    public String getPubKey() {
        return pubkey;
    }
    public ArrayList<GlobalStats.NodeStats.InvoiceReport> getInvoiceReports() {
        synchronized (nodeStats.invoiceReports) { return new ArrayList<>(nodeStats.invoiceReports); }
    }
    @Override
    public String getAlias() {
        return alias;
    }


    public void setLocalRndGenerator(Random local_rnd_generator) {
        this.local_rnd_generator = local_rnd_generator;
    }

    public Random getLocalRndGenerator() {
        return local_rnd_generator;
    }

    private UVNode getChannelPeer(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return uvNetwork.getUVNode(channel.getNode2PubKey());
        else
            return uvNetwork.getUVNode(channel.getNode1PubKey());
    }

    public synchronized int getOnChainBalance() {
        return onchainBalance;
    }
    private synchronized void updateOnChainBalance(int new_balance) {
        this.onchainBalance = new_balance;
    }
    public synchronized int getOnchainPending() {
        return onchainPending;
    }
    private synchronized void updateOnchainPending(int pending) {
        this.onchainPending = pending;
    }
    public synchronized int getOnchainLiquidity() {
        return getOnChainBalance() - getOnchainPending();
    }
    public ConcurrentHashMap<String, UVChannel> getChannels() {
        return channels;
    }

    public UVChannel getRandomChannel() {
        var some_channel_id = (String) channels.keySet().toArray()[local_rnd_generator.nextInt(channels.size())];
        return channels.get(some_channel_id);
    }
    /**
     * @return the sum of all balances on node side
     */
    public long getLocalBalance() {
        long balance = 0;

        for (UVChannel c : channels.values()) {
            balance += c.getBalance(this.getPubKey());
        }
        return balance;
    }
    public long getRemoteBalance() {
        long balance = 0;

        for (UVChannel c : channels.values()) {
            var peer_id = getChannelPeer(c.getChannelId()).getPubKey();
            balance += c.getBalance(peer_id);
        }
        return balance;
    }

    @Override
    public long getNodeCapacity() {
        long capacity = 0;
        for (var ch: this.channels.values()) capacity+=ch.getCapacity();
        return capacity;
    }

    /**
     * @param uvm
     */
    public void setUVM(UVNetwork uvm) {
        this.uvNetwork = uvm;
        if (pathFinder == null) {
            pathFinder = PathFinderFactory.of(savedPathFinderStrategy == null ? PathFinderFactory.Strategy.BFS : savedPathFinderStrategy, uvm.getConfig());
        }
    }

    /** The compatibility flag no longer derives identity from a message hash. Issuance order
     * provides reproducible unique preimages without consuming the workload RNG. */
    @Override
    public LNInvoice generateInvoice(int amount, String message, boolean deterministic) {
        return generateInvoice(amount, message);
    }

    public LNInvoice generateInvoice(int amount, String message) {
        try (var mutation = uvNetwork.beginMutation()) {
            return generateInvoiceWithIdentity(amount, message, uvNetwork.nextInvoiceIdentity());
        }
    }

    /** Explicit identities support replayed request schedules and are unique network-wide. */
    public LNInvoice generateInvoiceWithIdentity(int amount, String message, long identity) {
        try (var mutation = uvNetwork.beginMutation()) {
            if (amount <= 0) throw new IllegalArgumentException("Positive invoice amount required");
            uvNetwork.reserveInvoiceIdentity(identity);
            var hash = CryptoKit.bytesToHexString(CryptoKit.sha256(BigInteger.valueOf(identity).toByteArray()));
            var invoice = new LNInvoice(hash, amount, pubkey, message);
            if (generatedInvoices.putIfAbsent(identity, invoice) != null) throw new IllegalStateException("Duplicate invoice identity");
            return invoice;
        }
    }

    /** Snapshot under the same lock as forwarding/confirmation handlers. A joined-worker
     * recheck is still mandatory: an empty queue alone does not prove no handler is active.
     */
    public synchronized Map<String, Integer> unsettledState() {
        Map<String, Integer> state = new TreeMap<>();
        state.put("invoice", pendingInvoices.size());
        state.put("outgoing_htlc", pendingHTLC.size());
        state.put("received_htlc", receivedHTLC.size());
        state.put("open_requests", sentChannelOpenings.size());
        state.put("accepted_peers", pendingAcceptedChannelPeers.size());
        state.put("confirmations", waitingTxConf.size());
        state.put("open_queue", channelsToAcceptQueue.size());
        state.put("accept_queue", channelsAcceptedQueue.size());
        state.put("reject_queue", channelsRejectedQueue.size());
        state.put("funding_locked_queue", fundingLockedQueue.size());
        state.put("add_queue", updateAddHTLCQueue.size());
        state.put("fulfill_queue", updateFulFillHTLCQueue.size());
        state.put("fail_queue", updateFailHTLCQueue.size());
        state.put("gossip_queue", GossipMessageQueue.size());
        state.put("onchain_pending_sat", onchainPending);
        for (UVChannel channel : channels.values()) {
            state.put("channel_pending:" + channel.getChannelId(), channel.getPending(pubkey));
        }
        state.values().removeIf(value -> value == 0);
        return state;
    }

    private boolean checkPathCapacity(Path path, int amount) {

        for (ChannelGraph.Edge e : path.edges()) {
            if (e.capacity() < amount) return false;
        }
        return true;
    }

    public boolean checkPathPolicies(Path path) {
        for (ChannelGraph.Edge e : path.edges()) {
            if (e.policy() == null) {
                debug(()->"WARNING: Invalid path, missing policy on edge " + e);
                return false;
            }
        }
        return true;
    }

    public boolean checkOutboundPathLiquidity(Path path, int amount) {

        var firstChannelId = getMyChannelWith(path.getEnd());
        var firstChannel = channels.get(firstChannelId);
        debug(()->"Checking outbound path liquidity (amt:" + amount + ")" + (path) + " for first channel " + firstChannel);

        // this is the only liquidity check that can be performed in advance
        var senderLiquidity = firstChannel.getLiquidity(this.getPubKey());

        if (senderLiquidity < amount) {
            debug(()->"Discarding route for missing liquidity in first channel " + firstChannel);
            return false;
        }

        debug(()->"Liquidity ok for " + firstChannelId + " (required " + amount + " of " + senderLiquidity + ")");
        return true;
    }

    private int computeFees(int amount, int base_fee_msat, int fee_ppm_rate) {
        double fee_ppm = ((double) amount / 1e6) * fee_ppm_rate; // Ensure floating-point division
        double base_fee = base_fee_msat / 1000.0;                // Convert msats to sats correctly
        return (int) Math.ceil(fee_ppm + base_fee);              // Round up to avoid undercharging
    }


    private int getPathFees(Path path, int amount) {
        int fees = 0;

        for (ChannelGraph.Edge e : path.forwardingEdges()) {
            fees += computeFees(amount, e.policy().getBaseFee(), e.policy().getFeePpm());
        }
        return fees;
    }

    /**
     * This function processes a given Lightning Network Invoice by routing through all possible valid candidate paths
     * from sender's public key to invoice's destination and tries to route the payment. It also keeps the track of stats regarding
     * invoicing process. Lastly, it removes the processed invoice from pending invoices, updates the failure reason, if failed,
     * and logs a detailed report of the invoicing process.
     *
     * @param invoice The Lightning Network Invoice to be processed
     * @param max_fees The maximum limit of fees that can be utilized for invoice processing
     * @param showui A flag determining whether the routing details should be displayed in the console or not
     */
    public GlobalStats.NodeStats.InvoiceReport processInvoice(LNInvoice invoice, int maxFees, boolean showUi) {
        uvNetwork.beginPayment(invoice.getHash());
        try {
            var report = processInvoiceReport(invoice, maxFees, showUi);
            uvNetwork.requireHealthy();
            return report;
        } catch (RuntimeException | Error error) {
            uvNetwork.recordFailure("Payment processing failed for " + invoice.getHash(), error);
            throw error;
        } finally { uvNetwork.endPayment(); }
    }

    public GlobalStats.NodeStats.InvoiceReport processInvoice(LNInvoice invoice, int maxFees) {
        return processInvoice(invoice, maxFees, false);
    }

    private GlobalStats.NodeStats.InvoiceReport processInvoiceReport(LNInvoice invoice, int max_fees, boolean showui) {

        log("Processing Invoice " + invoice);
        if (uvNetwork.isImportedObserverView() && !getPubKey().equals(uvNetwork.getImportedRootNodeGraph())) {
            String logMessage = "Invoice routing disabled for " + getPubKey()
                    + " in imported observer-view mode; valid sender is root "
                    + uvNetwork.getImportedRootNodeGraph();
            log(logMessage);
            if (showui) System.out.println(logMessage);
            throw new IllegalStateException(logMessage);
        }

        // Keep post-search filtering separate from pathfinder-internal pruning stats.
        int filtered_policy = 0;
        int filtered_capacity = 0;
        int filtered_local_liquidity = 0;
        int filtered_max_fees = 0;

        String logMessage;

        var searchResult = newSearchPathFinder(invoice.getAmount())
                .findPaths(this.channelGraph, this.getPubKey(), invoice.getDestination(), MAX_CANDIDATE_PATHS_PER_INVOICE);
        var searchStats = searchResult.stats();
        var pathFinderStrategy = PathFinderFactory.strategyOf(pathFinder).name().toLowerCase(Locale.ROOT);
        List<Path> candidatePaths = new ArrayList<>();

        for (var pathDetails : searchResult.paths()) {
            var path = pathDetails.path();

            if (!checkPathPolicies(path)) {
                filtered_policy++;
                continue;
            }

            if (!checkPathCapacity(path, invoice.getAmount())) {
                debug(()->"Discarding path: missing capacity" + (path));
                filtered_capacity++;
                continue;
            }

            // please notice that this does not exclude further missing local liquidy events
            // that will happen at the momennt of actual reservation, and will be accoutned in another place
            if (!checkOutboundPathLiquidity(path, invoice.getAmount())) {
                debug(()->"Discarding path: missing liquidity on local channel" +(path));
                filtered_local_liquidity++;
                continue;
            }

            if (getPathFees(path, invoice.getAmount()) > max_fees) {
                debug(()->"Discarding path: missing max fees " + (path));
                filtered_max_fees++;
                continue;
            }

            candidatePaths.add(path);
        }

        boolean success_htlc = false;
        int attempted_paths = 0;
        int attempt_failed_expiry_too_soon = 0;
        int attempt_failed_temporary_channel = 0;
        int attempt_failed_local_liquidity = 0;
        int attempt_failed_timeout = 0;
        int attempt_failed_unknown = 0;

        if (!candidatePaths.isEmpty()) {

            final var hash = invoice.getHash();

            logMessage = "Found " + candidatePaths.size() + " paths for " + hash;
            log(logMessage);
            if (showui) System.out.println(logMessage);

            //  conservative large delay set to the same as p2p queue updates
            final long invoice_check_delay = uvNetwork.getConfig().node_services_tick_ms;

            pendingInvoices.put(hash, invoice);

            for (var path : candidatePaths) {
                synchronized (this) { failure_reason.remove(hash); }
                attempted_paths++;
                logMessage = "Trying path " + attempted_paths + " of " + candidatePaths.size() + " for invoice " + hash;
                if (showui) System.out.println(logMessage);
                log(logMessage);

                routeInvoiceOnPath(invoice, path);
                debug(()->"Waiting for pending HTLC "+invoice.getHash());

                // A host watchdog detects a stalled simulator; block expiry resolves the contract.
                // Continued block progress must not turn a valid long-lived HTLC into a host timeout.
                long watchdogMs = Math.max(uvNetwork.getConfig().getHTLCStallTimeoutMs(),
                        Math.max(2L * uvNetwork.getConfig().blocktime_ms,
                                8L * Math.max(1, path.getSize()) * uvNetwork.getConfig().node_services_tick_ms));
                long lastProgress = System.nanoTime();
                int lastHeight = uvNetwork.getTimechain().getCurrentBlockHeight();
                synchronized (this) {
                    while (pendingHTLC.containsKey(hash)) {
                        uvNetwork.checkPaymentProgress();
                        int height = uvNetwork.getTimechain().getCurrentBlockHeight();
                        if (height != lastHeight) { lastHeight = height; lastProgress = System.nanoTime(); }
                        if (System.nanoTime() - lastProgress >= TimeUnit.MILLISECONDS.toNanos(watchdogMs)) {
                            throw new IllegalStateException("HTLC resolution stalled without block progress: " + hash);
                        }
                        try { wait(Math.max(1, Math.min(invoice_check_delay, watchdogMs))); }
                        catch (InterruptedException error) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException("Payment interrupted with unresolved state: " + hash, error);
                        }
                    }
                }

                boolean paid;
                synchronized (this) { paid = payedInvoices.containsKey(hash); }
                if (paid) {
                    String successMessage = " Successfully routed invoice " + hash;
                    if (showui) System.out.println(successMessage);
                    log(successMessage);

                    success_htlc = true;
                    break; // exit the for cycle of trying the paths...
                } else {
                    String reason;
                    synchronized (this) { reason = failure_reason.get(hash); }

                    if (reason==null) reason = "unknown";

                    // TOFIX: reason should not be null
                    switch (reason) {
                        case "expiry_too_soon":
                            attempt_failed_expiry_too_soon++;
                            break;
                        case "temporary_channel_failure":
                            attempt_failed_temporary_channel++;
                            break;
                        case "missing local liquidity":
                            attempt_failed_local_liquidity++;
                            break;
                        case "attempt_timeout":
                            attempt_failed_timeout++;
                            break;
                        default:
                            debug(()->"No reason for failure of "+hash);
                            attempt_failed_unknown++;
                            break;
                    }
                    if (!uvNetwork.isHealthy()) break;
                    logMessage = "Could not route invoice: " + hash+ ", "+reason+ " on path #"+attempted_paths;
                    log(logMessage);
                    if (showui) System.out.println(logMessage);
                }
            }
        }

        if (!success_htlc) {
            logMessage = "Invoice Routing Failed! No viable candidate paths found for invoice " + invoice.getHash();
            log(logMessage);
            if (showui) System.out.println(logMessage);
        }

        debug(()->"Removing pending invoice for "+invoice.getHash());

        // the invoice, success or not, is not useful anymore
        if (uvNetwork.isHealthy()) {
            pendingInvoices.remove(invoice.getHash());
            synchronized (this) { failure_reason.remove(invoice.getHash()); }
        }

        // The invoice CSV is stage-oriented: search, filtering, then actual routing attempts.
        var report = new GlobalStats.NodeStats.InvoiceReport(
                CryptoKit.shortString(invoice.getHash()),
                this.getPubKey(),
                invoice.getDestination(),
                invoice.getAmount(),
                max_fees,
                pathFinderStrategy,
                MAX_CANDIDATE_PATHS_PER_INVOICE,
                searchStats.returnedPaths(),
                searchStats.investigatedStates(),
                searchStats.expandedEdges(),
                searchStats.excludedByCapacity(),
                searchStats.excludedByVisitedState(),
                searchStats.excludedByCycle(),
                searchStats.excludedByMaxHops(),
                searchStats.excludedByCost(),
                filtered_policy,
                filtered_capacity,
                filtered_local_liquidity,
                filtered_max_fees,
                candidatePaths.size(),
                attempted_paths,
                attempt_failed_temporary_channel,
                attempt_failed_expiry_too_soon,
                attempt_failed_local_liquidity,
                attempt_failed_timeout,
                attempt_failed_unknown,
                success_htlc);

        synchronized (nodeStats.invoiceReports) { nodeStats.invoiceReports.add(report); }
        return report;
    }

    /**
     * This method is responsible for routing an invoice through a specific path in the Lightning Network.
     *
     * The method expects an protocol.LNInvoice instance and an ArrayList of topology.ChannelGraph.Edge objects
     * which represent the path for routing the payment in the Lightning Network.
     *
     */

    private synchronized void routeInvoiceOnPath(LNInvoice invoice, Path path) {
        log("Routing on path:"+ path);
        // if Alice is the sender, and Dina the receiver: paths = Dina, Carol, Bob, Alice

        final int amount = invoice.getAmount();
        final int baseBlockHeight = uvNetwork.getTimechain().getCurrentBlockHeight();
        final var finalCLTV = baseBlockHeight+invoice.getMinFinalCltvExpiry();

        // last layer is the only one with the secret
        var firstHopPayload = new OnionLayer.Payload("00",amount,finalCLTV,invoice.getHash());
        // this is the inner layer, for the final destination, so no further inner layer
        var firstOnionLayer = new OnionLayer(firstHopPayload,null);

        int cumulatedFees = 0;
        var onionLayer = firstOnionLayer;
        int out_cltv = finalCLTV;

        // we start with the payload for Carol, which has no added fee to pay because Dina is the final hop
        // Carol will take the forwarding fees specified in the payload for Bob, which will instruct Bob about the HTLC to be sent to Carol
        // don't need to create the last path segment onion for local node htlc
        for (int n=0;n<path.edges().size()-1;n++) {
            var source = path.edges().get(n).source();
            var dest = path.edges().get(n).destination();

            // TODO UVMODEL: used only by node, should be a service from the node itself ?
            // point to clarify: it is used for searching own nodes or in general ?
            var path_channel = uvNetwork.getChannelFromNodes(source,dest);
            if (path_channel.isPresent()) {
                var channel = path_channel.get();
                var hopPayload = new OnionLayer.Payload(channel.getChannelId(),amount+cumulatedFees,out_cltv,null);
                onionLayer = new OnionLayer(hopPayload,onionLayer);

                // the fees in the carol->dina channel will be put in the Bob payload in the next loop
                // because it's bob that has to advance the fees to Carol  (Bob will do same with Alice)
                cumulatedFees += computeFees(amount,channel.getPolicy(source).getBaseFee(),channel.getPolicy(source).getFeePpm());
                out_cltv += channel.getPolicy(source).getCLTVDelta();
            }
            else {
                throw new IllegalStateException("ERROR: No channel from "+source+ " to "+dest);
            }
        }

        var first_hop = path.getEnd();
        var channel_id = path.edges().getLast().id();
        var local_channel = channels.get(channel_id);
        var amt_to_forward= invoice.getAmount()+cumulatedFees;

        var message = new MsgUpdateAddHTLC(channel_id, local_channel.getNextHTLCid(), amt_to_forward,
                invoice.getHash(), out_cltv, onionLayer);
        if (!uvNetwork.htlcResolution().offer(local_channel, pubkey, message, null)) {
            failure_reason.put(invoice.getHash(), "missing local liquidity");
            return;
        }
        pendingHTLC.put(message.getPayment_hash(), message);
        sendToPeer(uvNetwork.getUVNode(first_hop), message, uvNetwork);
    }

    private synchronized void processUpdateFulfillHTLC(MsgUpdateFulFillHTLC message) {
        uvNetwork.htlcResolution().acceptFulfillment(pubkey,
                new HTLCResolution.Key(message.getChannel_id(), message.getId()), message.getPayment_preimage());
        reconcileHTLCs();
    }

    private synchronized void processUpdateFailHTLC(MsgUpdateFailHTLC message) {
        uvNetwork.htlcResolution().acceptFailure(pubkey,
                new HTLCResolution.Key(message.getChannel_id(), message.getId()));
        reconcileHTLCs();
    }

    private void sendHTLCOutcome(MsgUpdateAddHTLC incoming, HTLCResolution.Outcome outcome) {
        if (outcome.state() == HTLCResolution.State.FULFILLED) {
            sendToPeer(getChannelPeer(incoming.getChannel_id()),
                    new MsgUpdateFulFillHTLC(incoming.getChannel_id(), incoming.getId(), outcome.preimage()), uvNetwork);
        } else if (outcome.state() == HTLCResolution.State.FAILED) {
            sendToPeer(getChannelPeer(incoming.getChannel_id()),
                    new MsgUpdateFailHTLC(incoming.getChannel_id(), incoming.getId(), outcome.reason()), uvNetwork);
        }
    }

    private void failHTLC(MsgUpdateAddHTLC message, String reason) {
        var before = uvNetwork.htlcResolution().receive(pubkey, message);
        var outcome = uvNetwork.htlcResolution().fail(pubkey, message, reason);
        if (before.state() == HTLCResolution.State.PENDING && outcome.state() == HTLCResolution.State.FAILED) {
            nodeStats.incrementForwardingFailures(reason);
        }
        sendHTLCOutcome(message, outcome);
    }

    /** Apply authoritative channel outcomes once, from normal P2P or chain observation. */
    synchronized void reconcileHTLCs() {
        var resolver = uvNetwork.htlcResolution();
        for (var outgoing : new ArrayList<>(pendingHTLC.values())) {
            var outcome = resolver.outcome(outgoing);
            if (outcome == null || outcome.state() == HTLCResolution.State.PENDING) continue;
            String hash = outgoing.getPayment_hash();
            var incoming = receivedHTLC.get(hash);
            if (incoming != null) {
                var upstream = outcome.state() == HTLCResolution.State.FULFILLED
                        ? resolver.fulfill(pubkey, incoming, outcome.preimage())
                        : resolver.fail(pubkey, incoming, outcome.reason());
                sendHTLCOutcome(incoming, upstream);
                receivedHTLC.remove(hash);
                if (outcome.state() == HTLCResolution.State.FULFILLED) nodeStats.incrementForwardeVolume(incoming.getAmount());
            } else if (outcome.state() == HTLCResolution.State.FULFILLED) {
                var invoice = pendingInvoices.get(hash);
                if (invoice == null) throw new IllegalStateException("Missing pending invoice for resolved HTLC " + hash);
                payedInvoices.put(hash, invoice);
                nodeStats.incrementInvoiceProcessingSuccessses();
                nodeStats.incrementInvoiceProcessingVolume(invoice.getAmount());
            } else failure_reason.put(hash, outcome.reason());
            pendingHTLC.remove(hash, outgoing);
        }
        // Obsolete deliveries cannot revive a resolved contract, including while P2P is stopped.
        updateAddHTLCQueue.removeIf(message -> {
            var outcome = resolver.outcome(message);
            return outcome != null && outcome.state() != HTLCResolution.State.PENDING;
        });
        updateFulFillHTLCQueue.removeIf(message -> resolver.terminal(new HTLCResolution.Key(message.getChannel_id(), message.getId())));
        updateFailHTLCQueue.removeIf(message -> resolver.terminal(new HTLCResolution.Key(message.getChannel_id(), message.getId())));
        notifyAll();
    }

    private synchronized void processUpdateAddHTLC(MsgUpdateAddHTLC message) {
        var resolver = uvNetwork.htlcResolution();
        var known = resolver.receive(pubkey, message);
        if (known.state() != HTLCResolution.State.PENDING) { sendHTLCOutcome(message, known); return; }
        if (resolver.forwarded(message)) return; // Duplicate add; its original forwarding is still active.
        final var payload = message.getOnionPacket().getPayload();
        int currentBlock = uvNetwork.getTimechain().getCurrentBlockHeight();
        if (currentBlock >= message.getCLTVExpiry()) { failHTLC(message, "expiry_too_soon"); return; }

        if (payload.getShortChannelId().equals("00")) {
            for (var entry : generatedInvoices.entrySet()) {
                var invoice = entry.getValue();
                if (invoice.getHash().equals(message.getPayment_hash())
                        && invoice.getHash().equals(payload.getPayment_secret().orElse(null))) {
                    if (message.getAmount() < invoice.getAmount() || payload.getAmtToForward() != invoice.getAmount()) {
                        failHTLC(message, "temporary_channel_failure"); return;
                    }
                    var outcome = resolver.fulfill(pubkey, message, entry.getKey());
                    sendHTLCOutcome(message, outcome);
                    return;
                }
            }
            throw new IllegalStateException("No generated invoice matches the final HTLC");
        }

        var channel = channels.get(payload.getShortChannelId());
        var inner = message.getOnionPacket().getInnerLayer();
        if (channel == null || inner.isEmpty() || payload.getAmtToForward() <= 0
                || payload.getAmtToForward() > message.getAmount() || channel.getPolicy(pubkey) == null) {
            failHTLC(message, "temporary_channel_failure"); return;
        }
        int delta = channel.getPolicy(pubkey).getCLTVDelta();
        if (payload.getOutgoingCLTV() <= currentBlock
                || (long) message.getCLTVExpiry() - payload.getOutgoingCLTV() < delta
                || message.getCLTVExpiry() <= payload.getOutgoingCLTV()) {
            failHTLC(message, "expiry_too_soon"); return;
        }
        var outgoing = new MsgUpdateAddHTLC(channel.getChannelId(), channel.getNextHTLCid(), payload.getAmtToForward(),
                message.getPayment_hash(), payload.getOutgoingCLTV(), inner.get());
        if (!resolver.offer(channel, pubkey, outgoing, message)) {
            failHTLC(message, "temporary_channel_failure"); return;
        }
        receivedHTLC.put(message.getPayment_hash(), message);
        pendingHTLC.put(outgoing.getPayment_hash(), outgoing);
        nodeStats.incrementForwardingSuccesses();
        sendToPeer(getChannelPeer(channel.getChannelId()), outgoing, uvNetwork);
    }

    private String getMyChannelWith(String node_id) {

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id)) {
                    return c.getChannelId();
            }
        }
        throw new IllegalArgumentException(this.getPubKey()+" Has no channel with "+node_id);
    }

    public synchronized boolean hasChannelWith(String nodeId) {
        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(nodeId) || c.getNode1PubKey().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }
    private synchronized boolean hasPendingAcceptedChannelWith(String nodeId) {
        return pendingAcceptedChannelPeers.contains(nodeId);
    }

    private synchronized boolean hasOpeningRequestWith(String nodeId) {
        return sentChannelOpenings.containsKey(nodeId);
    }

    /**
     * Configure a channel for the node, replacing any existing one with same key
     * Mainly used for importing channels from a previously exported real topology, e.g. lncli describegraph
     * As compared to openChannel method using in bootstrapping;
     * - it does not create a new object
     * - it does not involve any further p2p action: e.g., asking to the peer to acknowledge the opening, broadcasting, updating channel graph
     * - it does not check actual onchain balances
     */
    public void configureChannel(UVChannel channel) {
        channels.put(channel.getChannelId(),channel);
    }

    private String generateTempChannelId(String peerPubKey) {
        openingSequence = Math.incrementExact(openingSequence);
        return pubkey + ":" + peerPubKey + ":" + openingSequence;
    }

    /**
     * Mapped to LN protocol message: open_channel
     *
     * @param peerPubKey the target node partner to open the channel
     */
    public boolean openChannel(String peerPubKey, int channel_size) {
        try (var mutation = uvNetwork.beginMutation()) {
            synchronized (this) { return openChannelInternal(peerPubKey, channel_size); }
        }
    }

    private boolean openChannelInternal(String peerPubKey, int channel_size) {
        if (channel_size <= 0 || channel_size > getOnchainLiquidity()) return false;
        if (peerPubKey.equals(this.getPubKey())) {
            log("WARNING:Cancelling openChannel, the peer selected is the node itself!");
            return false;
        }
        if (hasChannelWith(peerPubKey)) {
            log("WARNING:Cancelling openChannel, channel already present with peer "+peerPubKey);
            return false;
        }
        if (hasOpeningRequestWith(peerPubKey)) {
            log("WARNING:Cancelling openChannel, opening request already present with peer "+peerPubKey);
            return false;
        }
        if (hasPendingAcceptedChannelWith(peerPubKey)) {
            log("WARNING: Cancelling openChannel, pending accepted channel already present with peer "+peerPubKey);
            return false;
        }

        var peer = uvNetwork.getUVNode(peerPubKey);
        if (peer == null) throw new IllegalArgumentException("Unknown channel peer: " + peerPubKey);
        peers.putIfAbsent(peer.getPubKey(),peer);
        var tempChannelId = generateTempChannelId(peerPubKey);

        log("Opening channel to "+peerPubKey+ " (temp_id: "+tempChannelId+", size:"+channel_size+")");
        //TODO: the semantics of the related variable is tied to total openings, not the currently alive channels opened
        increaseChannelOpenings();

        debug(()->"Updating pending current: "+getOnchainPending()+" to "+(channel_size+getOnchainPending()));
        updateOnchainPending(getOnchainPending()+channel_size);

        //Both sides of the channel are forced by the other side to keep 1% of funds in the channel on their side. This is in order to ensure cheating always costs something (at least 1% of the channel balance).
        double reserve_fraction = 0.01;
        int reserve = (int)(reserve_fraction*channel_size);
        var msg_request = new MsgOpenChannel(tempChannelId,channel_size, reserve, 0, uvNetwork.getConfig().to_self_delay, this.pubkey);
        sentChannelOpenings.put(peerPubKey,msg_request);
        sendToPeer(peer, msg_request, uvNetwork);
        return true;
    }

    private synchronized void acceptChannel(MsgOpenChannel openRequest) {
        var temporary_channel_id = openRequest.getTemporary_channel_id();
        var initiator_id = openRequest.getFunding_pubkey();


        if (hasOpeningRequestWith(initiator_id)) {
            log("WARNING:Cannot accept channel, already opening request with "+initiator_id);
            rejectOpening(openRequest);
            return;
        }

        if (hasPendingAcceptedChannelWith(initiator_id)) {
            log("WARNING:Cannot accept channel, already pending accepted channel with "+initiator_id);
            rejectOpening(openRequest);
            return;
        }
        if (this.hasChannelWith(initiator_id)) {
            //throw  new IllegalStateException("Node "+this.getPubKey()+ " has already a channel with "+initiator_id);
            log("Warning: Cannot accept channel, already a channel with "+initiator_id);
            rejectOpening(openRequest);
            return;
        }

        if (initiator_id!=null) pendingAcceptedChannelPeers.add(initiator_id);
        else {
            uvNetwork.reportCritical("CRITICAL: initiator_id null in acceptChannel of node "+getPubKey()+" for "+openRequest);
            return;
        }


        log("Accepting channel "+ temporary_channel_id);
        var channel_peer = uvNetwork.getUVNode(initiator_id);

        if (channel_peer == null) {
            uvNetwork.reportCritical("CRITICAL: from node  "+this.getPubKey()+ " when acceptChannel, uvNetwork has no node for initiator_id " + initiator_id );
            return;
        }

        peers.putIfAbsent(channel_peer.getPubKey(),channel_peer);
        var acceptance = new MsgAcceptChannel(temporary_channel_id, uvNetwork.getConfig().minimum_depth, uvNetwork.getConfig().to_self_delay,this.getPubKey());
        sendToPeer(channel_peer,acceptance, uvNetwork);
    }


    private void rejectOpening(MsgOpenChannel request) {
        UVNode initiator = uvNetwork.getUVNode(request.getFunding_pubkey());
        if (initiator == null) throw new IllegalStateException("Unknown opening initiator");
        sendToPeer(initiator, new MsgRejectChannel(request.getTemporary_channel_id(), pubkey), uvNetwork);
    }

    private synchronized void channelRejected(MsgRejectChannel rejection) {
        var request = sentChannelOpenings.get(rejection.getPeer());
        if (request == null || !request.getTemporary_channel_id().equals(rejection.getTemporaryChannelId())) return;
        // A matching terminal rejection is valid only before funding was broadcast.
        if (waitingTxConf.keySet().stream().anyMatch(tx -> tx.getNode2Pub().equals(rejection.getPeer()))) {
            throw new IllegalStateException("Opening rejected after funding broadcast");
        }
        if (onchainPending < request.getFunding()) throw new IllegalStateException("Opening reservation does not reconcile");
        sentChannelOpenings.remove(rejection.getPeer());
        onchainPending -= request.getFunding();
    }

    private synchronized void channelAccepted(MsgAcceptChannel acceptMessage) {
        // annotate the current height, so to facilitate search into a limite block set
        final int broadcast_timestamp = uvNetwork.getTimechain().getCurrentBlockHeight();
        var temp_channel_id = acceptMessage.getTemporary_channel_id();
        var peerPubKey = acceptMessage.getFundingPubkey();


        var proposal = sentChannelOpenings.get(peerPubKey);
        if (proposal == null || !proposal.getTemporary_channel_id().equals(temp_channel_id)) return;
        if (waitingTxConf.keySet().stream().anyMatch(tx -> tx.getNode2Pub().equals(peerPubKey))) return;
        var funding_amount = proposal.getFunding();
        // TODO: pubkeys should be lexically ordered, not based on initiator
        int fees_per_byte = 1;
        var funding_tx = UVTransaction.createTx(UVTransaction.Type.CHANNEL_FUNDING,funding_amount,getPubKey(),peerPubKey,fees_per_byte);
        waitingTxConf.put(funding_tx,broadcast_timestamp);
        // No need to model the actual signatures with the two messages below, leaving placeholder for future extensions ;)
        // bolt: send funding_created
        // bolt: received funding_signed
        log("Channel accepted by "+ peerPubKey+ " ("+temp_channel_id+") Broadcasting funding tx: "+funding_tx.getTxId());
        uvNetwork.getTimechain().addToMempool(funding_tx);

    }

    private void fundingChannelConfirmed(String peer_id, String tx_id) {

        // Abstractions:
        // - This function is called on channel inititor, which will alert peer with BOLT funding_locked message:
        // - Actually, the peer should monitor onchain confirmation on its own, but no trust problem is modeledd in UV


        var timestamp = uvNetwork.getTimechain().getCurrentBlockHeight();
        var request = sentChannelOpenings.get(peer_id);
        sentChannelOpenings.remove(peer_id);
        var newChannel = UVChannel.buildFromProposal(this.getPubKey(),peer_id,request.getFunding(),request.getChannelReserve(),request.getPushMSAT());

        updateOnChainBalance(getOnChainBalance()- request.getFunding());
        updateOnchainPending(getOnchainPending()-request.getFunding());

        var channel_id = newChannel.getChannelId();
        channels.put(channel_id,newChannel);

        // in millisats
        int base_fee = uvNetwork.getConfig().getMultivalRandomItem(local_rnd_generator,"base_fee_set");

        int fee_ppm = this.profile.getRandomSample(local_rnd_generator,"ppm_fees");
        var newPolicy = new LNChannel.Policy(40,base_fee,fee_ppm);

        // local update
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        // why?
        //channelGraph.updateChannel(msg_update);

        channelGraph.addLNChannel(newChannel);

        String from = this.getPubKey();
        String signer = this.getPubKey();
        // when funding tx is confirmed after minimim depth
        var msg_update = new GossipMsgChannelUpdate(from,signer,channel_id,timestamp,0,newPolicy);
        var msg_announcement = new GossipMsgChannelAnnouncement(from,channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);

        log("Confirmed funding tx: "+tx_id + " for new channel: "+channel_id);
        uvNetwork.getUVNode(peer_id).fundingLocked(newChannel);
        broadcastToPeers(from,msg_announcement);
        broadcastToPeers(from,msg_update);
    }

    public void fundingLocked(UVChannel newChannel) { fundingLockedQueue.add(newChannel); }

    private void applyFundingLocked(UVChannel newChannel) {

        log("Received funding_locked for "+newChannel.getChannelId());

        pendingAcceptedChannelPeers.remove(newChannel.getInitiator());

        // setting a random policy
        int base_fee = uvNetwork.getConfig().getMultivalRandomItem(local_rnd_generator,"base_fee_set");
        int fee_ppm = this.profile.getRandomSample(local_rnd_generator,"ppm_fees");
        // TODO: set some delta cltv
        var newPolicy = new LNChannel.Policy(40,base_fee,fee_ppm);

        newChannel.setPolicy(getPubKey(),newPolicy);
        channels.put(newChannel.getChannelId(), newChannel);

        channelGraph.addLNChannel(newChannel);
        channelGraph.updateChannel(this.getPubKey(),newPolicy,newChannel.getChannelId());

        var timestamp = uvNetwork.getTimechain().getCurrentBlockHeight();
        // here sender is set as current node, all the other peers should receive the update
        String sender = this.getPubKey();
        var msg_update = new GossipMsgChannelUpdate(sender,this.getPubKey(),newChannel.getChannelId(),timestamp,0,newPolicy);

        // sender is set as the channel initiator, so that it's excluded from broadcasting
        var message_ann = new GossipMsgChannelAnnouncement(newChannel.getInitiator(),newChannel.getChannelId(),newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(newChannel.getInitiator(), message_ann);
        broadcastToPeers(sender,msg_update);
    }

   private void broadcastToPeers(String fromID, GossipMsg msg) {

       var current_age = uvNetwork.getTimechain().getCurrentBlockHeight() -msg.getTimeStamp();
       if (current_age> uvNetwork.getConfig().p2p_max_age) return;
       if (msg.getForwardings()>= uvNetwork.getConfig().p2p_max_hops)  return;

       for (UVNode peer: peers.values()) {
            if (peer.getPubKey().equals(fromID)) continue;
            sendToPeer(peer,msg, uvNetwork);
        }
    }

    private void sendToPeer(UVNode peer, P2PMessage msg, LNetwork network) {
       //debug("Sending message "+msg+ " to "+peer.getPubKey());
       //peer.deliverMessage(msg);
       network.deliverMessage(peer,msg);
    }

    // packege private to be accessible from network manager
    void deliverMessage(P2PMessage msg) {
        //debug("Received "+msg);
        switch (msg.getType()) {
            case OPEN_CHANNEL -> {
                var request = (MsgOpenChannel)msg;
                channelsToAcceptQueue.add(request);
            }
            case REJECT_CHANNEL -> channelsRejectedQueue.add((MsgRejectChannel) msg);
            case ACCEPT_CHANNEL -> {
                var acceptance = (MsgAcceptChannel)msg;
                channelsAcceptedQueue.add(acceptance);
            }
            case UPDATE_ADD_HTLC -> {
                var update_htlc = (MsgUpdateAddHTLC)msg;
                updateAddHTLCQueue.add(update_htlc);
            }
            case UPDATE_FULFILL_HTLC -> {
                var update_htlc = (MsgUpdateFulFillHTLC)msg;
                updateFulFillHTLCQueue.add(update_htlc);
            }
            case UPDATE_FAIL_HTLC -> {
                var htlc = (MsgUpdateFailHTLC)msg;
                updateFailHTLCQueue.add(htlc);
            }
            // assuming all the other message type are gossip
            default ->  {
                var message = (GossipMsg) msg;
                if (!GossipMessageQueue.contains(message)) {
                    GossipMessageQueue.add(message);
                }
            }
        }
    }

    public Queue<GossipMsg> getGossipMsgQueue() {
        return this.GossipMessageQueue;
    }

    public synchronized void setP2PServices(boolean status) {
       this.p2pIsRunning = status;
    }

    public synchronized boolean isP2PRunning() {
        return p2pIsRunning;
    }

    /**
     * Internal processing of the queue of p2p gossip messages
     * A max number of messages is processed periodically to avoid execessive overloading
     * Messages older than a given number of blocks are discarded
     */
    private void processGossip() {

        int max_msg = uvNetwork.getConfig().gossip_flush_size;

        while (isP2PRunning() && !GossipMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            GossipMsg msg = GossipMessageQueue.poll();

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var announce_msg = (GossipMsgChannelAnnouncement) msg.nextMsgToForward(this.getPubKey());
                    var new_channel_id = announce_msg.getChannelId();
                    if (!channelGraph.hasChannel(new_channel_id)) {
                        // synchronized
                        this.channelGraph.addAnnouncedChannel(announce_msg);
                        broadcastToPeers(msg.getSender(),announce_msg);
                    }

                }
                // TODO: 4 times per day, per channel (antonopoulos)
                case CHANNEL_UPDATE -> {
                    var message = (GossipMsgChannelUpdate) msg;
                    // skip channel updates of own channels
                    var updater_id = message.getSignerId();
                    var channel_id = message.getChannelId();

                    // sent from my channel partners, update related data
                    if (channels.containsKey(channel_id)) {
                        channels.get(channel_id).setPolicy(updater_id,message.getUpdatedPolicy());
                        getChannelGraph().updateChannel(message);
                        var next = message.nextMsgToForward(this.getPubKey());
                        broadcastToPeers(message.getSender(),next);
                    } // not my local channel, but I have an entry to be updated...
                    else {
                        //debug("Received update for non local channel ");
                        if (getChannelGraph().hasChannel(channel_id)) {
                            //debug("GOSSIP: Updating non-local channel "+channel_id);
                            getChannelGraph().updateChannel(message);
                            var next = message.nextMsgToForward(this.getPubKey());
                            broadcastToPeers(message.getSender(),next);
                        }
                        else {
                            //debug("GOSSIP: Skipping update for unknown channel "+channel_id);
                        }
                    }

                    //https://github.com/lightning/bolts/blob/master/07-routing-gossip.md#the-channel_update-message
                    /*
                    The receiving node:
                    if the short_channel_id does NOT match a previous channel_announcement, OR if the channel has been closed in the meantime:
                    MUST ignore channel_updates that do NOT correspond to one of its own channels.
                     */
                }
            }
        }
    }

    /**
     * All the services and actions that are periodically performed by the node
     * This includes only the p2p, gossip network, not the LN protocol actions like channel open, routing etc..
     * Starting from a base services_tick_period, different frequencies can be used
     */
    public synchronized void runServices() {
        if (!isP2PRunning() || !uvNetwork.isHealthy()) return;
        try {

            // handled as soon as possible, just check at very services tick
            processChannelsMsgQueue();

            // handled as soon as possible, just check at very services tick
            processHTLCMsgQueue();

            long now = System.currentTimeMillis();
            // should run around the same frequency of blocktime
            if (now-last_mempool_check >= 3L *uvNetwork.getConfig().blocktime_ms) {
                checkTimechainTxConfirmations();
                last_mempool_check = now;
            }
            // to avoid spam and network congestion, gossid should spread less frequenctly
            if (now - last_gossip_flush >= uvNetwork.getConfig().gossip_flush_period_ms) {
                processGossip();  // or flushGossip()
                last_gossip_flush = now;
            }
        }
        catch (Exception e) {
            uvNetwork.recordFailure("runServices on node " + getPubKey(), e);
        }
    }

    private synchronized void checkTimechainTxConfirmations() {
        int req_confirmations = uvNetwork.getConfig().minimum_depth;

        if (!waitingTxConf.isEmpty()) {
            final int current_block = uvNetwork.getTimechain().getCurrentBlockHeight();
            var to_be_removed = new ArrayList<UVTransaction>();

            for (var tx: waitingTxConf.keySet()) {
                var broadcast_height = waitingTxConf.get(tx);
                // avoid checking for txs that are too recent
                if (current_block-broadcast_height<req_confirmations) continue;
                debug(()->"Checking confirmation of "+tx.getTxId()+ " starting from block "+broadcast_height);
                var location = uvNetwork.getTimechain().findTxLocation(tx,broadcast_height);
                if (location.isPresent() && current_block - location.get().height() >=req_confirmations) {
                    String peerId;
                    if (tx.getNode1Pub().equals(this.getPubKey())) peerId = tx.getNode2Pub();
                    else peerId = tx.getNode1Pub();
                    fundingChannelConfirmed(peerId,tx.getTxId());
                    to_be_removed.add(tx);
                }
            }
            for (var tx: to_be_removed) {
                waitingTxConf.remove(tx);
            }
        }
    }

    private void processChannelsMsgQueue() {

        // TODO: this is enough for sure, but maybe move to some config constansts
        final int max = 20;
        int n = 0;
        while (!fundingLockedQueue.isEmpty() && n++ < max) applyFundingLocked(fundingLockedQueue.poll());
        n = 0;
        while (!channelsRejectedQueue.isEmpty() && n++ < max) channelRejected(channelsRejectedQueue.poll());
        n = 0;

        while (!channelsAcceptedQueue.isEmpty() && n++ < max) {
            var msg = channelsAcceptedQueue.poll();
            channelAccepted(msg);
        }
        n = 0;

        while (!channelsToAcceptQueue.isEmpty() && n++ < max ) {
            var msg = channelsToAcceptQueue.poll();
            acceptChannel(msg);
        }
    }

    private void processHTLCMsgQueue() {
        // TODO: this is enough for sure, but maybe move to some config constansts
        final int max = 20;
        int n = 0;

        while (!updateAddHTLCQueue.isEmpty() && n++ < max ) {
            var msg = updateAddHTLCQueue.poll();
            try {
                processUpdateAddHTLC(msg);
            }
            catch (Exception e) { uvNetwork.recordFailure("processUpdateAddHTLC on node " + getPubKey(), e); }
        }

        n = 0;
        while (!updateFulFillHTLCQueue.isEmpty() && n++ < max ) {
            var msg = updateFulFillHTLCQueue.poll();
            try {
                processUpdateFulfillHTLC(msg);
            } catch (Exception e) { uvNetwork.recordFailure("processUpdateFulfillHTLC on node " + getPubKey(), e); }
        }
        n = 0;
        while (!updateFailHTLCQueue.isEmpty() && n++ < max ) {
            var msg = updateFailHTLCQueue.poll();
            try {
                processUpdateFailHTLC(msg);
            } catch (Exception e) { uvNetwork.recordFailure("processUpdateFailHTLC on node " + getPubKey(), e); }
        }
        reconcileHTLCs();
    }

    /**
     * Move sat from local side to the other side of the channel, update balance accordingly
     * @param channel_id
     * @param amount amount to be moved in sats
     * @return true if the channel update is successful
     */
    protected synchronized boolean pushSats(String channel_id, int amount) {

        var channel = this.channels.get(channel_id);
        String peer_id = getChannelPeer(channel_id).getPubKey();

        synchronized (channel) {
            if (amount < 0 || channel.getLiquidity(pubkey) < amount) return false;
            int local = channel.getBalance(pubkey), remote = channel.getBalance(peer_id);
            if (channel.getNode1PubKey().equals(pubkey)) channel.newCommitment(local - amount, remote + amount);
            else channel.newCommitment(remote + amount, local - amount);
            return true;
        }
    }


    public static String generateNodeLabelString() {
        return String.format("%-10s %-30s %-20s %-15s %-10s", "Pubkey", "Alias", "Node Capacity", "Channels", "Node Profile");
    }

    @Override
    public String toString() {
        // assuming you have a `getNodeProfile()` method or `nodeProfile` field
        return String.format("%-10s %-30s %-,20d %-15d %-10s", pubkey, alias, getNodeCapacity(), channels.size(), profile.getName());
    }

    /**
     * @param uvNode 
     * @return
     */
    @Override
    public int compareTo(UVNode uvNode) {
        String pk1 = this.getPubKey();
        String pk2 = uvNode.getPubKey();
        // Pattern: pk + integer
        if (pk1.startsWith("pk") && pk2.startsWith("pk")) {
            try {
                int n1 = Integer.parseInt(pk1.substring(2));
                int n2 = Integer.parseInt(pk2.substring(2));
                return Integer.compare(n1, n2);
            } catch (NumberFormatException e) {
                return pk1.compareTo(pk2);
                // fallback to string comparison if parsing fails
            }
        }
        return pk1.compareTo(pk2);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        UVNode uvNode = (UVNode) o;

        return pubkey.equals(uvNode.pubkey);
    }

    @Override
    public int hashCode() {
        return pubkey.hashCode();
    }

    private void printQueue(Queue<?> queue, String queueName) {
        if (!queue.isEmpty()) {
            System.out.println(queueName);
            for (var element : queue) {
                System.out.println(element);
            }
        }
    }

    public void showQueuesStatus() {

        if (countNonEmptyQueues()==0) {
            System.out.println("All node's queues are empty");
            return;
        }

        printQueue(GossipMessageQueue, "GossipMessageQueue");
        printQueue(channelsAcceptedQueue, "channelsAcceptedQueue");
        printQueue(channelsToAcceptQueue, "channelsToAcceptQueue");
        printQueue(updateAddHTLCQueue, "updateAddHTLCQueue");
        printQueue(updateFulFillHTLCQueue, "updateFulFillHTLCQueue");
        printQueue(updateFailHTLCQueue, "updateFailHTLCQueue");
        int i = 0;
        if (!pendingInvoices.values().isEmpty()) {
            System.out.println("pendingInvoices.values()");
            for(var value : pendingInvoices.values()) {
                i++;
                System.out.println(value);
            }
        }
        if (i != pendingInvoices.size()) {
            var s = "WARNING: For pendingInvoices .values() are " + i + " while .size() returns " + pendingInvoices.size();
            log(s);
            System.out.println(s);
        }


        if (!pendingHTLC.values().isEmpty()) {
            System.out.println("pendingHTLC.values()");
            for(var value : pendingHTLC.values()) {
                System.out.println(value);
            }
        }

        if (!pendingAcceptedChannelPeers.isEmpty()) {
            System.out.println("pendingAcceptedChannelPeers:");
            for(var element : pendingAcceptedChannelPeers) {
                System.out.println(element);
            }
        }
        if (!waitingTxConf.isEmpty()) {
            System.out.println("Waiting Tx Confirmations for:");
            for(var element : waitingTxConf.keySet()) {
                System.out.println(element);
                System.out.printf("Broadcasted at: "+waitingTxConf.get(element));
            }
        }
    }
    public int countNonEmptyQueues() {
        int queueSizeSum = 0;

        queueSizeSum += GossipMessageQueue.size();
        queueSizeSum += channelsAcceptedQueue.size();
        queueSizeSum += channelsToAcceptQueue.size();
        queueSizeSum += updateAddHTLCQueue.size();
        queueSizeSum += updateFulFillHTLCQueue.size();
        queueSizeSum += updateFailHTLCQueue.size();
        queueSizeSum += pendingInvoices.size();
        queueSizeSum += pendingHTLC.size();
        queueSizeSum += pendingAcceptedChannelPeers.size();
        queueSizeSum += waitingTxConf.size();

        return queueSizeSum;
    }

    public synchronized boolean areQueuesEmpty() {
        return pendingInvoices.isEmpty()
                && fundingLockedQueue.isEmpty()
                && channelsRejectedQueue.isEmpty()
                && receivedHTLC.isEmpty()
                && sentChannelOpenings.isEmpty()
                && onchainPending == 0
                && channels.values().stream().allMatch(channel -> channel.getPending(pubkey) == 0)
                && channelsAcceptedQueue.isEmpty()
                && channelsToAcceptQueue.isEmpty()
                && updateAddHTLCQueue.isEmpty()
                && updateFulFillHTLCQueue.isEmpty()
                && updateFailHTLCQueue.isEmpty()
                && GossipMessageQueue.isEmpty()
                && pendingHTLC.isEmpty()
                && pendingAcceptedChannelPeers.isEmpty()
                && waitingTxConf.isEmpty();
    }


    public double getOutboundFraction(String channel_id) {
        var channel = channels.get(channel_id);
        var channel_size = channel.getCapacity();
        var local_balance = channel.getBalance(getPubKey());
        return (double)local_balance/channel_size;
    }

    public double getOverallOutboundFraction() {
        long capacity = getNodeCapacity();
        if (capacity == 0) {
            return 0.0;
        }
        return (double)getLocalBalance()/capacity;
    }



    /**
     * Custom Serialized format: number of element / objects
     * @param s The outputstream, as specified when choosing saving file
     */
    @Serial
    private void writeObject(ObjectOutputStream s) {
        try {
            // savig non transient data
            s.defaultWriteObject();

            s.writeInt(channels.size());
            for (UVChannel c:channels.values()) {
                s.writeObject(c);
            }
            // saving only pubkey
            s.writeInt(peers.size());
            for (UVNode p:peers.values()) {
                s.writeObject(p.getPubKey());
            }

            s.writeObject(this.generatedInvoices);
            //s.writeObject(this.invoiceReports);
            s.writeObject(this.nodeStats);
            s.writeObject(this.payedInvoices);
            s.writeObject(channelGraph);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    /**
     * Read custom serialization of network.UVNode: num channels + sequence of channels object
     * Notice that internal UVNodes are restored separately to avoid stack overflow
     * @param s
     */
    @SuppressWarnings("unchecked")
    @Serial
    private void readObject(ObjectInputStream s) throws IOException, ClassNotFoundException {
        channels = new ConcurrentHashMap<>();
        saved_peers_id = new ArrayList<>();
        failure_reason = new HashMap<>();

        s.defaultReadObject();
        int num_channels = s.readInt();
        for (int i=0;i<num_channels;i++) {
            UVChannel c = (UVChannel)s.readObject();
            channels.put(c.getChannelId(),c);
        }
        int num_peers = s.readInt();
        for (int i=0;i<num_peers;i++) {
            saved_peers_id.add((String)s.readObject());
        }

        //noinspection unchecked
        generatedInvoices = (ConcurrentHashMap<Long, LNInvoice>)s.readObject();
        //invoiceReports = (ArrayList<stats.GlobalStats.InvoiceReport>) s.readObject();
        nodeStats = (GlobalStats.NodeStats) s.readObject();
        payedInvoices = (HashMap<String, LNInvoice>) s.readObject();
        channelGraph = (ChannelGraph) s.readObject();

        pathFinder = null;

        this.updateFulFillHTLCQueue = new ConcurrentLinkedQueue<>();
        this.channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
        this.channelsRejectedQueue = new ConcurrentLinkedQueue<>();
        this.fundingLockedQueue = new ConcurrentLinkedQueue<>();
        this.pendingAcceptedChannelPeers = ConcurrentHashMap.newKeySet();
        this.receivedHTLC = new HashMap<>();
        this.pendingHTLC = new ConcurrentHashMap<>();
        this.sentChannelOpenings = new HashMap<>();
        this.updateFailHTLCQueue = new ConcurrentLinkedQueue<>();
        this.pendingInvoices = new ConcurrentHashMap<>();
        this.updateAddHTLCQueue = new ConcurrentLinkedQueue<>();
        this.channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
        this.GossipMessageQueue = new ConcurrentLinkedQueue<>();
        this.waitingTxConf = new HashMap<>();
    }


    /**
     * Restoring that can performed only after all UVNodes have be loaded from UVM load (after all
     * readObject have been invoked on all UVNodes
     */
    public void restorePersistentData() {
        // restore peers
        peers = new ConcurrentHashMap<>();
        for (String p: saved_peers_id)
            peers.put(p, uvNetwork.getUVNode(p));
    }

}
