import java.io.*;
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
    transient private UVNetworkManager uvm;
    transient private ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    transient private ChannelGraph channelGraph;
    transient private ConcurrentHashMap<String, P2PNode> peers = new ConcurrentHashMap<>();

    transient private boolean p2pIsRunning = false;
    transient private ArrayList<String> saved_peers_id;

    transient public ScheduledFuture<?> p2pHandler;
    transient private Queue<Message> p2PMessageQueue = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgOpenChannel> pendingChannelsToAccept = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgAcceptChannel> pendingChannelsAccepted = new ConcurrentLinkedQueue<>();
    transient private Queue<MsgUpdateAddHTLC> pendingUpdateAddHTLC = new ConcurrentLinkedQueue<>();
    transient private HashSet<LNInvoice> generatedInvoices = new HashSet<>();
    transient private HashMap<String, MsgUpdateAddHTLC> forwardedHTLC = new HashMap<>();
    transient private HashMap<String, MsgOpenChannel> sentChannelOpenings = new HashMap<>();

    public ScheduledFuture<?> getP2pHandler() {
        return p2pHandler;
    }

    public Queue<Message> getP2PMessageQueue() {
        return p2PMessageQueue;
    }

    public Queue<MsgOpenChannel> getPendingChannelsToAccept() {
        return pendingChannelsToAccept;
    }

    public Queue<MsgAcceptChannel> getPendingChannelsAccepted() {
        return pendingChannelsAccepted;
    }

    public Queue<MsgUpdateAddHTLC> getPendingUpdateAddHTLC() {
        return pendingUpdateAddHTLC;
    }

    public HashSet<LNInvoice> getGeneratedInvoices() {
        return generatedInvoices;
    }

    public HashMap<String, MsgUpdateAddHTLC> getForwardedHTLC() {
        return forwardedHTLC;
    }

    public HashMap<String, MsgOpenChannel> getSentChannelOpenings() {
        return sentChannelOpenings;
    }

    public NodeBehavior getBehavior() {
        return behavior;
    }

    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     * @param uvm an instance of a Ultraviolet Manager to attach
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param funding initial onchain balance
     */
    public UVNode(UVNetworkManager uvm, String pubkey, String alias, int funding) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchainBalance = funding;
        // change lamba function here to log to a different target
        channelGraph = new ChannelGraph(pubkey);
    }

    private void log(String s) {
         UVNetworkManager.log(this.getPubKey()+":"+s);
    }

    private void debug(String s) {
        if (ConfigManager.get("debug").equals("true"))  {
            UVNetworkManager.log("DEBUG:"+this.getPubKey()+":"+s);
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
    /**
     *
     * @return returns the node public key used as node id
     */
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
            return uvm.getP2PNode(channel.getNode2PubKey());
        else
            return uvm.getP2PNode(channel.getNode1PubKey());
    }

    public synchronized int getLocalBalance(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return channel.getNode1Balance();
        else
            return channel.getNode2Balance();
    }

    /**
     *
     * @return
     */
    public ConcurrentHashMap<String, UVChannel> getChannels() {
        return channels;
    }

    /**
     *
     * @return a random node channel
     */
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
        this.uvm = uvm;
    }
    /**
     * Add a node to the list of peers and update the channel graph
     * @param node
     */
    public void addPeer(P2PNode node) {
            this.peers.putIfAbsent(node.getPubKey(),node);
    }

    /**
     * @param amount
     * @return
     */
    @Override
    public LNInvoice generateInvoice(int amount) {
        int r = ThreadLocalRandom.current().nextInt();
        var invoice = new LNInvoice(r,amount,this.getPubKey());
        if (generatedInvoices ==null) generatedInvoices = new HashSet<>();
        generatedInvoices.add(invoice);
        return invoice;
    }

    /**
     * @param invoice 
     * @param destination
     * @return
     */
    @Override
    public boolean routeInvoice(LNInvoice invoice, LNode destination) {
        //var paylooad = new OnionLayer.Payload(invoice.)
        return false;
    }

    /**
     *
     * @param invoice
     * @param path
     * @return
     */
    public void routeInvoiceOnPath(LNInvoice invoice, ArrayList<ChannelGraph.Edge> path) {
        StringBuilder s = new StringBuilder("Routing invoice on path: ");
        for (ChannelGraph.Edge e:path)
            s.append("(").append(e.source()).append("->").append(e.destination()).append(")");
        log(s.toString());

        // if Alice is the sender, and Dina the receiver: paths = Dina, Carol, Bob, Alice

        debug("** Assembling final payload for node: "+path.get(0).destination());
        final int amount = invoice.getAmount();
        final int base_block_height = uvm.getTimechain().getCurrentBlock();
        var outgoing_cltv_value = base_block_height+invoice.getMin_cltv_expiry();

        // last layer is the only one with the secret
        var firstHopPayload = new OnionLayer.Payload("00",amount,outgoing_cltv_value,invoice.getHash());
        // this is the inner layer, for the final destination, so no further inner layer
        var firstOnionLayer = new OnionLayer(firstHopPayload,null);
        debug(firstOnionLayer.toString());

        int fees = 0;
        var onionLayer = firstOnionLayer;

        // we start with the payload for Carol, which has no added fee to pay because Dina is the final hop
        // Carol will take the forwarding fees specified in the payload for Bob
        // don't need to create the last path segment onion for local node htlc
        for (int n=0;n<path.size()-1;n++) {

            var source = path.get(n).source();
            var dest = path.get(n).destination();

            var path_channel = uvm.getChannelFromNodes(source,dest);
            if (path_channel.isPresent()) {
                var channel = path_channel.get();
                debug("**Assembling Payload for node: "+source);
                var hopPayload = new OnionLayer.Payload(channel.getId(),amount+fees,outgoing_cltv_value);
                onionLayer = new OnionLayer(hopPayload,onionLayer);

                debug(onionLayer.toString());
                // TODO: get the proper values, to be used in next iteration
                // the fees in the carol->dina channel will be put in the Bob payload in the next loop
                // because it's bob that has to advance the fees to Carol  (Bob will do same with Alice)
                //fees += channel.getPolicy(source).fee_ppm()+channel.getPolicy(source).base_fee();
                fees += channel.getPolicy(source).fee_ppm();
                outgoing_cltv_value += channel.getPolicy(source).cltv();
            }
            else {
                log("ERROR: No channel from "+source+ " to "+source);
                return;
            }
        }

        var first_hop = path.get(path.size()-1).destination();
        debug("Creating HTLC messsage for the first hop: "+first_hop);

        var channel_id = path.get(path.size()-1).id();
        var local_channel = channels.get(channel_id);
        var id = local_channel.getLastCommitNumber()+1;
        var amt_to_forward= invoice.getAmount()+fees;

        var update_htcl = new MsgUpdateAddHTLC(channel_id,id,amt_to_forward,invoice.getHash(),outgoing_cltv_value,onionLayer);
        debug(update_htcl.toString());

        sendToPeer(uvm.getP2PNode(first_hop),update_htcl);
        forwardedHTLC.put(update_htcl.getPayment_hash(),update_htcl);
    }

    /**
     *
     * @return
     */
    private boolean updateAddHTLC(final MsgUpdateAddHTLC msg) {

        log("Processing update_add_htlc: "+msg);
        final var payload = msg.getOnionPacket().getPayload();

        int currentBlock = uvm.getTimechain().getCurrentBlock();
        final var forwardingChannel = channels.get(payload.getShort_channel_id());

        var my_out_cltv = forwardingChannel.getPolicy(this.getPubKey()).cltv();

        if ( (msg.getCltv_expiry() > currentBlock) || (msg.getCltv_expiry()-currentBlock < my_out_cltv)) {
           log("Expired cltv, HTLC hash:"+msg.getPayment_hash());
           return false;
        }

        // TODO:
        //https://github.com/lightning/bolts/blob/master/02-peer-protocol.md#normal-operation
        // - once the cltv_expiry of an incoming HTLC has been reached,
        // O if cltv_expiry minus current_height is less than cltv_expiry_delta for the corresponding outgoing HTLC:
        // MUST fail that incoming HTLC (update_fail_htlc).


        if (payload.getShort_channel_id().equals("00")) {
            log("RECEIVED ONION, RETURNING OK");
            return true;
        }

        // the msg info will be used to update the channel htcl
        // the payload will be used to create the next htlc update message (with the inner onion as argument)


        if (msg.getAmount() >getLocalBalance(forwardingChannel.getId())+forwardingChannel.getReserve()) {
            log("Not enought local balance liquidity in channel "+forwardingChannel);
            debug("___SHOULD RETURN FALSE, BUT WE GO____");
            return false;
        }

        // DO HTLC UPDATE HERE....
        debug("STORING HTLC COMMIT FOR future application... ");
        forwardedHTLC.put(msg.getPayment_hash(),msg);

        // CREATE NEW HTLC UPDATE MESSAGE HERE USING PAYLOAD
        debug("Creating new htlc message using payload ... ");
        var amt= payload.getAmt_to_forward();
        int cltv = payload.getOutgoing_cltv_value();

        // fields that does not depend on payload, but message received
        var onion_packet = msg.getOnionPacket().getInnerLayer();
        var payhash = msg.getPayment_hash();

        var id = forwardingChannel.getLastCommitNumber()+1;
        var new_msg = new MsgUpdateAddHTLC(forwardingChannel.getId(), id,amt,payhash,cltv,onion_packet.get());

        debug(msg.toString());

        var channel_peer = getChannelPeer(forwardingChannel.getId());

        sendToPeer(channel_peer,new_msg);
        return true;

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
    // TODO: assuming single channel
    public Optional<String> getChannelWith(String node_id) {

        Optional<String> channel_id = Optional.empty();

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id)) {
                if (channel_id.isEmpty())
                    channel_id = Optional.of(c.getId());
                else  {
                    throw new RuntimeException(" Multiple channels between two peers not supported!");
                }
            }
        }
        return channel_id;
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

    private String generateFakeChannelId(String peerPubKey) {
        StringBuilder s = new StringBuilder();
        var block = uvm.getTimechain().getCurrentBlock();
        s.append("blk").append(block).append("_");
        s.append(getPubKey()).append("_").append(peerPubKey);
        return s.toString();
    }
    /**
     * Mapped to LN protocol message: open_channel
     * Open a channel with a peer node, with the features defined by the node behavior and configuration
     * Mainly used when bootstrapping: autogenerates channel id, fees, and takes all the necessary p2p actions
     *
     * @param peerPubKey the target node partner to open the channel
     * @return true if the channel has been successful opened
     */
    public void openChannel(String peerPubKey, int channel_size) {

        var peer = uvm.getP2PNode(peerPubKey);
        var channel_id = generateFakeChannelId(peerPubKey);

        var msg = new MsgOpenChannel(channel_id,channel_size, 0, 0, 0, this.pubkey);
        debug("Adding "+msg+" to pending opening requests...");
        sentChannelOpenings.put(channel_id,msg);
        sendToPeer(peer, msg);
    }

    private void channelAccepted(MsgAcceptChannel acceptMessage) {

        var channel_id = acceptMessage.getChannel_id();
        var peerPubKey = acceptMessage.getFundingPubkey();
        log("Channel "+channel_id+" accepted by peer "+ peerPubKey);
        MsgOpenChannel request = sentChannelOpenings.get(channel_id);
        var size = request.getFunding();


        var newChannel = new UVChannel(channel_id, this.getPubKey(), peerPubKey,size,request.getChannelReserve(),request.getPushMSAT());

        // Updates on channel status and balances should be in sync with other accesses (e.g. accept())
        var peer = uvm.getP2PNode(peerPubKey);
        this.addPeer(peer);
        this.channels.put(channel_id,newChannel);
        channelGraph.addLNChannel(newChannel);
        updateOnChainBalance(getOnChainBalance()- size);


        var newPolicy = new LNChannel.Policy(20,1000,50);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channelGraph.updateChannel(channel_id,newPolicy);

        var timestamp = uvm.getTimechain().getCurrentBlock();
        var msg_announcement = new MsgChannelAnnouncement(channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(msg_announcement);
        var msg_update = new MsgChannelUpdate(this.getPubKey(),channel_id,timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

        sentChannelOpenings.remove(channel_id);
    }

    /**
     *
     * @param openRequest
     * @return
     */
    public synchronized boolean acceptChannel(MsgOpenChannel openRequest) {
        var channel_id = openRequest.getChannel_id();
        var initiator_id = openRequest.getFunding_pubkey();
        log("Accepting channel "+ channel_id);
        if (this.getChannelWith(initiator_id).isPresent()) {
            log("Node has already a channel with "+initiator_id);
            return false;
        }

        var newChannel = new UVChannel(openRequest.getChannel_id(), initiator_id, this.getPubKey(), openRequest.getFunding(),openRequest.getChannelReserve(),openRequest.getPushMSAT());
        var channel_peer = uvm.getP2PNode(initiator_id);
        addPeer(channel_peer);
        var acceptance = new MsgAcceptChannel(channel_id,0,this.getPubKey());
        sendToPeer(channel_peer,acceptance);

        channels.put(newChannel.getId(), newChannel);
        var newPolicy = new LNChannel.Policy(20,1000,200);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channelGraph.addLNChannel(newChannel);
        channelGraph.updateChannel(channel_id,newPolicy);

        var timestamp = uvm.getTimechain().getCurrentBlock();
        var message_ann = new MsgChannelAnnouncement(newChannel.getId(),newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(message_ann);

        var msg_update = new MsgChannelUpdate(this.getPubKey(),newChannel.getId(),timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

        return true;
    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
   public void broadcastToPeers(MessageGossip msg) {

       var current_age = uvm.getTimechain().getCurrentBlock() -msg.getTimeStamp();
       if (current_age> ConfigManager.getVal("max_p2p_age")) return;
       if (msg.getForwardings()>= ConfigManager.getVal("max_p2p_hops")) {
           //log("Too much forwardings ("+msg.getForwardings()+") discarding "+msg);
           return;
       }

       for (P2PNode peer: peers.values()) {
            if (!peer.getPubKey().equals(this.getPubKey()))
                sendToPeer(peer,msg);
        }
    }

    private void sendToPeer(P2PNode peer, Message msg) {
       debug("Sending message "+msg+ " to "+peer.getPubKey());
       uvm.sendMessageToNode(peer.getPubKey(), msg);
    }

    /**
     *
     * @param msg
     */
    public void receiveMessage(Message msg) {
        debug("Received "+msg);
        switch (msg.getType()) {
            case OPEN_CHANNEL -> {
                var request = (MsgOpenChannel)msg;
                pendingChannelsToAccept.add(request);
            }
            case ACCEPT_CHANNEL -> {
                var acceptance = (MsgAcceptChannel)msg;
                pendingChannelsAccepted.add(acceptance);
            }
            case UPDATE_ADD_HTCL -> {
                var htlc = (MsgUpdateAddHTLC)msg;
                forwardedHTLC.put(htlc.getPayment_hash(),htlc);
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

        int max_msg = ConfigManager.getVal("p2p_flush_size");
        while (isP2PRunning() && !p2PMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            MessageGossip msg = (MessageGossip) p2PMessageQueue.poll();

            // Do again the control on message age, maybe it's been stuck in the queue for long...
            /*
            var current_age = uvm.getTimechain().getCurrentBlock() -msg.getTimeStamp();
            if (current_age> ConfigManager.getVal("max_p2p_age")) continue;

             */

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
                    else {
                        debug("Channel not announced yet! Discarding update/forwarding for "+channel_id+" (origin:"+updater_id+")");
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

        while (pendingChannelsAccepted.size()>0 && n++ < max) {
            var msg = pendingChannelsAccepted.poll();
            channelAccepted(msg);
        }
        n = 0;

        while (pendingChannelsToAccept.size()>0 && n++ < max ) {
            var msg = pendingChannelsToAccept.poll();
            acceptChannel(msg);
        }
    }

    private void checkHTLC() {
        final int max = 20;
        int n = 0;

        while (pendingUpdateAddHTLC.size()>0 && n++ < max ) {
            var msg = pendingUpdateAddHTLC.poll();
            debug("pending HTLC: "+msg.getPayment_hash());
            updateAddHTLC(msg);
        }

        // check for expired htlc
        // update_fulfill_htlc
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
    @SuppressWarnings("UnusedReturnValue")
    public synchronized boolean pushSats(String channel_id, int amount) {

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
            success = target_channel.newCommitment(new_node1_balance,new_node2_balance);
        }
        else {
            log("Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getNode2PubKey());
            success = false;
        }
        return success;
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
/********************************************************************************
 * Serialization section
 */
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
            s.writeObject(this.pendingChannelsAccepted);
            s.writeObject(this.pendingChannelsToAccept);
            s.writeObject(this.pendingUpdateAddHTLC);

            s.writeObject(this.generatedInvoices);
            s.writeObject(this.sentChannelOpenings);
            s.writeObject(this.forwardedHTLC);

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
            pendingChannelsAccepted = (Queue<MsgAcceptChannel>) s.readObject();
            pendingChannelsToAccept = (Queue<MsgOpenChannel>) s.readObject();
            pendingUpdateAddHTLC = (Queue<MsgUpdateAddHTLC>) s.readObject();

            generatedInvoices = (HashSet<LNInvoice>) s.readObject();
            sentChannelOpenings = (HashMap<String, MsgOpenChannel>) s.readObject();
            forwardedHTLC = (HashMap<String, MsgUpdateAddHTLC>) s.readObject();

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
            peers.put(p,uvm.getP2PNode(p));
    }
}
