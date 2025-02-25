import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public class UVNode implements LNode, Serializable,Comparable<UVNode> {

    private final UVConfig.NodeProfile profile;

    transient private NodeStats nodeStats = new NodeStats();

    public NodeStats getNodeStats() {
        return nodeStats;
    }
    private int channel_openings = 0;
    public synchronized int getChannelOpenings() {
        return channel_openings;
    }
    public synchronized void increaseChannelOpenings() {
        channel_openings++;
    }

    private static class NodeStats implements Serializable{
        // this refers to the invoices processed
        public final ArrayList<GlobalStats.InvoiceReport> invoiceReports = new ArrayList<>();
        // these refer to partecipation in HTLC routings
        private int HTLC_success = 0;
        private int HTLC_failure = 0;
        // local forwarding events outcome.
        // For example, the local forwarding event can be a success, while eventually the HTLC routing could still fail
        private int forwarding_failures = 0;
        private int forwarding_successes = 0;
        private int forwarded_volume = 0;

        @Override
        public String toString() {
            var s = new StringBuilder();

            for (var inv : invoiceReports)  s.append('\n').append(inv);

            s.append("HTLC_success=").append(HTLC_success).append("\n")
            .append("HTLC_failure=").append(HTLC_failure).append("\n")
            .append("forwarding_failures=").append(forwarding_failures).append("\n")
            .append("forwarding_successes=").append(forwarding_successes).append("\n")
            .append("forwarded_volume=").append(forwarded_volume).append("\n");

            return s.toString();
        }
    }

    // temporary store for an invoice failure reason
    transient private Map<String,String> failure_reason = new HashMap<>();

    @Serial
    private static final long serialVersionUID = 120675L;

    private final String pubkey;
    private final String alias;
    private int onchainBalance;
    private int onchainPending = 0;
    private long last_gossip_flush = 0;


    // serialized and restored manually, to avoid stack overflow
    transient private UVNetwork uvNetwork;
    transient private ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    transient private ChannelGraph channelGraph;
    transient private ConcurrentHashMap<String, UVNode> peers = new ConcurrentHashMap<>();
    transient private boolean p2pIsRunning = false;
    transient private ArrayList<String> saved_peers_id;
    transient public ScheduledFuture<?> p2pHandler;
    transient private Queue<GossipMsg> GossipMessageQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgOpenChannel> channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgAcceptChannel> channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
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
    public record fundingConfirmation(int target_block, String tx_id, String peer) { }
    transient private HashMap<String,fundingConfirmation > waitingFundings = new HashMap<>();

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
        this.profile = profile;
    }

    public UVConfig.NodeProfile getProfile() {
        return profile;
    }
    public Queue<GossipMsg> getGossipMessageQueue() {
        return GossipMessageQueue;
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
    public HashMap<String, LNInvoice> getPayedInvoices() {
        return payedInvoices;
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

    private void print_log(String s) {
        uvNetwork.print_log(s);
    }

    private void debug(String s) {
        if (uvNetwork.getConfig().debug) log("_DEBUG_" + s);
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
    public ArrayList<GlobalStats.InvoiceReport> getInvoiceReports() {
        return nodeStats.invoiceReports;
    }
    @Override
    public String getAlias() {
        return alias;
    }

    /**
     * @param channel_id
     * @return
     */
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
        var some_channel_id = (String) channels.keySet().toArray()[ThreadLocalRandom.current().nextInt(channels.size())];
        return channels.get(some_channel_id);
    }
    /**
     * @return the sum of all balances on node side
     */
    public int getLocalBalance() {
        int balance = 0;

        for (UVChannel c : channels.values()) {
            balance += c.getBalance(this.getPubKey());
        }
        return balance;
    }
    public int getRemoteBalance() {
        int balance = 0;

        for (UVChannel c : channels.values()) {
            var peer_id = getChannelPeer(c.getChannel_id()).getPubKey();
            balance += c.getBalance(peer_id);
        }
        return balance;
    }

    @Override
    public int getNodeCapacity() {
        int capacity  =0;
        for (var ch: this.channels.values()) capacity+=ch.getCapacity();
        return capacity;
    }

    /**
     * @param uvm
     */
    public void setUVM(UVNetwork uvm) {
        this.uvNetwork = uvm;
    }

    /**
     * @param amount
     * @return
     */
    @Override
    public LNInvoice generateInvoice(int amount,String msg) {
        long R = ThreadLocalRandom.current().nextInt();
        var H = CryptoKit.bytesToHexString(CryptoKit.sha256(BigInteger.valueOf(R).toByteArray()));
        var invoice = new LNInvoice(H, amount, this.getPubKey(), msg);
        if (generatedInvoices == null) generatedInvoices = new ConcurrentHashMap<>();
        generatedInvoices.put(R, invoice);
        return invoice;
    }

    private boolean checkPathCapacity(ArrayList<ChannelGraph.Edge> path, int amount) {

        for (ChannelGraph.Edge e : path) {
            if (e.capacity() < amount) return false;
        }
        return true;
    }

    public boolean checkPathPolicies(ArrayList<ChannelGraph.Edge> path) {
        for (ChannelGraph.Edge e : path) {
            if (e.policy() == null) {
                log("WARNING: Invalid path, missing policy on edge " + e);
                return false;
            }
        }
        return true;
    }

    public boolean checkOutboundPathLiquidity(ArrayList<ChannelGraph.Edge> path, int amount) {

        var firstChannelId = getMyChannelWith(path.get(path.size() - 1).destination());
        var firstChannel = channels.get(firstChannelId);
        debug("Checking outbound path liquidity (amt:" + amount + ")" + ChannelGraph.pathString(path) + " for first channel " + firstChannel);

        // this is the only liquidity check that can be performed in advance
        var senderLiquidity = firstChannel.getLiquidity(this.getPubKey());

        if (senderLiquidity < amount) {
            debug("Discarding route for missing liquidity in first channel " + firstChannel);
            return false;
        }

        debug("Liquidity ok for " + firstChannelId + " (required " + amount + " of " + senderLiquidity + ")");
        return true;
    }

    private int computeFees(int amount, int base_fee_msat, int fee_ppm_rate) {
        double fee_ppm = ((double) amount / 1e6) * fee_ppm_rate; // Ensure floating-point division
        double base_fee = base_fee_msat / 1000.0;                // Convert msats to sats correctly
        return (int) Math.ceil(fee_ppm + base_fee);              // Round up to avoid undercharging
    }


    private int getPathFees(ArrayList<ChannelGraph.Edge> path, int amount) {
        int fees = 0;

        for (ChannelGraph.Edge e : path) {
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
    public void processInvoice(LNInvoice invoice, int max_fees, boolean showui) {

        log("Processing Invoice " + invoice);

        // pathfinding stats
        int miss_capacity = 0;
        int miss_local_liquidity = 0;
        int miss_max_fees = 0;
        int miss_policy = 0;
        int unknonw_reason = 0;

        var totalPaths = this.getChannelGraph().findPath(this.getPubKey(),invoice.getDestination(),false);
        var candidatePaths = new ArrayList<ArrayList<ChannelGraph.Edge>>();

        for (var path : totalPaths) {

            var to_discard = false;

            if (!checkPathPolicies(path)) {
                miss_policy++;
                continue;
            }

            if (!checkPathCapacity(path, invoice.getAmount())) {
                debug("Discarding path: missing capacity" + ChannelGraph.pathString(path));
                if (showui)
                    System.out.println("Discarding path: missing capacity" + ChannelGraph.pathString(path));
                to_discard = true;
                miss_capacity++;
            }

            // please notice that this does not exclude further missing local liquidy events
            // that will happen at the momennt of actual reservation, and will be accoutned in another place
            if (!checkOutboundPathLiquidity(path, invoice.getAmount())) {
                debug("Discarding path: missing liquidity on local channel" + ChannelGraph.pathString(path));
                if (showui)
                    System.out.println("Discarding path: missing liquidity on local channel" + ChannelGraph.pathString(path));
                to_discard = true;
                miss_local_liquidity++;
            }

            if (getPathFees(path, invoice.getAmount()) > max_fees) {
                debug("Discarding path: missing max fees " + ChannelGraph.pathString(path));
                if (showui)
                    System.out.println("Discarding path (exceed fees)" + ChannelGraph.pathString(path));
                to_discard = true;
                miss_max_fees++;
            }

            if (!to_discard) {
                candidatePaths.add(path);
                if (showui)
                    System.out.println("Added to candidates: " + ChannelGraph.pathString(path));
            }
        }

        boolean success_htlc = false;
        int attempted_paths = 0;
        int expiry_too_soon = 0;
        int temporary_channel_failure = 0;

        if (!candidatePaths.isEmpty()) {
            if (showui) System.out.println("Found " + candidatePaths.size() + " paths...");

            var s = new StringBuilder();
            s.append("Found ").append(candidatePaths.size()).append(" paths for ").append(invoice.getHash());
            for (var path : candidatePaths)
                s.append('\n').append(ChannelGraph.pathString(path));
            log(s.toString());


            //  conservative large delay set to the same as p2p queue updates
            // TODO: differentiate between gossip and htlc messages frequency updates
            final long invoice_check_delay = uvNetwork.getConfig().node_services_tick_ms;

            pendingInvoices.put(invoice.getHash(), invoice);

            for (var path : candidatePaths) {
                attempted_paths++;

                if (showui) System.out.println("Trying path " + path);
                log("Trying path "+attempted_paths+ " of "+candidatePaths.size()+" for invoice "+invoice.getHash());

                routeInvoiceOnPath(invoice, path);
                log("Waiting for pending HTLC "+invoice.getHash());

                while (pendingHTLC.containsKey(invoice.getHash())) {
                    try {
                        //System.out.println("WAITING...");
                        Thread.sleep(invoice_check_delay);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
                log("Cleared pending HTLC "+invoice.getHash());


                if (payedInvoices.containsKey(invoice.getHash())) {
                    if (showui) System.out.println(" Successfully routed invoice " + invoice.getHash());
                    log(" Successfully routed invoice " + invoice.getHash());
                    success_htlc = true;
                    break; // exit the for cycle of trying the paths...
                } else {
                    var reason = failure_reason.get(invoice.getHash());

                    if (reason==null) reason = "unknown";

                    // TOFIX: reason should not be null
                    log("Evaluating failure reason...");
                    switch (reason) {
                        case "expiry_too_soon":
                            expiry_too_soon++;
                            break;
                        case "temporary_channel_failure":
                            temporary_channel_failure++;
                            break;
                        case "missing local liquidity":
                            miss_local_liquidity++;
                            break;
                        default:
                            debug("No reason for failure of "+invoice.getHash());
                            unknonw_reason++;
                            break;
                    }
                    log("Could not route invoice: " + invoice.getHash()+ ", "+reason+ " on path #"+attempted_paths);

                    if (showui) System.out.println("Could not route invoice: " + invoice.getHash());
                }
            }
        }

        if (!success_htlc) {
            log("No viable candidate paths found for:" + invoice.getHash());
            if (showui)
                System.out.println("Invoice Routing Failed! No viable candidate paths found for invoice " + invoice.getHash());
        }

        log("Removing pending invoice for "+invoice.getHash());

        // the invoice, success or not, is not useful anymore
        pendingInvoices.remove(invoice.getHash());
        failure_reason.remove(invoice.getHash());


        var report = new GlobalStats.InvoiceReport(CryptoKit.shortString(invoice.getHash()), this.getPubKey(), invoice.getDestination(), invoice.getAmount(), totalPaths.size(), candidatePaths.size(), miss_policy, miss_capacity, miss_local_liquidity, miss_max_fees, attempted_paths, temporary_channel_failure, expiry_too_soon, success_htlc);
        //log("Adding report: "+report);
        nodeStats.invoiceReports.add(report);
    }

    /**
     * This method is responsible for routing an invoice through a specific path in the Lightning Network.
     *
     * The method expects an LNInvoice instance and an ArrayList of ChannelGraph.Edge objects
     * which represent the path for routing the payment in the Lightning Network.
     *
     */

    private synchronized void routeInvoiceOnPath(LNInvoice invoice, ArrayList<ChannelGraph.Edge> path) {
        log("Routing on path:"+ChannelGraph.pathString(path));
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
        for (int n=0;n<path.size()-1;n++) {
            var source = path.get(n).source();
            var dest = path.get(n).destination();

            // TODO UVMODEL: used only by node, should be a service from the node itself ?
            // point to clarify: it is used for searching own nodes or in general ?
            var path_channel = uvNetwork.getChannelFromNodes(source,dest);
            if (path_channel.isPresent()) {
                var channel = path_channel.get();
                var hopPayload = new OnionLayer.Payload(channel.getId(),amount+cumulatedFees,out_cltv,null);
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

        var first_hop = path.get(path.size()-1).destination();
        var channel_id = path.get(path.size()-1).id();
        var local_channel = channels.get(channel_id);
        var amt_to_forward= invoice.getAmount()+cumulatedFees;

        debug("Trying to reserve pending for node "+this.getPubKey()+ " , required: "+amt_to_forward+ " in channel "+local_channel.getId());
        if (!local_channel.reservePending(this.getPubKey(),amt_to_forward)) {

            failure_reason.put(invoice.getHash(),"missing local liquidity");
            // even if previuously checked, the local liquidity might have been reserved in the meanwhile...
            log("Warning:Cannot reserve "+amt_to_forward+" on first hop channel "+local_channel.getId());
            return;
        }

        var update_htcl = new MsgUpdateAddHTLC(channel_id,local_channel.getNextHTLCid(),amt_to_forward,invoice.getHash(),out_cltv,onionLayer);

        sendToPeer(uvNetwork.getUVNode(first_hop),update_htcl, uvNetwork);
        pendingHTLC.put(update_htcl.getPayment_hash(),update_htcl);
    }

    /**
     * This method processes a fulfilled Hash Time Locked Contract (HTLC) transaction.
     */

    private synchronized void processUpdateFulfillHTLC(MsgUpdateFulFillHTLC msg) throws IllegalStateException {

        var preimage = msg.getPayment_preimage();
        var channel_id = msg.getChannel_id();
        var computed_hash = CryptoKit.bytesToHexString(CryptoKit.sha256(BigInteger.valueOf(preimage).toByteArray()));
        var channel_peer_id = getChannelPeer(channel_id).getPubKey();
        log("Fulfilling invocice hash " + computed_hash + " received from "+channel_peer_id+ " via " + channel_id);

        // As expected, I offered a HTLC with the same hash
        if (pendingHTLC.containsKey(computed_hash)) {
            var htlc = pendingHTLC.get(computed_hash);

            channels.get(channel_id).removePending(this.getPubKey(),htlc.getAmount());

            if (!pushSats(channel_id, htlc.getAmount())) {
                throw new IllegalStateException("Cannot push "+htlc.getAmount()+ " from "+ this.getPubKey()+ " via "+channel_id);
            }

            // If I forwarded an incoming htlc, must also send back the fulfill message
            if (receivedHTLC.containsKey(computed_hash)) {
                var received_htlc = receivedHTLC.get(computed_hash);
                var new_msg = new MsgUpdateFulFillHTLC(received_htlc.getChannel_id(),received_htlc.getId(),preimage);
                sendToPeer(getChannelPeer(received_htlc.getChannel_id()),new_msg, uvNetwork);
                receivedHTLC.remove(computed_hash);
                nodeStats.HTLC_success++;
                nodeStats.forwarded_volume += received_htlc.getAmount();
            }
            // I offered, but did not receive the htlc, I'm initial sender?
            else {
                if (pendingInvoices.containsKey(computed_hash)) {
                    log("LN invoice for hash "+computed_hash+ " Completed!");
                    payedInvoices.put(computed_hash,pendingInvoices.get(computed_hash));
                    nodeStats.HTLC_success++;
                }
                else {
                    log("FATAL: Missing pending invoice for " + computed_hash);
                    throw new IllegalStateException("Node "+this.getPubKey()+": Missing pending Invoice for hash "+computed_hash);
                }
            }
            pendingHTLC.remove(computed_hash);
        }
        else {
            log("*FATAL*: Missing HTLC for fulfilling hash "+computed_hash);
            throw new IllegalStateException("Node "+this.getPubKey()+": Missing HTLC for fulfilling hash "+computed_hash);
        }
    }
    /**
     * This function processes the failure of an HTLC (Hash Time-Locked Contract) update message.
     * If the failure update message is found among the pending HTLCs, it removes the
     * HTLC from the pendingHTLC map, updates the channel's list of pending HTLCs, and reduces the node's statistics of successful HTLCs. If the outgoing
     */

    private synchronized void processUpdateFailHTLC(final MsgUpdateFailHTLC msg) {
        log("Processing: " + msg);
        var ch_id = msg.getChannel_id();

        for (MsgUpdateAddHTLC pending_msg : pendingHTLC.values()) {

            if (pending_msg.getChannel_id().equals(ch_id) && pending_msg.getId()==msg.getId()) {
                var hash = pending_msg.getPayment_hash();

                pendingHTLC.remove(hash);
                debug("Removing pending "+pending_msg.getAmount()+" on channel "+ch_id);
                channels.get(ch_id).removePending(this.getPubKey(),pending_msg.getAmount());

                // I offered a HTLC with the same hash
                // So I shoould send back the same failure msg to the previous node
                if (receivedHTLC.containsKey(hash)) {
                    var prev_htlc = receivedHTLC.get(hash);
                    var prev_ch_id = prev_htlc.getChannel_id();
                    var prev_peer = getChannelPeer(prev_ch_id);
                    var fail_msg = new MsgUpdateFailHTLC(prev_ch_id, prev_htlc.getId(), msg.getReason());
                    debug("Sending "+fail_msg+ " to "+prev_peer.getPubKey());
                    sendToPeer(prev_peer, fail_msg, uvNetwork);
                    receivedHTLC.remove(hash);
                    nodeStats.HTLC_failure++;
                } // I offered, but did not receive the htlc, I'm initial sender?
                else {
                    // The origin node can detect the sender of the error message by matching the hmac field with the computed HMAC.
                    // https://github.com/lightning/bolts/blob/master/04-onion-routing.md#failure-messages

                    // TODO: This could be abstracted in the future if necessary, by putting some field in the fail message

                    // this set the reason of current HTLC routing attempt failure, so that we can make some invoice specific stats
                    failure_reason.put(hash,msg.getReason());
                    nodeStats.HTLC_failure++;
                    log("Original sender of "+msg+ " recognize failure due to "+msg.getReason());
                }
                return;
            }
        }

        throw new IllegalStateException("Node " + this.getPubKey() + ": Missing pending HTLC for "+msg);
    }

    /**
     * This method is responsible for handling HTLC messages in Lightning Network.
     */
    private synchronized void processUpdateAddHTLC(final MsgUpdateAddHTLC msg) {

        // IMPORTANT: a failure at this point, due to fees, missing liquidity or expiration, will NOT
        // need any pending balance removal on the outgoing channel, it wil simply not be reserved at all

        log("Processing: "+msg);
        final var payload = msg.getOnionPacket().getPayload();
        int currentBlock = uvNetwork.getTimechain().getCurrentBlockHeight();
        int cltv_expiry = msg.getCLTVExpiry();
        var incomingPeer = getChannelPeer(msg.getChannel_id());

        // check if I'm the final destination
        if (payload.getShortChannelId().equals("00")) {
            final var secret = payload.getPayment_secret().get();

            for (long s: generatedInvoices.keySet()) {
                var preimage_bytes = BigInteger.valueOf(s).toByteArray();
                var hash = CryptoKit.bytesToHexString(CryptoKit.sha256(preimage_bytes));
                if (hash.equals(secret)) {
                    var own_invoice =  generatedInvoices.get(s);
                    log("Received HTLC on own invoice "+own_invoice);

                    if (currentBlock <= cltv_expiry) {
                        nodeStats.HTLC_success++;
                        var to_send = new MsgUpdateFulFillHTLC(msg.getChannel_id(),msg.getId(),s);
                        sendToPeer(incomingPeer,to_send, uvNetwork);
                    }
                    else {
                        log("Final node discarding late HTLC: expired in block "+cltv_expiry+ " hash:"+msg.getPayment_hash());
                        var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "expiry_too_soon");
                        nodeStats.HTLC_failure++;
                        sendToPeer(incomingPeer,fail_msg, uvNetwork);
                    }
                    return;
                }
            }
            throw new IllegalStateException(" Received update_add_htlc, but no generated invoice was found!");
        }

        // I'm not the final destination, must forward the HTLC
        final var forwardingChannel = channels.get(payload.getShortChannelId());
        int amt_incoming = msg.getAmount();
        int amt_forward = payload.getAmtToForward();

        int fees = amt_incoming-amt_forward;

        if (fees>=0) {
            // TODO: check fees here...
            debug("Fees "+fees+" ok for channel "+forwardingChannel.getPolicy(this.getPubKey()));
        }
        else {
           throw new IllegalStateException("Negative fees value for forwading "+msg);
        }

        //https://github.com/lightning/bolts/blob/master/02-peer-protocol.md#normal-operation
        // - once the cltv_expiry of an incoming HTLC has been reached,
        // OR if cltv_expiry minus current_height is less than cltv_expiry_delta for the corresponding outgoing HTLC:
        // MUST fail that incoming HTLC (update_fail_htlc).

        //TODO: expand with more failure cases (see antonop 263)

        var cltv_expiry_delta = forwardingChannel.getPolicy(this.getPubKey()).getCLTVDelta();

        if ( (currentBlock > cltv_expiry ) || (cltv_expiry-currentBlock) < cltv_expiry_delta )  {
           log("Expired HTLC: cltv_expiry="+cltv_expiry + ", out cltv_expiry_delta="+cltv_expiry_delta+" , hash:"+msg.getPayment_hash());
           var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "expiry_too_soon");
           nodeStats.forwarding_failures++;
           sendToPeer(incomingPeer,fail_msg, uvNetwork);
           return;
        }


        // check liquidity to be reserved before accepting htlc add
        debug("Checking liquidity for forwarding channel "+forwardingChannel.getId());
        if (!forwardingChannel.reservePending(this.getPubKey(),amt_forward)) {
            log("Not enought local liquidity to forward "+amt_forward+ " in channel "+forwardingChannel.getId());
            var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "temporary_channel_failure");
            nodeStats.forwarding_failures++;
            sendToPeer(incomingPeer,fail_msg, uvNetwork);
            return;
        }

        else {
            debug("Reserved liquidity on "+forwardingChannel.getId()+ " to forward: "+amt_forward + " from "+this.getPubKey()+ " to "+getChannelPeer(forwardingChannel.getId()).getPubKey());
        }


        // CREATE NEW HTLC UPDATE MESSAGE HERE USING PAYLOAD
        int cltv = payload.getOutgoingCLTV();

        // fields that does not depend on payload, but message received
        var onion_packet = msg.getOnionPacket().getInnerLayer();
        var payhash = msg.getPayment_hash();

        var new_msg = new MsgUpdateAddHTLC(forwardingChannel.getId(), forwardingChannel.getNextHTLCid(),amt_forward,payhash,cltv,onion_packet.get());

        debug("Forwarding "+new_msg);
        receivedHTLC.put(msg.getPayment_hash(),msg);
        pendingHTLC.put(new_msg.getPayment_hash(), new_msg);
        nodeStats.forwarding_successes++;
        // Forwading successfull: the pending balance will be free on fullfill by current node or
        // by the next node if failure happens...

        var next_channel_peer = getChannelPeer(forwardingChannel.getId());
        sendToPeer(next_channel_peer,new_msg, uvNetwork);
    }

    private String getMyChannelWith(String node_id) {

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id)) {
                    return c.getId();
            }
        }
        throw new IllegalArgumentException(this.getPubKey()+" Has no channel with "+node_id);
    }

    private synchronized boolean hasChannelWith(String nodeId) {
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
        channels.put(channel.getId(),channel);
    }

    private String generateTempChannelId(String peerPubKey) {
        return "tmp_id" + getPubKey() + "_" + peerPubKey;
    }

    /**
     * Mapped to LN protocol message: open_channel
     *
     * @param peerPubKey the target node partner to open the channel
     */
    public synchronized void openChannel(String peerPubKey, int channel_size) {
        if (peerPubKey.equals(this.getPubKey())) {
            log("WARNING:Cancelling openChannel, the peer selected is the node itself!");
            return;
        }
        if (hasChannelWith(peerPubKey)) {
            log("WARNING:Cancelling openChannel, channel already present with peer "+peerPubKey);
            return;
        }
        if (hasOpeningRequestWith(peerPubKey)) {
            log("WARNING:Cancelling openChannel, opening request already present with peer "+peerPubKey);
            return;
        }
        if (hasPendingAcceptedChannelWith(peerPubKey)) {
            log("WARNING: Cancelling openChannel, pending accepted channel already present with peer "+peerPubKey);
            return;
        }

        var peer = uvNetwork.getUVNode(peerPubKey);
        peers.putIfAbsent(peer.getPubKey(),peer);
        var tempChannelId = generateTempChannelId(peerPubKey);

        log("Opening channel to "+peerPubKey+ " (temp_id: "+tempChannelId+", size:"+channel_size+")");
        //TODO: the semantics of the related variable is tied to total openings, not the currently alive channels opened
        increaseChannelOpenings();

        debug("Updating pending current: "+getOnchainPending()+" to "+(channel_size+getOnchainPending()));
        updateOnchainPending(getOnchainPending()+channel_size);

        //Both sides of the channel are forced by the other side to keep 1% of funds in the channel on their side. This is in order to ensure cheating always costs something (at least 1% of the channel balance).
        double reserve_fraction = 0.01;
        int reserve = (int)(reserve_fraction*channel_size);
        var msg_request = new MsgOpenChannel(tempChannelId,channel_size, reserve, 0, uvNetwork.getConfig().to_self_delay, this.pubkey);
        sentChannelOpenings.put(peerPubKey,msg_request);
        sendToPeer(peer, msg_request, uvNetwork);
    }
    /**
     * @param openRequest
     */
    private synchronized void acceptChannel(MsgOpenChannel openRequest) {
        var temporary_channel_id = openRequest.getTemporary_channel_id();
        var initiator_id = openRequest.getFunding_pubkey();


        if (hasOpeningRequestWith(initiator_id)) {
            log("WARNING:Cannot accept channel, already opening request with "+initiator_id);
            return;
        }

        if (hasPendingAcceptedChannelWith(initiator_id)) {
            log("WARNING:Cannot accept channel, already pending accepted channel with "+initiator_id);
            return;
        }

        if (initiator_id!=null) pendingAcceptedChannelPeers.add(initiator_id);
        else {
            throw new RuntimeException("initiator_id null in acceptChannel of node "+getPubKey()+" for "+openRequest);
        }


        if (this.hasChannelWith(initiator_id)) {
            //throw  new IllegalStateException("Node "+this.getPubKey()+ " has already a channel with "+initiator_id);
            log("Warning: cannot accept channel, already a channel with "+initiator_id);
            return;
        }
        log("Accepting channel "+ temporary_channel_id);
        var channel_peer = uvNetwork.getUVNode(initiator_id);
        peers.putIfAbsent(channel_peer.getPubKey(),channel_peer);
        var acceptance = new MsgAcceptChannel(temporary_channel_id, uvNetwork.getConfig().minimum_depth, uvNetwork.getConfig().to_self_delay,this.getPubKey());
        sendToPeer(channel_peer,acceptance, uvNetwork);
    }


    private void channelAccepted(MsgAcceptChannel acceptMessage) {

        var temp_channel_id = acceptMessage.getTemporary_channel_id();
        var peerPubKey = acceptMessage.getFundingPubkey();

        log("Channel accepted by peer "+ peerPubKey+ " ("+temp_channel_id+")");
        var pseudo_hash = CryptoKit.bytesToHexString(CryptoKit.hash256(temp_channel_id));

        var funding_amount = sentChannelOpenings.get(peerPubKey).getFunding();
        // TODO: pubkeys should be lexically ordered, not based on initiator
        var funding_tx = new UVTimechain.Transaction(pseudo_hash, UVTimechain.TxType.FUNDING_TX,funding_amount,getPubKey(),peerPubKey);
        // No need to model the actual signatures with the two messages below, leaving placeholder for future extensions ;)
        // bolt: send funding_created
        // bolt: received funding_signed
        // UVMODE TODO: this should move to UVNetork
        uvNetwork.getTimechain().addToMempool(funding_tx);


        int taget_block = uvNetwork.getTimechain().getCurrentBlockHeight()+acceptMessage.getMinimum_depth();

        waitingFundings.put(funding_tx.txId(),new fundingConfirmation(taget_block, funding_tx.txId(),peerPubKey));
    }

    private void fundingChannelConfirmed(String peer_id, String tx_id) {

        // Abstractions:
        // - No in-mempool waiting room after broadcasting (just wait the blocks...)
        // - This function is called by channel inititor, which will alert peer with BOLT funding_locked message:
        // Actually, even the peer should monitor onchain confirmation on its own, not trusting channel initiator

        log("Confirmed funding tx "+tx_id);

        var timestamp = uvNetwork.getTimechain().getCurrentBlockHeight();
        var request = sentChannelOpenings.get(peer_id);
        sentChannelOpenings.remove(peer_id);
        var newChannel = UVChannel.buildFromProposal(this.getPubKey(),peer_id,request.getFunding(),request.getChannelReserve(),request.getPushMSAT());

        updateOnChainBalance(getOnChainBalance()- request.getFunding());
        updateOnchainPending(getOnchainPending()-request.getFunding());

        var channel_id = newChannel.getChannel_id();
        channels.put(channel_id,newChannel);

        // in millisats
        int base_fee = uvNetwork.getConfig().getMultivalPropertyRandomIntItem("base_fee_set");

        int fee_ppm = getProfile().getRandomSample("ppm_fees");
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

        uvNetwork.getUVNode(peer_id).fundingLocked(newChannel);
        broadcastToPeers(from,msg_announcement);
        broadcastToPeers(from,msg_update);
    }

    /**
     *
     * @param newChannel
     */
    public void fundingLocked(UVChannel newChannel) {

        log("Received funding_locked for "+newChannel.getId());

        pendingAcceptedChannelPeers.remove(newChannel.getInitiator());

        // setting a random policy
        int base_fee = uvNetwork.getConfig().getMultivalPropertyRandomIntItem("base_fee_set");
        int fee_ppm = getProfile().getRandomSample("ppm_fees");
        // TODO: set some delta cltv
        var newPolicy = new LNChannel.Policy(40,base_fee,fee_ppm);

        newChannel.setPolicy(getPubKey(),newPolicy);
        channels.put(newChannel.getId(), newChannel);

        channelGraph.addLNChannel(newChannel);
        channelGraph.updateChannel(this.getPubKey(),newPolicy,newChannel.getId());

        var timestamp = uvNetwork.getTimechain().getCurrentBlockHeight();
        // here sender is set as current node, all the other peers should receive the update
        String sender = this.getPubKey();
        var msg_update = new GossipMsgChannelUpdate(sender,this.getPubKey(),newChannel.getId(),timestamp,0,newPolicy);

        // sender is set as the channel initiator, so that it's excluded from broadcasting
        var message_ann = new GossipMsgChannelAnnouncement(newChannel.getInitiator(),newChannel.getId(),newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(newChannel.getInitiator(), message_ann);
        broadcastToPeers(sender,msg_update);
    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
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
    public void runServices() {

        try {
            // should run at least at blocktime period speed, but we can just check at very services tick
            checkTimechainTxConfirmations();

            // handled as soon as possible, just check at very services tick
            processChannelsMsgQueue();

            // handled as soon as possible, just check at very services tick
            processHTLCMsgQueue();

            // to avoid spam and network congestion, gossid should spread less frequenctly
            long now = System.currentTimeMillis();
            if (now - last_gossip_flush >= uvNetwork.getConfig().gossip_flush_period_ms) {
                processGossip();  // or flushGossip()
                last_gossip_flush = now;
            }
            //processGossip();
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private synchronized void checkTimechainTxConfirmations() {

        if (!waitingFundings.isEmpty()) {
            final int current_block = uvNetwork.getTimechain().getCurrentBlockHeight();
            var to_be_removed = new ArrayList<String>();

            for (var tx: waitingFundings.values()) {
                if (tx.target_block<=current_block) {
                    fundingChannelConfirmed(tx.peer(),tx.tx_id());
                    to_be_removed.add(tx.tx_id());
                }
            }
            for (var tx: to_be_removed) {
                waitingFundings.remove(tx);
            }
        }

    }


    private void processChannelsMsgQueue() {

        // TODO: this is enough for sure, but maybe move to some config constansts
        final int max = 20;
        int n = 0;

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
            catch (Exception e) { e.printStackTrace(); }
        }

        n = 0;
        while (!updateFulFillHTLCQueue.isEmpty() && n++ < max ) {
            var msg = updateFulFillHTLCQueue.poll();
            try {
                processUpdateFulfillHTLC(msg);
            } catch (Exception e) { e.printStackTrace(); }
        }
        n = 0;
        while (!updateFailHTLCQueue.isEmpty() && n++ < max ) {
            var msg = updateFailHTLCQueue.poll();
            try {
                processUpdateFailHTLC(msg);
            } catch (Exception e) { e.printStackTrace(); }
        }
        // check for expired htlc
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

        var outbound_liquidity = channel.getLiquidity(this.getPubKey());
        var local_balance = channel.getBalance(this.getPubKey());
        var remote_balance = channel.getBalance(peer_id);

        // actually available outbound liquidity = local_balance - 1% channel_reserve - pending
        if (outbound_liquidity>=amount) {
            if (channel.getNode1PubKey().equals(this.getPubKey()))
                channel.newCommitment(local_balance-amount,remote_balance+amount);
            else
                channel.newCommitment(remote_balance+amount,local_balance-amount);
        }
        else {
            log("pushSats: Insufficient outbound liquidity in channel "+channel_id+" cannot push  "+amount+ " sats to "+peer_id);
            return false;
        }
        return true;
    }



    @Override
    public String toString() {
        return getInfo();
    }

    public String getInfo() {

        System.out.println(getInfoCSV());
        return String.format("%-10s %-30s %-,20d %-15d %.2f", pubkey, alias, getNodeCapacity(), channels.size(), getOverallOutboundFraction());
    }

    public String getInfoCSV() {

        if (channels.size() == 0) {
            return "-";
        }

        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        double sum = 0;

        List<Integer> sizes = new ArrayList<>();
        for (var channel : channels.values()) {
            int size = channel.getCapacity();
            sizes.add(size);

            if (size < min) {
                min = size;
            }
            if (size > max) {
                max = size;
            }
            sum += size;
        }

        double average = sum / channels.size();

        double median;
        Collections.sort(sizes);
        if (sizes.size() % 2 == 0)
            median = ((double)sizes.get(sizes.size()/2) + (double)sizes.get(sizes.size()/2 - 1))/2;
        else
            median = (double) sizes.get(sizes.size()/2);

        return String.format("%s,%s,%d,%d,%.2f,%d,%d,%.2f,%.2f,%d,%d,%d,%d,%d",
                pubkey, alias,
                getNodeCapacity(),
                channels.size(),
                getOverallOutboundFraction(),
                max, min, average, median,
                getNodeStats().HTLC_success, getNodeStats().HTLC_failure,
                getNodeStats().forwarding_successes, getNodeStats().forwarding_failures, getNodeStats().forwarded_volume);
    }

    /**
     * @param uvNode 
     * @return
     */
    @Override
    public int compareTo(UVNode uvNode) {
        return this.getPubKey().compareTo(uvNode.getPubKey());
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

    public void showQueuesStatus() {

        if (countNonEmptyQueues()!=0)
            System.out.println("----- node " +this.getPubKey()+ "----------------------------------------------");

        System.out.println("GossipMessageQueue");
        for(var element : GossipMessageQueue) {
            System.out.println(element);
        }

        System.out.println("channelsAcceptedQueue");
        for(var element : channelsAcceptedQueue) {
            System.out.println(element);
        }

        System.out.println("channelsToAcceptQueue");
        for(var element : channelsToAcceptQueue) {
            System.out.println(element);
        }

        System.out.println("updateAddHTLCQueue");
        for(var element : updateAddHTLCQueue) {
            System.out.println(element);
        }

        System.out.println("updateFulFillHTLCQueue");
        for(var element : updateFulFillHTLCQueue) {
            System.out.println(element);
        }

        System.out.println("updateFailHTLCQueue");
        for(var element : updateFailHTLCQueue) {
            System.out.println(element);
        }

        int i=0;
        System.out.println("pendingInvoices.values()");
        for(var value : pendingInvoices.values()) {
            System.out.println(value);
            i++;
        }
        if (i != pendingInvoices.size()) {
            var s = "WARNING: For pendingInvoices .values() are " + i + " while .size() returns " + pendingInvoices.size();
            log(s);
            System.out.println(s);
        }


        System.out.println("pendingHTLC.values()");
        for(var value : pendingHTLC.values()) {
            System.out.println(value);
        }

        System.out.println("pendingAcceptedChannelPeers");
        for(var element : pendingAcceptedChannelPeers) {
            System.out.println(element);
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

        return queueSizeSum;
    }

    public boolean areQueuesEmpty() {
        return pendingInvoices.isEmpty()
                && channelsAcceptedQueue.isEmpty()
                && channelsToAcceptQueue.isEmpty()
                && updateAddHTLCQueue.isEmpty()
                && updateFulFillHTLCQueue.isEmpty()
                && updateFailHTLCQueue.isEmpty()
                && GossipMessageQueue.isEmpty()
                && pendingHTLC.isEmpty()
                && pendingAcceptedChannelPeers.isEmpty();
    }


    public double getOutboundFraction(String channel_id) {
        var channel = channels.get(channel_id);
        var channel_size = channel.getCapacity();
        var local_balance = channel.getBalance(getPubKey());
        return (double)local_balance/channel_size;
    }

    public double getOverallOutboundFraction() {
        return (double)getLocalBalance()/getNodeCapacity();
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
     * Read custom serialization of UVNode: num channels + sequence of channels object
     * Notice that internal UVNodes are restored separately to avoid stack overflow
     * @param s
     */
    @SuppressWarnings("unchecked")
    @Serial
    private void readObject(ObjectInputStream s) {
        channels = new ConcurrentHashMap<>();
        saved_peers_id = new ArrayList<>();
        failure_reason = new HashMap<>();

        try {
            s.defaultReadObject();
            int num_channels = s.readInt();
            for (int i=0;i<num_channels;i++) {
                UVChannel c = (UVChannel)s.readObject();
                channels.put(c.getId(),c);
            }
            int num_peers = s.readInt();
            for (int i=0;i<num_peers;i++) {
                saved_peers_id.add((String)s.readObject());
            }

            //noinspection unchecked
            generatedInvoices = (ConcurrentHashMap<Long, LNInvoice>)s.readObject();
            //invoiceReports = (ArrayList<GlobalStats.InvoiceReport>) s.readObject();
            nodeStats = (NodeStats) s.readObject();
            payedInvoices = (HashMap<String, LNInvoice>) s.readObject();
            channelGraph = (ChannelGraph) s.readObject();


            this.updateFulFillHTLCQueue = new ConcurrentLinkedQueue<>();
            this.channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
            this.pendingAcceptedChannelPeers = ConcurrentHashMap.newKeySet();
            this.receivedHTLC = new HashMap<>();
            this.pendingHTLC = new ConcurrentHashMap<>();
            this.sentChannelOpenings = new HashMap<>();
            this.updateFailHTLCQueue = new ConcurrentLinkedQueue<>();
            this.pendingInvoices = new ConcurrentHashMap<>();
            this.updateAddHTLCQueue = new ConcurrentLinkedQueue<>();
            this.channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
            this.GossipMessageQueue = new ConcurrentLinkedQueue<>();
            this.waitingFundings = new HashMap<>();
        }
        catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
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
