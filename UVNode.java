import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public class UVNode implements LNode,P2PNode, Serializable,Comparable<UVNode> {

    private int skipped_msg = 0;

    @Serial
    private static final long serialVersionUID = 120675L;

    private NodeBehavior behavior;
    private final String pubkey;
    private final String alias;
    private int onchainBalance;
    private int onchainPending = 0;

    // serialized and restored manually, to avoid stack overflow
    transient private UVNetworkManager uvNetworkManager;
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
     * @param uvNetworkManager an instance of a Ultraviolet Manager to attach
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param funding initial onchain balance
     */
    public UVNode(UVNetworkManager uvNetworkManager, String pubkey, String alias, int funding) {
        this.uvNetworkManager = uvNetworkManager;
        this.pubkey = pubkey;
        this.alias = alias;
        updateOnChainBalance(funding);
        channelGraph = new ChannelGraph(pubkey);
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
    public NodeBehavior getBehavior() {
        return behavior;
    }
    private void log(String s) {
         UVNetworkManager.log(this.getPubKey()+":"+s);
    }

    private void debug(String s) {
        if (Config.get("debug").equals("true"))  {
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
            return uvNetworkManager.getP2PNode(channel.getNode2PubKey());
        else
            return uvNetworkManager.getP2PNode(channel.getNode1PubKey());
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
        this.uvNetworkManager = uvm;
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
        while (pendingInvoices.containsKey(hash)) {
            try {
                System.out.println("WAITING...");
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        return (payedInvoices.containsKey(hash));
    }


    public boolean checkPath(ArrayList<ChannelGraph.Edge> path, int amount, int maxFees) {

        log("Checking path "+ChannelGraph.pathString(path));
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

    public void payInvoice(LNInvoice invoice) {

        log("Processing "+invoice);
        var paths = this.findPaths(invoice.getDestination(),false);

        boolean success = false;
        if (paths.size()>0) {
            log("Found "+paths.size()+" paths to "+invoice.getDestination());

            int n = 0;
            for (var path: paths) {
                n++;
                log("Trying path #"+n);
                if (!checkPath(path,invoice.getAmount(),777)) continue;
                routeInvoiceOnPath(invoice,path);

                if (waitForInvoiceCleared(invoice.getHash())) {
                    success = true;
                    break;
                }
            }
            if (success) {
                log("Successfull processed invoice "+invoice.getHash());
            }
            else log("Failed routing for invoice "+invoice.getHash());
        }
        else
            log("No path found for destination "+ invoice.getDestination());
    }

    public ArrayList<ArrayList<ChannelGraph.Edge>> findPaths(String destination, boolean stopfirst) {
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
        final int baseBlockHeight = uvNetworkManager.getTimechain().getCurrentBlock();
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

            var path_channel = uvNetworkManager.getChannelFromNodes(source,dest);
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
                cumulatedFees += channel.getPolicy(source).fee_ppm();
                out_cltv += channel.getPolicy(source).cltv();
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

        sendToPeer(uvNetworkManager.getP2PNode(first_hop),update_htcl);
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
            log("Fulfilling " + htlc + " by pushing " + htlc.getAmount() + " from me:" + this.getPubKey() + " to channel " + channel);

            if (!pushSats(channel, htlc.getAmount())) {
                throw new IllegalStateException("Cannot push sats!");
            }

            pendingHTLC.remove(computed_hash);

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

        int fees = msg.getAmount()-payload.getAmtToForward();

        if (fees>=0) {
            // TODO: check fees here...
            debug("Fees "+fees+" ok for channel "+forwardingChannel.getPolicy(this.getPubKey()));

        }

        int currentBlock = uvNetworkManager.getTimechain().getCurrentBlock();
        var my_out_cltv = forwardingChannel.getPolicy(this.getPubKey()).cltv();

        var incomingPeer = getChannelPeer(msg.getChannel_id());

        //https://github.com/lightning/bolts/blob/master/02-peer-protocol.md#normal-operation
        // - once the cltv_expiry of an incoming HTLC has been reached,
        // O if cltv_expiry minus current_height is less than cltv_expiry_delta for the corresponding outgoing HTLC:
        // MUST fail that incoming HTLC (update_fail_htlc).

        var blocks_until_expire = msg.getCLTVExpiry()-currentBlock;

        if ( (blocks_until_expire <=0) || (blocks_until_expire < my_out_cltv)) {
           log("Expired (blocks until expire "+blocks_until_expire+ ", my out cltv:"+my_out_cltv+" for HTLC hash:"+msg.getPayment_hash());
           var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "CLTV expired");
           sendToPeer(incomingPeer,fail_msg);
           return;
        }

        if (msg.getAmount() > getLocalChannelBalance(forwardingChannel.getId())+forwardingChannel.getReserve()) {
            log("Not enought local balance liquidity in channel "+forwardingChannel);
            var fail_msg = new MsgUpdateFailHTLC(msg.getChannel_id(), msg.getId(), "temporary_channel_failure");
            sendToPeer(incomingPeer,fail_msg);
            return;
        }


        // CREATE NEW HTLC UPDATE MESSAGE HERE USING PAYLOAD
        var amt= payload.getAmtToForward();
        int cltv = payload.getOutgoingCLTV();

        // fields that does not depend on payload, but message received
        var onion_packet = msg.getOnionPacket().getInnerLayer();
        var payhash = msg.getPayment_hash();

        var new_msg = new MsgUpdateAddHTLC(forwardingChannel.getId(), forwardingChannel.increaseHTLCId(),amt,payhash,cltv,onion_packet.get());

        debug("Forwarding "+new_msg);
        receivedHTLC.put(msg.getPayment_hash(),msg);
        pendingHTLC.put(new_msg.getPayment_hash(), new_msg);

        var next_channel_peer = getChannelPeer(forwardingChannel.getId());
        sendToPeer(next_channel_peer,new_msg);
    }

    /**
     *
     * @param behavior defines some operational policies,e.g., how many channel try to open, of which size etc
     */
    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
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
        var block = uvNetworkManager.getTimechain().getCurrentBlock();
        s.append("tmp_id").append(getPubKey()).append("_").append(peerPubKey);
        return s.toString();
    }
    private String generateChannelId(UVTimechain.Transaction tx) {
        var searchPos = uvNetworkManager.getTimechain().getTxLocation(tx);
        var position = searchPos.get();

        return position.height() + "x" + position.tx_index();
    }
    /**
     * Mapped to LN protocol message: open_channel
     * @param peerPubKey the target node partner to open the channel
     */
    public void openChannel(String peerPubKey, int channel_size) {

        var peer = uvNetworkManager.getP2PNode(peerPubKey);
        peers.putIfAbsent(peer.getPubKey(),peer);
        var tempChannelId = generateTempChannelId(peerPubKey);

        log("Opening channel to "+peerPubKey+ " (temp_id: "+tempChannelId+")");

        updateOnchainPending(getOnchainPending()+channel_size);

        var msg_request = new MsgOpenChannel(tempChannelId,channel_size, 0, 0, 30, this.pubkey);
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
        var channel_peer = uvNetworkManager.getP2PNode(initiator_id);
        peers.putIfAbsent(channel_peer.getPubKey(),channel_peer);
        var acceptance = new MsgAcceptChannel(temporary_channel_id,Config.getVal("minimum_depth"), Config.getVal("to_self_delay"),this.getPubKey());
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
        var funding_tx = new UVTimechain.Transaction(pseudo_hash, UVTimechain.TxType.FUNDING_TX,getPubKey(),peerPubKey);
        // No need to model the actual signatures with the two messages below, leaving placeholder for future extensions ;)
        // bolt: send funding_created
        // bolt: received funding_signed
        uvNetworkManager.getTimechain().broadcastTx(funding_tx);
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

        var wait_conf = uvNetworkManager.getTimechain().getTimechainLatch(min_depth);
        try {
            wait_conf.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log("Confirmed funding tx "+tx.txId());

        var timestamp = uvNetworkManager.getTimechain().getCurrentBlock();
        var request = sentChannelOpenings.get(peer_id);
        var channel_id = generateChannelId(tx);
        var newChannel = new UVChannel(channel_id, this.getPubKey(),peer_id,request.getFunding(),request.getChannelReserve(),request.getPushMSAT());
        this.channels.put(channel_id,newChannel);
        channelGraph.addLNChannel(newChannel);

        updateOnChainBalance(getOnChainBalance()- request.getFunding());
        updateOnchainPending(getOnchainPending()-request.getFunding());

        var newPolicy = new LNChannel.Policy(20,1000,50);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channelGraph.updateChannel(channel_id,newPolicy);

        uvNetworkManager.getNode(peer_id).fundingLocked(newChannel);

        // when funding tx is confirmed after minimim depth
        String from = this.getPubKey();
        String signer = this.getPubKey();
        var msg_announcement = new GossipMsgChannelAnnouncement(from,channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(from,msg_announcement);
        var msg_update = new GossipMsgChannelUpdate(from,signer,channel_id,timestamp,0,newPolicy);
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
        var newPolicy = new LNChannel.Policy(20,1000,200);
        channels.get(newChannel.getId()).setPolicy(getPubKey(),newPolicy);
        channelGraph.addLNChannel(newChannel);
        channelGraph.updateChannel(newChannel.getId(), newPolicy);

        // sender is set as the channel initiator, so that it's excluded from broadcasting
        var timestamp = uvNetworkManager.getTimechain().getCurrentBlock();
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

       var current_age = uvNetworkManager.getTimechain().getCurrentBlock() -msg.getTimeStamp();
       if (current_age> Config.getVal("p2p_max_age")) return;
       if (msg.getForwardings()>= Config.getVal("p2p_max_hops"))  return;

       for (P2PNode peer: peers.values()) {
            if (peer.getPubKey().equals(fromID)) continue;
            sendToPeer(peer,msg);
        }
    }

    private void sendToPeer(P2PNode peer, P2PMessage msg) {
       //debug("Sending message "+msg+ " to "+peer.getPubKey());
       uvNetworkManager.sendMessageToNode(peer.getPubKey(), msg);
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

        int max_msg = Config.getVal("gossip_flush_size");

        while (isP2PRunning() && !GossipMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            GossipMsg msg = (GossipMsg) GossipMessageQueue.poll();

            // Do again the control on message age, maybe it's been stuck in the queue for long...

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var announce_msg = (GossipMsgChannelAnnouncement) msg.nextMsgToForward(this.getPubKey());
                    var new_channel_id = announce_msg.getChannelId();
                    if (!channelGraph.hasChannel(new_channel_id)) {
                        //log("Adding to graph non existent channel "+new_channel_id);
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
                        channels.get(channel_id).setPolicy(updater_id,message.getUpdatedPolicy());
                    }
                    //https://github.com/lightning/bolts/blob/master/07-routing-gossip.md#the-channel_update-message
                    /*
                    The receiving node:
                    if the short_channel_id does NOT match a previous channel_announcement, OR if the channel has been closed in the meantime:
                    MUST ignore channel_updates that do NOT correspond to one of its own channels.
                     */
                    if (getChannelGraph().hasChannel(channel_id)) {
                        getChannelGraph().updateChannel(channel_id,message.getUpdatedPolicy());
                        var next = message.nextMsgToForward(this.getPubKey());
                        broadcastToPeers(message.getSender(),next);
                    }
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
            new_node1_balance =target_channel.getNode2Balance()+amount;
            new_node2_balance =target_channel.getNode1Balance()-amount;
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

        return "*PubKey:'" + pubkey + '\'' + "("+alias+")"+ ", ch:" + channels.size() +
                ", onchain(pending):" + getOnChainBalance() + "("+getOnchainPending()+"), ln:" + getLightningBalance() + ", p2pq: "+size+'}';
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
            peers.put(p, uvNetworkManager.getP2PNode(p));
    }

    public static class NodeBehavior implements Serializable {
        @Serial
        private static final long serialVersionUID = 9579L;
        // TODO: define more profiles here
        public final static int Msat = (int)1e6;
        public final static NodeBehavior MANY_SMALL = new NodeBehavior(100,Msat/10,5*Msat);
        public final static NodeBehavior MANY_BIG = new NodeBehavior(100,5*Msat,10*Msat);
        public final static NodeBehavior MEDIUM_SMALL = new NodeBehavior(30,Msat/10,5*Msat);
        public final static NodeBehavior MEDIUM_BIG = new NodeBehavior(30,5*Msat,10*Msat);
        public final static NodeBehavior FEW_SMALL = new NodeBehavior(10,Msat/10,5*Msat);
        public final static NodeBehavior FEW_BIG = new NodeBehavior(10,5*Msat,10*Msat);

        private final int target_channel_number;
        private final int min_channel_size;
        private final int max_channel_size;

        public int getTargetChannelsNumber() {
            return target_channel_number;
        }

        public int getMinChannelSize() {
            return min_channel_size;
        }

        public int getMaxChannelSize() {
            return max_channel_size;
        }

        public NodeBehavior(int target_channel_number, int min_channel_size, int max_channel_size) {
            this.target_channel_number = target_channel_number;
            this.min_channel_size = min_channel_size;
            this.max_channel_size = max_channel_size;
        }
    }
}
