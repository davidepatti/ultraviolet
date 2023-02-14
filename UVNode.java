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
    transient private ChannelGraph channel_graph;
    transient private ConcurrentHashMap<String, P2PNode> peers = new ConcurrentHashMap<>();

    transient private boolean p2pIsRunning = false;
    transient ArrayList<String> saved_peers_id;

    transient Queue<P2PMessage> p2PMessageQueue = new ConcurrentLinkedQueue<>();
    transient ScheduledFuture<?> p2pHandler;

    transient HashSet<LNInvoice> pendingInvoices = new HashSet<>();


    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     * @param uvm an instance of a Ultraviolet Manager to attach
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param onchain_balance initial onchain balance
     */
    public UVNode(UVNetworkManager uvm, String pubkey, String alias, int onchain_balance) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchainBalance = onchain_balance;
        // change lamba function here to log to a different target
        channel_graph = new ChannelGraph(pubkey);
    }

    private void log(String s) {
         UVNetworkManager.log(this.getPubKey()+":"+s);
    }

    public void setUVM(UVNetworkManager uvm) {
        this.uvm = uvm;
    }


    public ArrayList<LNChannel> getLNChannelList() {

        return new ArrayList<>(this.channels.values());
    }

    /**
     *
     * @return the hashmap of the current list of peers
     */
    public ConcurrentHashMap<String, P2PNode> getPeers() {
        return peers;
    }
    public ChannelGraph getChannelGraph() {
        return this.channel_graph;
    }
    /**
     * Add a node to the list of peers and update the channel graph
     * @param node
     */
    public void addPeer(P2PNode node) {
            this.peers.putIfAbsent(node.getPubKey(),node);
    }

    /**
     *
     * @return returns the current onchain balance
     */
    private synchronized int getOnChainBalance() {
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
     * @param amount
     * @return
     */
    @Override
    public LNInvoice generateInvoice(int amount) {
        int r = ThreadLocalRandom.current().nextInt();
        var invoice = new LNInvoice(r,amount,this.getPubKey());
        pendingInvoices.add(invoice);
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
    public boolean routeInvoiceOnPath(LNInvoice invoice, ArrayList<ChannelGraph.Edge> path) {
        System.out.println("Routing invoice on path:");
        path.stream().forEach(System.out::println);

        // if Alice is the sender, and Dina the receiver
        // paths = Dina, Carol, Bob, Alice    with Dina in position 0

        // the last hop payload for destination Dina is special, the only having the secret and null as channel id

        var amount = invoice.getAmount();
        var base_block_height = uvm.getTimechain().getCurrentBlock();
        var outgoing_cltv_value = base_block_height+invoice.getMin_cltv_expiry();
        var short_channel_id = "00000000";
        var hopPayload = new OnionLayer.Payload(short_channel_id,amount,outgoing_cltv_value,invoice.getHash());
        // this is the inner layer, for the final destination
        var onionLayer = new OnionLayer(hopPayload,null);

        // make the remaning hop payloads, starting with the payload for Carol
        // we must find the related connecting channels


        // we start with the payload for Carol, which has no added fee to pay because Dina is the final hop
        // Carol will take the forwarding fees specified in the payload for Bob
        int forwarding_fees=0;

        System.out.println("Final layer:");
        System.out.println(onionLayer);


        for (int n=1;n<path.size()-1;n++) {

            var to_node = path.get(n).destination();
            var from_node = path.get(n).source();

            var channel_result = uvm.getChannelFromNodes(to_node,from_node);
            if (channel_result.isPresent()) {
                var channel = channel_result.get();
                var channel_id = channel.getId();
                hopPayload = new OnionLayer.Payload(channel_id,amount+forwarding_fees,outgoing_cltv_value);
                onionLayer = new OnionLayer(hopPayload,onionLayer);

                System.out.println("------------------------------------------------------------");
                System.out.println("Onion for "+from_node);
                System.out.println(onionLayer);
                // TODO: get the proper values, to be used in next iteration
                // the forwarding fees information in the carol->dina channel are put in the Bob payload
                // because it's bob that has to advance the fees to Carol  (Bob will do same with Alice)
                forwarding_fees += channel.getNode1Policy().fee_ppm();
                outgoing_cltv_value+=channel.getNode1Policy().cltv();
            }
            else {
                log("ERROR: channel from "+from_node+ " to "+to_node);
                return false;
            }

        }

        var first_hop = path.get(path.size()-2);
        System.out.println("Sending Onion to first hop: "+first_hop);

        return uvm.getUVNodes().get(first_hop).updateAddHTLC(onionLayer);

    }

    /**
     *
     * @param onionLayer
     * @return
     */
    public boolean updateAddHTLC(OnionLayer onionLayer) {
        // TODO: check if you are the destination, and start the update_fulfill_htcl
        var forwarding_channel_id = onionLayer.getPayload().getShort_channel_id();
        System.out.println("Received onion, will forward along channel "+forwarding_channel_id);
        var forwarding_channel = channels.get(forwarding_channel_id);

        if (onionLayer.getPayload().getAmt_to_forward()>getLocalBalance(forwarding_channel.getId())) {
            System.out.println("Not enought liquidity ");
            return false;
        }

        if (onionLayer.getInnerLayer().isPresent()) {
            var channel_peer = getChannelPeer(forwarding_channel_id);
            return channel_peer.updateAddHTLC(onionLayer.getInnerLayer().get());
        }
        else {
            log("No more inner layers!");
            System.out.println("No more inner layers!");
        }
        return true;
    }

    /**
     *
     * @param channel_id
     * @return
     */
    private LNode getChannelPeer(String channel_id) {
        var channel = channels.get(channel_id);
        if (this.getPubKey().equals(channel.getNode1PubKey()))
            return uvm.getLNode(channel.getNode2PubKey());
        else
            return uvm.getLNode(channel.getNode1PubKey());
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
     * @param behavior defines some operational policies,e.g., how many channel try to open, of which size etc
     */
    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
    }


    /**
     *
     */
    public void bootstrapNode() {

        int initiated_channels =0;
        ///----------------------------------------------------------
        var warmup = ConfigManager.getBootstrapWarmup();

        // Notice: no way of doing this deterministically, timing will be always in race condition with other threads
        // Also: large warmups with short p2p message deadline can cause some node no to consider earlier node messages
        if (warmup!=0) {
            var ready_to_go = uvm.getTimechain().getTimechainLatch(ThreadLocalRandom.current().nextInt(0,warmup));
            log(" waiting "+ready_to_go.getCount()+" blocks before bootstrap... ");
            try {
                ready_to_go.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        log("Starting bootstrap!");

        while (behavior.getBoostrapChannels() > initiated_channels) {

            if (getOnChainBalance() <= behavior.getMin_channel_size()) {
                log("No more onchain balance for opening further channels of min size "+behavior.getMin_channel_size());
                break; // exit while loop
            }

            var peer_node = uvm.getRandomNode();
            var peer_pubkey = peer_node.getPubKey();

            if (peer_pubkey.equals(this.getPubKey())) continue;
            if (this.myChannelWith(peer_pubkey).isPresent()) continue;

            log("Trying to open a channel with "+peer_pubkey);

            int max = Math.min(behavior.getMax_channel_size(), onchainBalance);
            int min = behavior.getMin_channel_size();
            var channel_size = ThreadLocalRandom.current().nextInt(min,max+1);
            channel_size = (channel_size/100000)*100000;

            // onchain balance is changed only from initiator, so no problems of sync
            if (channel_size>getOnChainBalance()) {
                log("<<< Insufficient liquidity for "+channel_size+" channel opening");
                continue;
            }

            if (openChannel(peer_pubkey,channel_size)) {
                initiated_channels++;
            }
            else log("Failed opening channel to "+peer_node.getPubKey());
            //log("another round...");
        } // while

        log("Bootstrap Completed");
        uvm.getBootstrapLatch().countDown();
    }

    /**
     *
     * @param node_id
     * @return
     */
    // TODO: here
    public Optional<String> myChannelWith(String node_id) {

        for (UVChannel c:this.channels.values()) {
            if (c.getNode2PubKey().equals(node_id) || c.getNode1PubKey().equals(node_id))
                return Optional.of(c.getId());
        }
        return Optional.empty();
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
    private boolean openChannel(String peerPubKey, int channel_size) {

        var peer = uvm.getUVNodes().get(peerPubKey);
        var channel_id = generateFakeChannelId(peerPubKey);
        var newChannel = new UVChannel(channel_id, this.getPubKey(), peerPubKey,channel_size,0,0);

        // HERE WHEN ACCEPTING FROM THE BLOCKED NODE STOPS
        // notice: nothing forbids opening channels with each other reciprocally
        if (!peer.acceptChannel(this, newChannel)) {
            log("OPEN: Channel proposal to "+ peerPubKey+" not accepted");
            return false;
        }

        log("Channel "+channel_id+" accepted by peer "+ peerPubKey);
        // Updates on channel status and balances should be in sync with other accesses (e.g. accept())
        this.addPeer(peer);
        this.channels.put(channel_id,newChannel);
        channel_graph.addLNChannel(newChannel);
        updateOnChainBalance(getOnChainBalance()- channel_size);


        var newPolicy = new LNChannel.Policy(20,1000,50);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channel_graph.updateChannel(channel_id,this.getPubKey(),newPolicy);

        var timestamp = uvm.getTimechain().getCurrentBlock();
        var msg_announcement = new P2PMsgChannelAnnouncement(channel_id,newChannel.getNode1PubKey(),newChannel.getNode2PubKey(),newChannel.getCapacity(),timestamp,0);
        broadcastToPeers(msg_announcement);
        var msg_update = new P2PMsgChannelUpdate(this.getPubKey(),channel_id,timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

        log("OPEN: End opening channel with "+ peerPubKey);
        return true;
    }
    /**
     * Mapped to LN protocol message: accept_channel
     * Called by the channel initiator on a target node to propose a channel opening
     * synchronized to accept one channel per time to avoid inconsistency
     * @param channel  the channel proposal
     * @return true if accepted
     */
    public synchronized boolean acceptChannel(P2PNode initiator, UVChannel channel) {
        log("Accepting channel "+ channel.getId());
        if (this.myChannelWith(initiator.getPubKey()).isPresent()) {
            log("Node has already a channel with "+initiator.getPubKey());
            return false;
        }

        var channel_id = channel.getId();
        this.channels.put(channel.getId(), channel);
        // TODO: this is why UVChannel must contain UVNode partners and not only pubkeys
        this.addPeer(initiator);
        channel_graph.addLNChannel(channel);
        var newPolicy = new LNChannel.Policy(20,1000,200);
        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        channel_graph.updateChannel(channel_id,this.getPubKey(),newPolicy);

        var timestamp = uvm.getTimechain().getCurrentBlock();
        var message_ann = new P2PMsgChannelAnnouncement(channel.getId(),channel.getNode1PubKey(),channel.getNode2PubKey(),channel.getCapacity(),timestamp,0);
        broadcastToPeers(message_ann);

        var msg_update = new P2PMsgChannelUpdate(this.getPubKey(),channel.getId(),timestamp,0,newPolicy);
        broadcastToPeers(msg_update);

        log("<<<ACCEPT: End Accepting channel "+ channel.getId());
        return true;
    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
   public void broadcastToPeers(P2PMessage msg) {

       var current_age = uvm.getTimechain().getCurrentBlock() -msg.getTimeStamp();
       if (current_age> ConfigManager.getMaxP2PAge()) return;
       if (msg.getForwardings()>= ConfigManager.getMaxP2PHops()) {
           //log("Too much forwardings ("+msg.getForwardings()+") discarding "+msg);
           return;
       }

       for (P2PNode peer: peers.values()) {
            if (!peer.getPubKey().equals(this.getPubKey()))
                peer.receiveP2PMessage(msg);
        }
    }

    /**
     *
     * @param msg
     */
    public void receiveP2PMessage(P2PMessage msg) {
            this.p2PMessageQueue.add(msg);
    }

    public Queue<P2PMessage> getP2PMsgQueue() {
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

        if (p2PMessageQueue.size()>0)
            log(">>> Message queue not empty, processing "+ p2PMessageQueue.size()+" elements..");
        else {
            return;
        }

        int max_msg = 500;
        while (isP2PRunning() && !p2PMessageQueue.isEmpty() && max_msg>0) {
            max_msg--;
            var msg = p2PMessageQueue.poll();

            // Do again the control on message age, maybe it's been stuck in the queue for long...
            var current_age = uvm.getTimechain().getCurrentBlock() -msg.getTimeStamp();
            if (current_age> ConfigManager.getMaxP2PAge()) continue;
            //if (msg.getForwardings()>ConfigManager.getMaxP2PHops()) continue;

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var announce_msg = (P2PMsgChannelAnnouncement) msg.getNext();
                    var new_channel_id = announce_msg.getChannelId();
                    if (!channel_graph.hasChannel(new_channel_id)) {
                        //log("Adding to graph non existent channel "+new_channel_id);
                        this.channel_graph.addAnnouncedChannel(announce_msg);
                        broadcastToPeers(announce_msg);
                    }
                    else {
                        //log("Not adding already existing graph element for channel "+new_channel_id);
                    }

                }
                // TODO: 4 times per day, per channel (antonopoulos)
                case CHANNEL_UPDATE -> {
                    var message = (P2PMsgChannelUpdate) msg;
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
                        log("Accepting graph update for "+channel_id+" (origin:"+updater_id+")");
                        getChannelGraph().updateChannel(channel_id,updater_id,message.getUpdatedPolicy());
                        var next = message.getNext();
                        broadcastToPeers(next);
                    }
                    else {
                        log("Channel not announced yet! Discarding update/forwarding for "+channel_id+" (origin:"+updater_id+")");
                    }
                }
            }
        }
    }

    /**
     * All the services and actions that are periodically performed by the node
     * This includes only the p2p, gossip network, not the LN protocol actions like channel open, routing etc..
     */
    public void runP2PServices() {
        synchronized (this) {
            this.p2pIsRunning = true;
        }
        p2pProcessGossip();
    }

    private synchronized void updateOnChainBalance(int new_balance) {
        this.onchainBalance = new_balance;
    }

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
     * Move sat from local side to the other side of the channel, update balance accordingly
     * @param channel_id
     * @param amount amount to be moved in sats
     * @return true if the channel update is successful
     */
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

        if (new_node1_balance > 0 || new_node2_balance > 0) {
            success = target_channel.newCommitment(new_node1_balance,new_node2_balance);
        }
        else {
            log("Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getNode2PubKey());
            success = false;
        }
        return success;
    }

    /**
     *
     * @param channel_id channel to check for being its initiator
     * @return
     */
    public boolean isInitiatorOf(String channel_id) {
        return channels.get(channel_id).getInitiator().equals(this.getPubKey());
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
            s.writeObject(channel_graph);

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
            p2PMessageQueue = (Queue<P2PMessage>)s.readObject();

            channel_graph = (ChannelGraph) s.readObject();

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
            peers.put(p,uvm.getUVNodes().get(p));
    }
}
