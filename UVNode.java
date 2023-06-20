import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public class UVNode implements LNode,P2PNode, Serializable,Comparable<UVNode> {

    private final Map<String,String> profile;

    public record InvoiceReport(String hash,
                            String sender,
                            String dest,
                            int total_paths,
                            int candidate_paths,
                            int missing_capacity,
                            int missing_fees,
                            int missing_outbound_liquidity,
                            int attempted_paths,
                            boolean htlc_success) {};

    transient private ArrayList<InvoiceReport> invoiceReports = new ArrayList<>();


    @Serial
    private static final long serialVersionUID = 120675L;

    private final String pubkey;
    private final String alias;
    private int onchainBalance;
    private int onchainPending = 0;

    // serialized and restored manually, to avoid stack overflow
    transient private UVNetworkManager uvManager;
    transient private ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    transient private ChannelGraph channelGraph;
    transient private ConcurrentHashMap<String, P2PNode> peers = new ConcurrentHashMap<>();

    transient private boolean p2pIsRunning = false;
    transient private ArrayList<String> saved_peers_id;

    transient public ScheduledFuture<?> p2pHandler;
    transient private Queue<GossipMsg> GossipMessageQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgOpenChannel> channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgAcceptChannel> channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateAddHTLC> updateAddHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateFulFillHTLC> updateFulFillHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateFailHTLC> updateFailHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private HashMap<Long, LNInvoice> generatedInvoices = new HashMap<>();
    transient private HashMap<String, LNInvoice> pendingInvoices = new HashMap<>();
    transient private HashMap<String, LNInvoice> payedInvoices = new HashMap<>();
    transient private HashMap<String, MsgUpdateAddHTLC> receivedHTLC = new HashMap<>();
    transient private HashMap<String, MsgUpdateAddHTLC> pendingHTLC = new HashMap<>();
    transient private HashMap<String, MsgOpenChannel> sentChannelOpenings = new HashMap<>();

    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param funding initial onchain balance
     */
    public UVNode(UVNetworkManager manager, String pubkey, String alias, int funding, Map<String,String> profile) {
        this.uvManager = manager;
        this.pubkey = pubkey;
        this.alias = alias;
        updateOnChainBalance(funding);
        channelGraph = new ChannelGraph(pubkey);
        this.profile = profile;
    }

    public Map<String, String> getProfile() {
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
    public HashMap<Long,LNInvoice> getGeneratedInvoices() {
        return generatedInvoices;
    }
    public HashMap<String, MsgUpdateAddHTLC> getReceivedHTLC() {
        return receivedHTLC;
    }
    public HashMap<String, MsgOpenChannel> getSentChannelOpenings() {
        return sentChannelOpenings;
    }
    private void log(String s) {
         UVNetworkManager.log(this.getPubKey()+":"+s);
    }

    private void debug(String s) {
        if (uvManager.getConfig().getStringProperty("debug").equals("true"))  {
            UVNetworkManager.log("*DEBUG*:"+this.getPubKey()+":"+s);
        }
    }
    public ArrayList<LNChannel> getLNChannelList() {
        return new ArrayList<>(this.channels.values());
    }
    public ConcurrentHashMap<String, P2PNode> getPeers() {
        return peers;
    }
    public ChannelGraph getChannelGraph() {
        return this.channelGraph;
    }
    public String getPubKey() {
        return pubkey;
    }


    public ArrayList<InvoiceReport> getInvoiceReports() {
        return invoiceReports;
    }

    @Override
    public String getAlias() {
        return alias;
    }
    /**
     *
     * @param channel_id
     * @return
     */
    private P2PNode getChannelPeer(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return uvManager.getP2PNode(channel.getNode2PubKey());
        else
            return uvManager.getP2PNode(channel.getNode1PubKey());
    }

    public synchronized int getLocalChannelBalance(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return channel.getNode1Balance();
        else
            return channel.getNode2Balance();
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

    private synchronized void updateOnchainPending( int pending) {
        this.onchainPending = pending;
    }

    public synchronized int getOnchainLiquidity() {
        return getOnChainBalance()-getOnchainPending();
    }

    public ConcurrentHashMap<String, UVChannel> getChannels() {
        return channels;
    }

    public UVChannel getRandomChannel() {
        var some_channel_id = (String)channels.keySet().toArray()[ThreadLocalRandom.current().nextInt(channels.size())];
        return channels.get(some_channel_id);
    }
    /**
     *
     * @return the sum of all balances on node side
     */
    public int getLightningBalance() {
        int balance = 0;

        for (UVChannel c: channels.values()) {
            if (c.getNode1PubKey().equals(getPubKey())) balance+=c.getNode1Balance();
            else
                balance+=c.getNode2Balance();
        }
        return balance;
    }

    /**
     *
     * @param uvm
     */
    public void setUVM(UVNetworkManager uvm) {
        this.uvManager = uvm;
    }

    /**
     * @param amount
     * @return
     */
    @Override
    public LNInvoice generateInvoice(int amount) {
        long R = ThreadLocalRandom.current().nextInt();
        var H = Kit.bytesToHexString(Kit.sha256(BigInteger.valueOf(R).toByteArray()));
        var invoice = new LNInvoice(H,amount,this.getPubKey(),"");
        if (generatedInvoices ==null) generatedInvoices = new HashMap<>();
        generatedInvoices.put(R,invoice);
        return invoice;
    }

    public boolean waitForInvoiceCleared(String hash) {
        final long delay = uvManager.getTimechain().getBlockToMillisecTimeDelay(1);
        while (pendingInvoices.containsKey(hash)) {
            try {
                //System.out.println("WAITING...");
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return (payedInvoices.containsKey(hash));
    }

    public boolean checkPathCapacity(ArrayList<ChannelGraph.Edge> path, int amount) {

        log("Checking path capacity"+ChannelGraph.pathString(path));
        for (ChannelGraph.Edge e: path) {
            if (e.capacity()< amount) return false;
        }
        return true;
    }
    public boolean checkPathPolicies(ArrayList<ChannelGraph.Edge> path) {

        log("Checking path policies "+ChannelGraph.pathString(path));
        for (ChannelGraph.Edge e: path) {
            if (e.policy()==null)  {
                log("Invalid path, missing policy on edge "+e);
                return false;
            }
        }
        return true;
    }

    public boolean checkOutboundPathLiquidity(ArrayList<ChannelGraph.Edge> path, int amount) {

        log("Checking outbound path liquidity "+ChannelGraph.pathString(path));
        var firstChannelId = getMyChannelWith(path.get(path.size()-1).destination());
        var firstChannel = channels.get(firstChannelId);

        // this is the only liquidity check that can be performed in advance
        var senderLiquidity = firstChannel.getLiquidity(this.getPubKey());

        if (senderLiquidity< amount) {
            log("Discarding route for missing liquidity in first channel "+firstChannel);
            return false;
        }

        return true;
    }

    private int computeFees(int amount, int base_fee_rate, int fee_ppm_rate) {
        double fee_ppm = fee_ppm_rate*(amount/(double)1e6);
        double base_fee = base_fee_rate/(double)1000;
        return (int)(fee_ppm+base_fee);

    }

    public int getPathFees(ArrayList<ChannelGraph.Edge> path, int amount)  {
        int fees = 0;

        for (ChannelGraph.Edge e: path) {
            fees+= computeFees(amount,e.policy().getBaseFee(),e.policy().getFeePpm());
        }
        return fees;
    }

    /**
     *
     * @param invoice
     * @param max_fees
     */

    public void processInvoice(LNInvoice invoice, int max_fees) {

        log("Processing "+invoice);

        // pathfinding stats
        var  candidatePaths = new ArrayList<ArrayList<ChannelGraph.Edge>>();
        int miss_capacity = 0;
        int miss_outbound_liquidity = 0;
        int exceeded_max_fees = 0;

        var totalPaths = getPaths(invoice.getDestination(),false);

        for (var path:totalPaths) {

            var to_discard = false;

            if (!checkPathPolicies(path)) {
                // this path is not viable, some policies are missing
                continue;
            }

            if (!checkPathCapacity(path, invoice.getAmount()))  {
                log("Discarding path (missing capacity)"+ ChannelGraph.pathString(path));
                to_discard = true;
                miss_capacity++;
            }

            if (!checkOutboundPathLiquidity(path, invoice.getAmount()))  {
                log("Discarding path (missing liquidity)"+ ChannelGraph.pathString(path));
                to_discard = true;
                miss_outbound_liquidity++;
            }

            if (getPathFees(path,invoice.getAmount()) > max_fees) {
                log("Discarding path (exceed fees)"+ ChannelGraph.pathString(path));
                to_discard = true;
                exceeded_max_fees++;
            }

            if (!to_discard) candidatePaths.add(path);
        }

        // routing stats
        boolean success_htlc = false;
        int attempted_paths = 0;

        if ( candidatePaths.size()>0) {
            for (var path:  candidatePaths) {
                attempted_paths++;
                routeInvoiceOnPath(invoice,path);

                if (waitForInvoiceCleared(invoice.getHash())) {
                    success_htlc = true;
                    break;
                }
            }
        }

        invoiceReports.add(new InvoiceReport(this.getPubKey(), invoice.getDestination(),invoice.getHash(),totalPaths.size(),candidatePaths.size(),miss_capacity,exceeded_max_fees,miss_outbound_liquidity,attempted_paths,success_htlc));
    }

    public ArrayList<ArrayList<ChannelGraph.Edge>> getPaths(String destination, boolean stopfirst) {
        return this.getChannelGraph().findPath(this.getPubKey(),destination,stopfirst);
    }

    /**
     *
     * @param invoice
     * @param path
     * @return
     */
    public void routeInvoiceOnPath(LNInvoice invoice, ArrayList<ChannelGraph.Edge> path) {
        log("Routing on path:"+ChannelGraph.pathString(path));
        // if Alice is the sender, and Dina the receiver: paths = Dina, Carol, Bob, Alice

        debug("Assembling final payload for node: "+path.get(0).destination());
        final int amount = invoice.getAmount();
        final int baseBlockHeight = uvManager.getTimechain().getCurrentBlock();
        final var finalCLTV = baseBlockHeight+invoice.getMinFinalCltvExpiry();

        // last layer is the only one with the secret
        var firstHopPayload = new OnionLayer.Payload("00",amount,finalCLTV,invoice.getHash());
        // this is the inner layer, for the final destination, so no further inner layer
        var firstOnionLayer = new OnionLayer(firstHopPayload,null);
        debug(firstOnionLayer.toString());

        int cumulatedFees = 0;
        var onionLayer = firstOnionLayer;
        int out_cltv = finalCLTV;

        // we start with the payload for Carol, which has no added fee to pay because Dina is the final hop
        // Carol will take the forwarding fees specified in the payload for Bob
        // don't need to create the last path segment onion for local node htlc
        for (int n=0;n<path.size()-1;n++) {

            var source = path.get(n).source();
            var dest = path.get(n).destination();

            var path_channel = uvManager.getChannelFromNodes(source,dest);
            if (path_channel.isPresent()) {
                var channel = path_channel.get();
                debug("Assembling Payload for node: "+source);
                var hopPayload = new OnionLayer.Payload(channel.getId(),amount+cumulatedFees,out_cltv,null);
                onionLayer = new OnionLayer(hopPayload,onionLayer);

                debug(onionLayer.toString());
                // TODO: get the proper values, to be used in next iteration
                // the fees in the carol->dina channel will be put in the Bob payload in the next loop
                // because it's bob that has to advance the fees to Carol  (Bob will do same with Alice)
                //fees += channel.getPolicy(source).fee_ppm()+channel.getPolicy(source).base_fee();
                cumulatedFees += computeFees(amount,channel.getPolicy(source).getBaseFee(),channel.getPolicy(source).getFeePpm());
                out_cltv += channel.getPolicy(source).getCLTV();
            }
            else {
                log("ERROR: No channel from "+source+ " to "+dest);
                return;
            }
        }

        var first_hop = path.get(path.size()-1).destination();

        var channel_id = path.get(path.size()-1).id();
        var local_channel = channels.get(channel_id);
        var id = local_channel.getLastCommitNumber()+1;
        var amt_to_forward= invoice.getAmount()+cumulatedFees;

        var update_htcl = new MsgUpdateAddHTLC(channel_id,id,amt_to_forward,invoice.getHash(),out_cltv,onionLayer);
        debug("Creating HTLC messsage for the first hop: "+first_hop+": "+update_htcl.toString());

        sendToPeer(uvManager.getP2PNode(first_hop),update_htcl);
        pendingInvoices.put(invoice.getHash(),invoice);
        pendingHTLC.put(update_htcl.getPayment_hash(),update_htcl);
    }
    /**
     *
     * @param msg
     */
    private void processUpdateFulfillHTLC(MsgUpdateFulFillHTLC msg) throws IllegalStateException {

        var preimage = msg.getPayment_preimage();
        var channel = msg.getChannel_id();
        var computed_hash = Kit.bytesToHexString(Kit.sha256(BigInteger.valueOf(preimage).toByteArray()));

        // I offered a HTLC with the same hash
        if (pendingHTLC.containsKey(computed_hash)) {
            var htlc = pendingHTLC.get(computed_hash);
            log("Fulfilling " + htlc + " by pushing " + htlc.getAmount() + " from " + this.getPubKey() + " to channel " + channel);

            if (!pushSats(channel, htlc.getAmount())) {
                throw new IllegalStateException("Cannot push sats!");
            }

            pendingHTLC.remove(computed_hash);
            channels.get(channel).removePending(this.getPubKey(),htlc.getAmount());

            // If I forwarded an incoming htlc, must also send back the fulfill message
            if (receivedHTLC.containsKey(computed_hash)) {
                var received_htlc = receivedHTLC.get(computed_hash);
                var new_msg = new MsgUpdateFulFillHTLC(received_htlc.getChannel_id(),received_htlc.getId(),preimage);
                sendToPeer(getChannelPeer(received_htlc.getChannel_id()),new_msg);
                receivedHTLC.remove(computed_hash);
            }
            // I offered, but did not receive the htlc, I'm initial sender?
            else {
                if (pendingInvoices.containsKey(computed_hash)) {
                    log("LN invoice for hash "+computed_hash+ " Completed!");
                    payedInvoices.put(computed_hash,pendingInvoices.remove(computed_hash));
                }
                else throw new IllegalStateException("Node "+this.getPubKey()+": Missing pending Invoice for hash "+computed_hash);
            }
        }
        else throw new IllegalStateException("Node "+this.getPubKey()+": Missing HTLC for fulfilling hash "+computed_hash);
    }

    private synchronized void processUpdateFailHTLC(final MsgUpdateFailHTLC msg) {
        log("Processing: " + msg);
        var ch_id = msg.getChannel_id();

        for (MsgUpdateAddHTLC pending_msg : pendingHTLC.values()) {

            if (pending_msg.getChannel_id().equals(ch_id) && pending_msg.getId() == msg.getId()) {
                var hash = pending_msg.getPayment_hash();
                log("Found pending HTLC to remove for hash: " + hash);

                pendingHTLC.remove(hash);
                channels.get(ch_id).removePending(this.getPubKey(),pending_msg.getAmount());

                // I offered a HTLC with the same hash
                if (receivedHTLC.containsKey(hash)) {
                    var prev_htlc = receivedHTLC.get(hash);
                    var prev_ch_id = prev_htlc.getChannel_id();
                    var prev_peer = getChannelPeer(prev_ch_id);
                    sendToPeer(prev_peer, new MsgUpdateFailHTLC(prev_ch_id, prev_htlc.getId(), msg.getReason()));
                    receivedHTLC.remove(hash);
                } // I offered, but did not receive the htlc, I'm initial sender?
                else {
                    if (pendingInvoices.containsKey(hash)) {
                        log("LN invoice for hash " + hash + " Failed!");
                        pendingInvoices.remove(hash);
                    } else
                        throw new IllegalStateException("Node " + this.getPubKey() + ": Missing pending Invoice for hash " + hash);

                }
                return;
            }
        }

        throw new IllegalStateException("Node " + this.getPubKey() + ": Missing HTLC for "+msg);
    }


    /**
     *
     * @return
     */
    private void processUpdateAddHTLC(final MsgUpdateAddHTLC msg) {

        log("Processing: "+msg);
        final var payload = msg.getOnionPacket().getPayload();

        // check if I'm the final destination
        if (payload.getShortChannelId().equals("00")) {
            final var secret = payload.getPayment_secret().get();
            var preimages = generatedInvoices.keySet();
            for (long s: preimages) {
                var preimage_bytes = BigInteger.valueOf(s).toByteArray();
                var hash = Kit.bytesToHexString(Kit.sha256(preimage_bytes));
                if (hash.equals(secret)) {
                    log("Received HTLC on own invoice "+generatedInvoices.get(s));
                    var peer = getChannelPeer(msg.getChannel_id());
                    var to_send = new MsgUpdateFulFillHTLC(msg.getChannel_id(),msg.getId(),s);
                    sendToPeer(peer,to_send);
                    return;
                }
            }
            throw new IllegalStateException(" Received update_add_htlc, but no generated invoice was found!");
        }

        final var forwardingChannel = channels.get(payload.getShortChannelId());
        int amt_incoming = msg.getAmount();
        int amt_forward = payload.getAmtToForward();

        int fees = amt_incoming-amt_forward;

        if (fees>=0) {
            // TODO: check fees here...
            debug("Fees "+fees+" ok for channel "+forwardingChannel.getPolicy(this.getPubKey()));

        }

        //https://github.com/lightning/bolts/blob/master/02-peer-protocol.md#normal-operation
        // - once the cltv_expiry of an incoming HTLC has been reached,
        // O if cltv_expiry minus current_height is less than cltv_expiry_delta for the corresponding outgoing HTLC:
        // MUST fail that incoming HTLC (update_fail_htlc).

        var incomingPeer = getChannelPeer(msg.getChannel_id());
        int currentBlock = uvManager.getTimechain().getCurrentBlock();
        var blocks_until_expire = msg.getCLTVExpiry()-currentBlock;
        var my_out_cltv = forwardingChannel.getPolicy(this.getPubKey()).getCLTV();

        if ( (blocks_until_expire <=0) || (blocks_until_expire < my_out_cltv)) {
           log("Expired (blocks until expire "+blocks_until_expire+ ", my out cltv:"+my_out_cltv+" for HTLC hash:"+msg.getPayment_hash());
           var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "CLTV expired");
           sendToPeer(incomingPeer,fail_msg);
           return;
        }

        // check liquidity
        int channel_liquidity = forwardingChannel.getLiquidity(this.getPubKey());

        if (amt_forward > channel_liquidity) {
            log("Not enought local liquidity to forward "+amt_forward+ " in channel "+forwardingChannel);
            var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "temporary_channel_failure");
            sendToPeer(incomingPeer,fail_msg);
            return;
        }


        // CREATE NEW HTLC UPDATE MESSAGE HERE USING PAYLOAD
        int cltv = payload.getOutgoingCLTV();

        // fields that does not depend on payload, but message received
        var onion_packet = msg.getOnionPacket().getInnerLayer();
        var payhash = msg.getPayment_hash();

        var new_msg = new MsgUpdateAddHTLC(forwardingChannel.getId(), forwardingChannel.increaseHTLCId(),amt_forward,payhash,cltv,onion_packet.get());

        debug("Forwarding "+new_msg);
        receivedHTLC.put(msg.getPayment_hash(),msg);
        pendingHTLC.put(new_msg.getPayment_hash(), new_msg);
        forwardingChannel.addPending(this.getPubKey(),amt_forward);

        var next_channel_peer = getChannelPeer(forwardingChannel.getId());
        sendToPeer(next_channel_peer,new_msg);
    }

    /**
     *
     * @param node_id
     * @return
     */
    public String getMyChannelWith(String node_id) {

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id)) {
                    return c.getId();
            }
        }
        throw new IllegalArgumentException(this.getPubKey()+" Has no channel with "+node_id);
    }

    public boolean hasChannelWith(String nodeId) {
        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(nodeId) || c.getNode1PubKey().equals(nodeId)) {
                return true;
            }
        }
        return false;
    }

    public synchronized boolean ongoingOpeningRequestWith(String nodeId) {
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
        StringBuilder s = new StringBuilder();
        var block = uvManager.getTimechain().getCurrentBlock();
        s.append("tmp_id").append(getPubKey()).append("_").append(peerPubKey);
        return s.toString();
    }
    private String generateChannelId(UVTimechain.Transaction tx) {
        var searchPos = uvManager.getTimechain().getTxLocation(tx);
        var position = searchPos.get();

        return position.height() + "x" + position.tx_index();
    }
    /**
     * Mapped to LN protocol message: open_channel
     * @param peerPubKey the target node partner to open the channel
     */
    public void openChannel(String peerPubKey, int channel_size) {

        var peer = uvManager.getP2PNode(peerPubKey);
        peers.putIfAbsent(peer.getPubKey(),peer);
        var tempChannelId = generateTempChannelId(peerPubKey);

        log("Opening channel to "+peerPubKey+ " (temp_id: "+tempChannelId+")");

        log("Updating pending current: "+getOnchainPending()+" to "+(channel_size+getOnchainPending()));
        updateOnchainPending(getOnchainPending()+channel_size);

        var msg_request = new MsgOpenChannel(tempChannelId,channel_size, 0, 0, uvManager.getConfig().getIntProperty("to_self_delay"), this.pubkey);
        sentChannelOpenings.put(peerPubKey,msg_request);
        sendToPeer(peer, msg_request);
    }
    /**
     *
     * @param openRequest
     * @return
     */
    private synchronized void acceptChannel(MsgOpenChannel openRequest) {
        var temporary_channel_id = openRequest.getTemporary_channel_id();
        var initiator_id = openRequest.getFunding_pubkey();
        if (this.hasChannelWith(initiator_id)) {
            log("Node has already a channel with "+initiator_id);
            return;
        }
        log("Accepting channel "+ temporary_channel_id);
        var channel_peer = uvManager.getP2PNode(initiator_id);
        peers.putIfAbsent(channel_peer.getPubKey(),channel_peer);
        var acceptance = new MsgAcceptChannel(temporary_channel_id, uvManager.getConfig().getIntProperty("minimum_depth"), uvManager.getConfig().getIntProperty("to_self_delay"),this.getPubKey());
        sendToPeer(channel_peer,acceptance);
    }


    /**
     *
     * @param acceptMessage
     */
    private void channelAccepted(MsgAcceptChannel acceptMessage) {

        var temp_channel_id = acceptMessage.getTemporary_channel_id();
        var peerPubKey = acceptMessage.getFundingPubkey();


        log("Channel Accepted by peer "+ peerPubKey+ " ("+temp_channel_id+")");
        var pseudo_hash = Kit.bytesToHexString(Kit.hash256(temp_channel_id));

        var funding_amount = sentChannelOpenings.get(peerPubKey).getFunding();
        var funding_tx = new UVTimechain.Transaction(pseudo_hash, UVTimechain.TxType.FUNDING_TX,funding_amount,getPubKey(),peerPubKey);
        // No need to model the actual signatures with the two messages below, leaving placeholder for future extensions ;)
        // bolt: send funding_created
        // bolt: received funding_signed
        uvManager.getTimechain().broadcastTx(funding_tx);
        var exec = Executors.newSingleThreadExecutor();
        exec.submit(()-> waitFundingConfirmation(peerPubKey,funding_tx,acceptMessage.getMinimum_depth()));
    }


    /**
     *
     * @param peer_id
     * @param tx
     */
    private void waitFundingConfirmation(String peer_id, UVTimechain.Transaction tx, int min_depth) {

        // Abstractions:
        // - No in-mempool waiting room after broadcasting (just wait the blocks...)
        // - This function is called by channel inititor, which will alert peer with BOLT funding_locked message:
        // Actually, even the peer should monitor onchain confirmation on its own, not trusting channel initiator

        Thread.currentThread().setName("WaitTx "+tx.txId().substring(0,4));
        log("Waiting for confirmation of funding "+tx);

        var wait_conf = uvManager.getTimechain().getTimechainLatch(min_depth);
        try {
            wait_conf.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log("Confirmed funding tx "+tx);

        var timestamp = uvManager.getTimechain().getCurrentBlock();
        var request = sentChannelOpenings.get(peer_id);
        var channel_id = generateChannelId(tx);
        var newChannel = new UVChannel(channel_id, this.getPubKey(),peer_id,request.getFunding(),request.getChannelReserve(),request.getPushMSAT());

        updateOnChainBalance(getOnChainBalance()- request.getFunding());
        updateOnchainPending(getOnchainPending()-request.getFunding());

        this.channels.put(channel_id,newChannel);
        channelGraph.addLNChannel(newChannel);

        int base_fee = uvManager.getConfig().getMultivalPropertyRandomIntItem("base_fee_set");
        int fee_ppm = uvManager.getConfig().getMultivalPropertyRandomIntItem("ppm_fee_set");
        var newPolicy = new LNChannel.Policy(40,base_fee,fee_ppm);

        String from = this.getPubKey();
        String signer = this.getPubKey();
        // when funding tx is confirmed after minimim depth
        var msg_announcement = new GossipMsgChannelAnnouncement(from,channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        var msg_update = new GossipMsgChannelUpdate(from,signer,channel_id,timestamp,0,newPolicy);

        // local update
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channelGraph.updateChannel(msg_update);

        uvManager.getNode(peer_id).fundingLocked(newChannel);

        broadcastToPeers(from,msg_announcement);
        broadcastToPeers(from,msg_update);

        sentChannelOpenings.remove(peer_id);
    }

    /**
     *
     * @param newChannel
     */
    public void fundingLocked(UVChannel newChannel) {

        log("Received funding_locked for "+newChannel.getId());

        channels.put(newChannel.getId(), newChannel);

        // setting a random policy
        int base_fee = uvManager.getConfig().getMultivalPropertyRandomIntItem("base_fee_set");
        int fee_ppm = uvManager.getConfig().getMultivalPropertyRandomIntItem("ppm_fee_set");
        var newPolicy = new LNChannel.Policy(40,base_fee,fee_ppm);
        channels.get(newChannel.getId()).setPolicy(getPubKey(),newPolicy);

        channelGraph.addLNChannel(newChannel);
        channelGraph.updateChannel(this.getPubKey(),newPolicy,newChannel.getId());

        // sender is set as the channel initiator, so that it's excluded from broadcasting
        var timestamp = uvManager.getTimechain().getCurrentBlock();
        var message_ann = new GossipMsgChannelAnnouncement(newChannel.getInitiator(),newChannel.getId(),newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(newChannel.getInitiator(), message_ann);

        // here sender is set as current node, all the other peers should receive the update
        String sender = this.getPubKey();
        var msg_update = new GossipMsgChannelUpdate(sender,this.getPubKey(),newChannel.getId(),timestamp,0,newPolicy);
        broadcastToPeers(sender,msg_update);
    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
   public void broadcastToPeers(String fromID, GossipMsg msg) {

       var current_age = uvManager.getTimechain().getCurrentBlock() -msg.getTimeStamp();
       if (current_age> uvManager.getConfig().getIntProperty("p2p_max_age")) return;
       if (msg.getForwardings()>= uvManager.getConfig().getIntProperty("p2p_max_hops"))  return;

       for (P2PNode peer: peers.values()) {
            if (peer.getPubKey().equals(fromID)) continue;
            sendToPeer(peer,msg);
        }
    }

    private void sendToPeer(P2PNode peer, P2PMessage msg) {
       //debug("Sending message "+msg+ " to "+peer.getPubKey());
       uvManager.sendMessageToNode(peer.getPubKey(), msg);
    }

    /**
     *
     * @param msg
     */
    public void receiveMessage(P2PMessage msg) {
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
    private void p2pProcessGossip() {

        int max_msg = uvManager.getConfig().getIntProperty("gossip_flush_size");

        while (isP2PRunning() && !GossipMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            GossipMsg msg = GossipMessageQueue.poll();

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var announce_msg = (GossipMsgChannelAnnouncement) msg.nextMsgToForward(this.getPubKey());
                    var new_channel_id = announce_msg.getChannelId();
                    if (!channelGraph.hasChannel(new_channel_id)) {
                        debug("GOSSIP: Adding to graph new channel "+new_channel_id);
                        this.channelGraph.addAnnouncedChannel(announce_msg);
                        broadcastToPeers(msg.getSender(),announce_msg);
                    }
                    else {
                        //log("Not adding already existing graph element for channel "+new_channel_id);
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
                        debug("GOSSIP: Updating local channel "+channel_id);
                        channels.get(channel_id).setPolicy(updater_id,message.getUpdatedPolicy());
                        getChannelGraph().updateChannel(message);
                        var next = message.nextMsgToForward(this.getPubKey());
                        broadcastToPeers(message.getSender(),next);
                    } // not my local channel, but I have an entry to be updated...
                    else {
                        //debug("Received update for non local channel ");
                        if (getChannelGraph().hasChannel(channel_id)) {
                            debug("GOSSIP: Updating non-local channel "+channel_id);
                            getChannelGraph().updateChannel(message);
                            var next = message.nextMsgToForward(this.getPubKey());
                            broadcastToPeers(message.getSender(),next);
                        }
                        else {
                            debug("GOSSIP: Skipping update for unknown channel "+channel_id);
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
     */
    public void runServices() {

        try {
            checkChannelsMsgQueue();
            checkHTLCMsgQueue();
            p2pProcessGossip();

        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * Check for incoming openchannel request/acceptance
     */
    private void checkChannelsMsgQueue() {

        final int max = 20;
        int n = 0;

        while (channelsAcceptedQueue.size()>0 && n++ < max) {
            var msg = channelsAcceptedQueue.poll();
            channelAccepted(msg);
        }
        n = 0;

        while (channelsToAcceptQueue.size()>0 && n++ < max ) {
            var msg = channelsToAcceptQueue.poll();
            acceptChannel(msg);
        }
    }

    /**
     * Check for HTLC related messages
     */
    private void checkHTLCMsgQueue() {
        final int max = 20;
        int n = 0;

        while (updateAddHTLCQueue.size()>0 && n++ < max ) {
            var msg = updateAddHTLCQueue.poll();
            try {
                processUpdateAddHTLC(msg);
            }
            catch (Exception e) { e.printStackTrace(); };

        }

        n = 0;
        while (updateFulFillHTLCQueue.size()>0 && n++ < max ) {
            var msg = updateFulFillHTLCQueue.poll();
            try {
                processUpdateFulfillHTLC(msg);
            } catch (Exception e) { e.printStackTrace(); };
        }
        n = 0;
        while (updateFailHTLCQueue.size()>0 && n++ < max ) {
            var msg = updateFailHTLCQueue.poll();
            try {
                processUpdateFailHTLC(msg);
            } catch (Exception e) { e.printStackTrace(); };
        }
        // check for expired htlc
    }


    public boolean advanceChannelStatus(String channel_id, int node1_balance, int node2_balance ) {

        var channel = channels.get(channel_id);
        debug("Updating "+channel+ " to new balances "+node1_balance+","+node2_balance);
        try {
            channel.newCommitment(node1_balance,node2_balance);
        }
        catch (Exception e) {
            e.printStackTrace();
        }
        return true;
    }


    /**
     * Move sat from local side to the other side of the channel, update balance accordingly
     * @param channel_id
     * @param amount amount to be moved in sats
     * @return true if the channel update is successful
     */
    protected synchronized boolean pushSats(String channel_id, int amount) {

        var target_channel = this.channels.get(channel_id);

        boolean success;
        int new_node1_balance;
        int new_node2_balance;

        if (this.getPubKey().equals(target_channel.getNode1PubKey())) {
            new_node1_balance =target_channel.getNode1Balance()-amount;
            new_node2_balance =target_channel.getNode2Balance()+amount;
        }
        else {
            new_node1_balance =target_channel.getNode1Balance()+amount;
            new_node2_balance =target_channel.getNode2Balance()-amount;
        }

        if (new_node1_balance > 0 && new_node2_balance > 0) {
            this.advanceChannelStatus(channel_id,new_node1_balance,new_node2_balance);
        }
        else {
            log("pushSats: Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getNode2PubKey());
            return false;
        }
        return true;
    }



    @Override
    public String toString() {

        int size;
        if (getGossipMsgQueue()!=null)
            size = getGossipMsgQueue().size();
        else size = 0;


        String s = String.format("%-4s %-25s %-4s %-3d %-5s %-10d %-2s %-10d",pubkey,"("+alias+")", "#ch:", channels.size(),"onchain:", getOnChainBalance(), "ln:", getLightningBalance());


        return s;
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
            for (P2PNode p:peers.values()) {
                s.writeObject(p.getPubKey());
            }

            s.writeObject(GossipMessageQueue);
            s.writeObject(this.channelsAcceptedQueue);
            s.writeObject(this.channelsToAcceptQueue);
            s.writeObject(this.updateAddHTLCQueue);
            s.writeObject(this.updateFulFillHTLCQueue);
            s.writeObject(this.updateFailHTLCQueue);

            s.writeObject(this.generatedInvoices);
            s.writeObject(this.pendingInvoices);
            s.writeObject(this.receivedHTLC);
            s.writeObject(this.pendingHTLC);
            s.writeObject(this.sentChannelOpenings);

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
            GossipMessageQueue = (Queue<GossipMsg>)s.readObject();
            channelsAcceptedQueue = (Queue<MsgAcceptChannel>) s.readObject();
            channelsToAcceptQueue = (Queue<MsgOpenChannel>) s.readObject();
            updateAddHTLCQueue = (Queue<MsgUpdateAddHTLC>) s.readObject();
            updateFulFillHTLCQueue = (Queue<MsgUpdateFulFillHTLC>) s.readObject();
            updateFailHTLCQueue = (Queue<MsgUpdateFailHTLC>) s.readObject();
            pendingInvoices = (HashMap<String, LNInvoice>) s.readObject();
            generatedInvoices = (HashMap<Long, LNInvoice>)s.readObject();
            receivedHTLC = (HashMap<String, MsgUpdateAddHTLC>) s.readObject();
            pendingHTLC = (HashMap<String, MsgUpdateAddHTLC>) s.readObject();
            sentChannelOpenings = (HashMap<String, MsgOpenChannel>) s.readObject();

            channelGraph = (ChannelGraph) s.readObject();

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
            peers.put(p, uvManager.getP2PNode(p));
    }
}
