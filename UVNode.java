import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.*;

public class UVNode implements LNode,P2PNode, Serializable,Comparable<UVNode> {

    @Serial
    private static final long serialVersionUID = 120675L;

    private NodeBehavior behavior;
    private final String pubkey;
    private final String alias;
    private int onchainBalance;


    // serialized and restored manually, to avoid stack overflow
    transient private UVNetworkManager uvNetworkManager;
    transient private ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    transient private ChannelGraph channelGraph;
    transient private ConcurrentHashMap<String, P2PNode> peers = new ConcurrentHashMap<>();

    transient private boolean p2pIsRunning = false;
    transient private ArrayList<String> saved_peers_id;

    transient public ScheduledFuture<?> p2pHandler;
    transient private Queue<Message> p2PMessageQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgOpenChannel> channelsToAcceptQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgAcceptChannel> channelsAcceptedQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateAddHTLC> updateAddHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateFulFillHTLC> updateFulFillHTLCQueue = new ConcurrentLinkedQueue<>();
    transient private HashMap<Long, LNInvoice> generatedInvoices = new HashMap<>();
    transient private HashMap<String, LNInvoice> pendingInvoices = new HashMap<>();
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
        this.onchainBalance = funding;
        // change lamba function here to log to a different target
        channelGraph = new ChannelGraph(pubkey);
    }

    public Queue<Message> getP2PMessageQueue() {
        return p2PMessageQueue;
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
    public synchronized int getOnChainBalance() {
        return onchainBalance;
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

    public synchronized int getLocalBalance(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return channel.getNode1Balance();
        else
            return channel.getNode2Balance();
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

    public boolean payInvoice(LNInvoice invoice) {

        var paths = this.findPath(this.getPubKey(),invoice.getDestination(),true);

        if (paths.size()>0) {
            pendingInvoices.put(invoice.getHash(),invoice);
            routeInvoiceOnPath(invoice,paths.get(0));
            return true;
        }

        log("No path found for destination "+ invoice.getDestination());

        return false;
    }

    public ArrayList<ArrayList<ChannelGraph.Edge>> findPath(String start, String end, boolean stopfirst) {
        return this.getChannelGraph().findPath(start,end,stopfirst);
    }

    /**
     *
     * @param invoice
     * @param path
     * @return
     */
    private void routeInvoiceOnPath(LNInvoice invoice, ArrayList<ChannelGraph.Edge> path) {
        StringBuilder s = new StringBuilder("Routing invoice on path: ");
        for (ChannelGraph.Edge e:path)
            s.append("(").append(e.source()).append("->").append(e.destination()).append(")");
        log(s.toString());

        // if Alice is the sender, and Dina the receiver: paths = Dina, Carol, Bob, Alice

        debug("Assembling final payload for node: "+path.get(0).destination());
        final int amount = invoice.getAmount();
        final int base_block_height = uvNetworkManager.getTimechain().getCurrentBlock();
        final var final_cltv_value = base_block_height+invoice.getMinFinalCltvExpiry();

        // last layer is the only one with the secret
        var firstHopPayload = new OnionLayer.Payload("00",amount,final_cltv_value,invoice.getHash());
        // this is the inner layer, for the final destination, so no further inner layer
        var firstOnionLayer = new OnionLayer(firstHopPayload,null);
        debug(firstOnionLayer.toString());

        int fees = 0;
        var onionLayer = firstOnionLayer;
        int out_cltv = final_cltv_value;

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
                var hopPayload = new OnionLayer.Payload(channel.getId(),amount+fees,out_cltv,null);
                onionLayer = new OnionLayer(hopPayload,onionLayer);

                debug(onionLayer.toString());
                // TODO: get the proper values, to be used in next iteration
                // the fees in the carol->dina channel will be put in the Bob payload in the next loop
                // because it's bob that has to advance the fees to Carol  (Bob will do same with Alice)
                //fees += channel.getPolicy(source).fee_ppm()+channel.getPolicy(source).base_fee();
                fees += channel.getPolicy(source).fee_ppm();
                out_cltv += channel.getPolicy(source).cltv();
            }
            else {
                log("ERROR: No channel from "+source+ " to "+source);
                return;
            }
        }

        var first_hop = path.get(path.size()-1).destination();

        var channel_id = path.get(path.size()-1).id();
        var local_channel = channels.get(channel_id);
        var id = local_channel.getLastCommitNumber()+1;
        var amt_to_forward= invoice.getAmount()+fees;

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
    private void updateFulfillHTLC(MsgUpdateFulFillHTLC msg) throws IllegalStateException {

        var preimage = msg.getPayment_preimage();
        var channel = msg.getChannel_id();
        var computed_hash = Kit.bytesToHexString(Kit.sha256(BigInteger.valueOf(preimage).toByteArray()));

        // I offered a HTLC with the same hash
        if (pendingHTLC.containsKey(computed_hash)) {
            var htlc = pendingHTLC.get(computed_hash);
            log("Fulfilling " + htlc + " by pushing " + htlc.getAmount() + " from me:" + this.getPubKey() + " to channel " + channel);

            pushSats(channel, htlc.getAmount());
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
                    pendingInvoices.remove(computed_hash);
                }
                else throw new IllegalStateException("Node "+this.getPubKey()+": Missing pending Invoice for hash "+computed_hash);
            }
        }
    }

    /**
     *
     * @return
     */
    private void updateAddHTLC(final MsgUpdateAddHTLC msg) {

        log("Processing update_add_htlc: "+msg);
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


        int currentBlock = uvNetworkManager.getTimechain().getCurrentBlock();
        final var forwardingChannel = channels.get(payload.getShortChannelId());

        var my_out_cltv = forwardingChannel.getPolicy(this.getPubKey()).cltv();

        // TODO:
        /*
        if ( (msg.getCltv_expiry() > currentBlock) || (msg.getCltv_expiry()-currentBlock < my_out_cltv)) {
           log("Expired cltv, HTLC hash:"+msg.getPayment_hash());
           return;
        }
         */

        //https://github.com/lightning/bolts/blob/master/02-peer-protocol.md#normal-operation
        // - once the cltv_expiry of an incoming HTLC has been reached,
        // O if cltv_expiry minus current_height is less than cltv_expiry_delta for the corresponding outgoing HTLC:
        // MUST fail that incoming HTLC (update_fail_htlc).


        if (msg.getAmount() >getLocalBalance(forwardingChannel.getId())+forwardingChannel.getReserve()) {
            log("Not enought local balance liquidity in channel "+forwardingChannel);
            debug("___SHOULD RETURN FALSE, BUT WE GO____");
            //return;
        }

        // DO HTLC UPDATE HERE....
        receivedHTLC.put(msg.getPayment_hash(),msg);

        // CREATE NEW HTLC UPDATE MESSAGE HERE USING PAYLOAD
        var amt= payload.getAmt_to_forward();
        int cltv = payload.getOutgoing_cltv_value();

        // fields that does not depend on payload, but message received
        var onion_packet = msg.getOnionPacket().getInnerLayer();
        var payhash = msg.getPayment_hash();

        // TODO: check how to update the other side
        var id = forwardingChannel.getNextCommitNumber();
        var new_msg = new MsgUpdateAddHTLC(forwardingChannel.getId(), id,amt,payhash,cltv,onion_packet.get());

        debug("Forwarding "+new_msg.toString());
        pendingHTLC.put(new_msg.getPayment_hash(), new_msg);

        var channel_peer = getChannelPeer(forwardingChannel.getId());

        sendToPeer(channel_peer,new_msg);
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
    public Optional<String> getExistingChannelWith(String node_id) {

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id)) {
                    return Optional.of(c.getId());
            }
        }
        return Optional.empty();
    }

    public synchronized boolean ongoingOpeningRequestWith(String nodeId) {
        if (sentChannelOpenings.containsKey(nodeId)) return true;

        return false;
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
        s.append("CH_TMP_").append(getPubKey()).append("_").append(peerPubKey);
        return s.toString();
    }
    private String generateChannelId(Timechain.Transaction tx) {
        var searchPos = uvNetworkManager.getTimechain().getTxLocation(tx);
        var position = searchPos.get();

        StringBuilder s = new StringBuilder();
        s.append(position.height()+"x"+ position.tx_index());
        return s.toString();
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
        if (this.getExistingChannelWith(initiator_id).isPresent()) {
            log("Node has already a channel with "+initiator_id);
            return;
        }
        log("Accepting channel "+ temporary_channel_id);
        var channel_peer = uvNetworkManager.getP2PNode(initiator_id);
        peers.putIfAbsent(channel_peer.getPubKey(),channel_peer);
        var acceptance = new MsgAcceptChannel(temporary_channel_id,6, Config.getVal("to_self_delay"),this.getPubKey());
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
        var funding_tx = new Timechain.Transaction(pseudo_hash, Timechain.TxType.FUNDING_TX,getPubKey(),peerPubKey);
        // No need to model the actual signatures with the two messages below, leaving placeholder for future extensions ;)
        // bolt: send funding_created
        // bolt: received funding_signed
        uvNetworkManager.getTimechain().broadcastTx(funding_tx);
        var exec = Executors.newSingleThreadExecutor();
        exec.submit(()-> waitFundingConfirmation(peerPubKey,funding_tx));
    }


    /**
     *
     * @param peer_id
     * @param tx
     */
    private void waitFundingConfirmation(String peer_id, Timechain.Transaction tx) {

        // Abstractions:
        // - No in-mempool waiting room after broadcasting (just wait the blocks...)
        // - This function is called by channel inititor, which will alert peer with BOLT funding_locked message:
        // Actually, even the peer should monitor onchain confirmation on its own, not trusting channel initiator

        log("Waiting for confirmation of "+tx);

        var wait_conf = uvNetworkManager.getTimechain().getTimechainLatch(6);
        try {
            wait_conf.await();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        log("Confirmed tx "+tx.txId());

        var timestamp = uvNetworkManager.getTimechain().getCurrentBlock();
        var request = sentChannelOpenings.get(peer_id);
        var channel_id = generateChannelId(tx);
        var newChannel = new UVChannel(channel_id, this.getPubKey(),peer_id,request.getFunding(),request.getChannelReserve(),request.getPushMSAT());
        this.channels.put(channel_id,newChannel);
        channelGraph.addLNChannel(newChannel);
        updateOnChainBalance(getOnChainBalance()- request.getFunding());

        var newPolicy = new LNChannel.Policy(20,1000,50);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channelGraph.updateChannel(channel_id,newPolicy);

        uvNetworkManager.getUVNodes().get(peer_id).fundingLocked(newChannel);

        // when funding tx is confirmed after minimim depth
        var msg_announcement = new MsgChannelAnnouncement(channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(msg_announcement);
        var msg_update = new MsgChannelUpdate(this.getPubKey(),channel_id,timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

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

        var timestamp = uvNetworkManager.getTimechain().getCurrentBlock();
        var message_ann = new MsgChannelAnnouncement(newChannel.getId(),newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(message_ann);

        var msg_update = new MsgChannelUpdate(this.getPubKey(),newChannel.getId(),timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
   public void broadcastToPeers(MessageGossip msg) {

       var current_age = uvNetworkManager.getTimechain().getCurrentBlock() -msg.getTimeStamp();
       if (current_age> Config.getVal("max_p2p_age")) return;
       if (msg.getForwardings()>= Config.getVal("max_p2p_hops")) {
           //log("Too much forwardings ("+msg.getForwardings()+") discarding "+msg);
           return;
       }

       for (P2PNode peer: peers.values()) {
            if (!peer.getPubKey().equals(this.getPubKey()))
                sendToPeer(peer,msg);
        }
    }

    private void sendToPeer(P2PNode peer, Message msg) {
       //debug("Sending message "+msg+ " to "+peer.getPubKey());
       uvNetworkManager.sendMessageToNode(peer.getPubKey(), msg);
    }

    /**
     *
     * @param msg
     */
    public void receiveMessage(Message msg) {
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
                var htlc = (MsgUpdateAddHTLC)msg;
                updateAddHTLCQueue.add(htlc);
            }
            case UPDATE_FULFILL_HTLC -> {
                var htlc = (MsgUpdateFulFillHTLC)msg;
                updateFulFillHTLCQueue.add(htlc);
            }

            default -> this.p2PMessageQueue.add(msg);
        }
    }

    public Queue<Message> getP2PMsgQueue() {
        return this.p2PMessageQueue;
    }

    public synchronized void stopP2PServices() {
       this.p2pIsRunning = false;
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

        if (p2PMessageQueue.size()>0) {
            debug(">>> Message queue not empty, processing "+ p2PMessageQueue.size()+" elements..");
        }
        else {
            return;
        }

        int max_msg = Config.getVal("p2p_flush_size");
        while (isP2PRunning() && !p2PMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            MessageGossip msg = (MessageGossip) p2PMessageQueue.poll();

            // Do again the control on message age, maybe it's been stuck in the queue for long...

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var announce_msg = (MsgChannelAnnouncement) msg.getNext();
                    var new_channel_id = announce_msg.getChannelId();
                    if (!channelGraph.hasChannel(new_channel_id)) {
                        //log("Adding to graph non existent channel "+new_channel_id);
                        this.channelGraph.addAnnouncedChannel(announce_msg);
                        broadcastToPeers(announce_msg);
                    }
                    else {
                        //log("Not adding already existing graph element for channel "+new_channel_id);
                    }

                }
                // TODO: 4 times per day, per channel (antonopoulos)
                case CHANNEL_UPDATE -> {
                    var message = (MsgChannelUpdate) msg;
                    // skip channel updates of own channels
                    var updater_id = message.getNode();
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
                        var next = message.getNext();
                        broadcastToPeers(next);
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
        synchronized (this) {
            this.p2pIsRunning = true;
        }

        try {
            checkChannels();
            checkHTLC();
            p2pProcessGossip();

        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void checkChannels() {

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

    private void checkHTLC() {
        final int max = 20;
        int n = 0;

        while (updateAddHTLCQueue.size()>0 && n++ < max ) {
            var msg = updateAddHTLCQueue.poll();
            try {
                updateAddHTLC(msg);
            }
            catch (Exception e) { e.printStackTrace(); };

        }

        n = 0;
        while (updateFulFillHTLCQueue.size()>0 && n++ < max ) {
            var msg = updateFulFillHTLCQueue.poll();
            try {
                updateFulfillHTLC(msg);
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

    private synchronized void updateOnChainBalance(int new_balance) {
        this.onchainBalance = new_balance;
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
            debug("Advancing "+target_channel.getId()+" status balances to ("+new_node1_balance+","+new_node2_balance+")");
            this.advanceChannelStatus(channel_id,new_node1_balance,new_node2_balance);
            var peer = getChannelPeer(target_channel.getId());
            peer.advanceChannelStatus(channel_id,new_node1_balance,new_node2_balance);
        }
        else {
            log("Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getNode2PubKey());
            return false;
        }
        return true;
    }



    @Override
    public String toString() {

        int size;
        if (getP2PMsgQueue()!=null)
            size = getP2PMsgQueue().size();
        else size = 0;

        return "*PubKey:'" + pubkey + '\'' + "("+alias+")"+ ", ch:" + channels.size() +
                ", onchain:" + onchainBalance + ", ln:" + getLightningBalance() + ", p2pq: "+size+'}';
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

            s.writeObject(p2PMessageQueue);
            s.writeObject(this.channelsAcceptedQueue);
            s.writeObject(this.channelsToAcceptQueue);
            s.writeObject(this.updateAddHTLCQueue);
            s.writeObject(this.updateFulFillHTLCQueue);

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
            p2PMessageQueue = (Queue<Message>)s.readObject();
            channelsAcceptedQueue = (Queue<MsgAcceptChannel>) s.readObject();
            channelsToAcceptQueue = (Queue<MsgOpenChannel>) s.readObject();
            updateAddHTLCQueue = (Queue<MsgUpdateAddHTLC>) s.readObject();
            updateFulFillHTLCQueue = (Queue<MsgUpdateFulFillHTLC>) s.readObject();
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
}
