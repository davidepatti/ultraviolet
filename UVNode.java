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

    transient private boolean bootstrap_completed = false;
    transient private boolean p2pIsRunning = false;
    transient ArrayList<String> saved_peer_list;

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

    /**
     *
     * @return returs the hashmap representing the current list of channels for the node
     */
    public ConcurrentHashMap<String, UVChannel> getUVChannels() {
        return this.channels;
    }

    public ArrayList<LNChannel> getLNChannelList() {

        return new ArrayList<>(this.getUVChannels().values());
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
        var invoice = new LNInvoice(r,amount,this.getPubKey(), null);
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
        var base_block_height = uvm.getTimechain().getCurrent_block();
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
            var channel = uvm.getChannelFromNodes(to_node,from_node);
            var channel_id = channel.getId();
            hopPayload = new OnionLayer.Payload(channel_id,amount+forwarding_fees,outgoing_cltv_value,null);
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
        var forwarding_channel = getUVChannels().get(forwarding_channel_id);

        if (onionLayer.getPayload().getAmt_to_forward()>getLocalBalance(forwarding_channel)) {
            System.out.println("Not enought liquidity ");
            return false;
        }
        else {
            var channel_peer = getChannelPeer(forwarding_channel_id);
            return channel_peer.updateAddHTLC(onionLayer.getInnerLayer());
        }
    }

    /**
     *
     * @param channel_id
     * @return
     */
    private LNode getChannelPeer(String channel_id) {
        var channel = channels.get(channel_id);
        if (isInitiatorOf(channel_id)) return uvm.getLNode(channel.getInitiatorPubkey());
        else
            return uvm.getLNode(channel.getPeerPubkey());
    }

    /**
     *
     * @param channel
     * @return
     */
    public synchronized int getLocalBalance(UVChannel channel) {
        if (isInitiatorOf(channel.getId())) {
            return channel.getInitiatorBalance();
        }
        else {
           return channel.getPeerBalance();
        }
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
            if (this.myChannelWith(peer_pubkey)!=null) continue;

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
                log("Successufull opened channel to "+peer_node.getPubKey());
                initiated_channels++;
            }
            else log("Failed opening channel to "+peer_node.getPubKey());
        } // while

        bootstrap_completed = true;
        uvm.getBootstrapLatch().countDown();
        log("Bootstrap completed");
    }

    /**
     *
     * @param node_id
     * @return
     */
    // TODO: here
    public String myChannelWith(String node_id) {
        if (ConfigManager.isVerbose())
            log(">>> Checking if node has already channel with "+node_id);

        for (UVChannel c:this.channels.values()) {
            boolean initiated_with_peer = c.getPeerPubkey().equals(node_id);
            boolean accepted_from_peer = c.getInitiatorPubkey().equals(node_id);

            if (initiated_with_peer || accepted_from_peer) {
                if (ConfigManager.isVerbose())
                    log("<<< Channel already present with peer "+node_id);
                return c.getId();
            }
        }
        if (ConfigManager.isVerbose())
            log("<<< NO Channel already present with peer "+node_id);
        return null;
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
    /**
     * Mapped to LN protocol message: open_channel
     * Open a channel with a peer node, with the features defined by the node behavior and configuration
     * Mainly used when bootstrapping: autogenerates channel id, fees, and takes all the necessary p2p actions
     *
     * @param peerPubKey the target node partner to open the channel
     * @return true if the channel has been successful opened
     */
    private boolean openChannel(String peerPubKey, int channel_size) {
        log(">>> Start Opening "+channel_size+ " sats channel to "+ peerPubKey);

        var peer = uvm.getUVNodes().get(peerPubKey);
        var channel_id = uvm.getTimechain().getCurrent_block()+"_"+this.getPubKey()+"_"+ peerPubKey;
        var channel_proposal = new UVChannel(channel_id, this.getPubKey(), peerPubKey,channel_size,0,0);


        // notice: nothing forbids opening channels with each other reciprocally
        if (!peer.acceptChannel(this, channel_proposal)) {
            log("<<< Channel proposal to "+ peerPubKey+" not accepted");
            return false;
        }

        log(">>> Channel to "+ peerPubKey+" accepted, updating node status...");
        // Updates on channel status and balances should be in sync with other accesses (e.g. accept())
        this.addPeer(peer);
        this.channels.put(channel_id,channel_proposal);
        channel_graph.addChannel(channel_proposal);
        updateOnChainBalance(getOnChainBalance()-channel_proposal.getInitiatorBalance());

        // TODO: more granularity
        var timestamp = uvm.getTimechain().getCurrent_block();
        var msg_announcement = new P2PMsgChannelAnnouncement(channel_proposal,timestamp,0);
        broadcastToPeers(msg_announcement);

        var newPolicy = new LNChannel.Policy(20,1000,50);
        channelUpdate(channel_id,newPolicy);

        log("<<< End opening channel with "+ peerPubKey);

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
        log(">>> Start Accepting channel "+ channel.getId());
        if (this.myChannelWith(initiator.getPubKey())!=null) {
            return false;
        }

        this.channels.put(channel.getId(), channel);
        // TODO: this is why UVChannel must contain UVNode partners and not only pubkeys
        this.addPeer(uvm.getUVNodes().get(channel.getInitiatorPubkey()));
        channel_graph.addChannel(channel);

        var timestamp = uvm.getTimechain().getCurrent_block();
        var message = new P2PMsgChannelAnnouncement(channel,timestamp,0);
        broadcastToPeers(message);
        var newPolicy = new LNChannel.Policy(20,1000,200);
        channelUpdate(channel.getId(),newPolicy);

        log("<<< End Accepting channel "+ channel.getId());
        return true;
    }

    /**
     *
     * @param channel_id
     * @param newPolicy
     */
    public void channelUpdate(String channel_id, LNChannel.Policy newPolicy) {

        if (!channels.containsKey(channel_id)) {
            throw new RuntimeException("Not channel Owner!");
        }

        channels.get(channel_id).setPolicy(getPubKey(),newPolicy);
        var timestamp = uvm.getTimechain().getCurrent_block();
        var msg_update = new P2PMsgChannelUpdate(this.getPubKey(),channel_id,timestamp,0,newPolicy);

        broadcastToPeers(msg_update);
    }

    /**
     * Not broadcasted if too old or too many forwardings
     * @param msg
     */
   public void broadcastToPeers(P2PMessage msg) {
       var current_age = uvm.getTimechain().getCurrent_block() -msg.getTimeStamp();
       if (current_age> ConfigManager.getMaxP2PAge()) return;
       if (msg.getForwardings()>= ConfigManager.getMaxP2PHops()) return;

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
            var current_age = uvm.getTimechain().getCurrent_block() -msg.getTimeStamp();
            if (current_age> ConfigManager.getMaxP2PAge()) continue;

            switch (msg.getType()) {
                case CHANNEL_ANNOUNCE -> {
                    var next = (P2PMsgChannelAnnouncement) msg.getNext();
                    var channel = next.getLNChannel();
                    if (!channel_graph.hasChannel(channel)) {
                        this.channel_graph.addChannel(channel);
                        broadcastToPeers(next);
                    }
                }
                // TODO: 4 times per day, per channel (antonopoulos)
                case CHANNEL_UPDATE -> {
                    var message = (P2PMsgChannelUpdate) msg;
                    // skip channel updates of own channels
                    var updater = message.getNode();
                    var channel_id = message.getChannel_id();
                    // sent from my channel partners, update related data
                    var mychannel = this.myChannelWith(updater);
                    if (mychannel!=null && mychannel.equals(channel_id)) {
                        channels.get(channel_id).setPolicy(updater,message.getUpdatedPolicy());
                    }
                    //https://github.com/lightning/bolts/blob/master/07-routing-gossip.md#the-channel_update-message
                    /*
                    The receiving node:
                    if the short_channel_id does NOT match a previous channel_announcement, OR if the channel has been closed in the meantime:
                    MUST ignore channel_updates that do NOT correspond to one of its own channels.
                     */
                    if (getChannelGraph().hasChannel(channel_id)) {
                        //log("Requesting update for "+channel_id+" (origin:"+updater+")");
                        getChannelGraph().updateChannel(channel_id,updater,message.getUpdatedPolicy());
                    }

                    var next = message.getNext();
                    broadcastToPeers(next);
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

        if (this.isInitiatorOf(channel_id)) {
            int new_initiator_balance =target_channel.getInitiatorBalance()-amount;
            int new_peer_balance =target_channel.getPeerBalance()+amount;

            if(new_initiator_balance>0) {
               success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log("Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getPeerPubkey());
                success = false;
            }
        }
        else { // this node is not the initiator
            int new_initiator_balance =target_channel.getInitiatorBalance()+amount;
            int new_peer_balance =target_channel.getPeerBalance()-amount;

            if(new_peer_balance>0) {
                success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log("Insufficient funds in channel " + target_channel.getId() + " : cannot push  " + amount + " sats to " + target_channel.getInitiatorPubkey());
                log("local funds:" + getLightningBalance());
                success = false;
            }
        }

        if (success)
            log("Pushed "+amount+ " sats towards channel "+channel_id);
        return success;
    }

    /**
     *
     * @param channel_id channel to check for being its initiator
     * @return
     */
    public boolean isInitiatorOf(String channel_id) {
        return channels.get(channel_id).getInitiatorPubkey().equals(this.getPubKey());
    }


    /**
     *
     * @return true if the bootstrapping phase is completed
     */
    public boolean isBootstrap_completed() {
        return bootstrap_completed;
    }

    /**
     *
     * @return the sum of all balances on node side
     */
    public int getLightningBalance() {
        int balance = 0;

        for (UVChannel c: channels.values()) {
            if (this.isInitiatorOf(c.getId())) balance+=c.getInitiatorBalance();
            else
                balance+=c.getPeerBalance();
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
        saved_peer_list = new ArrayList<>();

        try {
            s.defaultReadObject();
            int num_channels = s.readInt();
            for (int i=0;i<num_channels;i++) {
                UVChannel c = (UVChannel)s.readObject();
                channels.put(c.getId(),c);
            }
            int num_peers = s.readInt();
            for (int i=0;i<num_peers;i++) {
                saved_peer_list.add((String)s.readObject());
            }

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
        for (String p: saved_peer_list)
            peers.put(p,uvm.getUVNodes().get(p));
    }
}
