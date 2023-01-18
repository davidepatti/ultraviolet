import java.util.ArrayList;
import java.util.concurrent.*;

public class UVNode implements Runnable, LNode,P2PNode {

    // internal values are in sat
    private final int Msat = (int)1e6;
    private NodeBehavior behavior;
    private final String pubkey;
    private final String alias;
    private int onchain_balance;
    private int initiated_channels = 0;
    private final UVManager uvm;
    private final ConcurrentHashMap<String, UVChannel> channels = new ConcurrentHashMap<>();
    // p2p
    private final ChannelGraph channel_graph = new ChannelGraph();
    private final ConcurrentHashMap<String, P2PNode> peers = new ConcurrentHashMap<>();

    final Log log;
    private boolean bootstrap_completed = false;

    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     * @param uvm an instance of a Ultraviolet Manager to attach
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param onchain_balance initial onchain balance
     */
    public UVNode(UVManager uvm, String pubkey, String alias, int onchain_balance) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        // change lamba function here to log to a different target
        this.log = s -> UVManager.log.print(this.getPubKey()+":"+s);
        channel_graph.addNode(this);
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
        return onchain_balance;
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
     * @param behavior defines some operational policies,e.g., how many channel try to open, of which size etc
     */
    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
    }

    public void bootstrapNode() {
        var warmup = ConfigManager.bootstrap_warmup;

        if (warmup!=0) {
            var ready_to_go = uvm.getTimechain().getTimechainLatch(ThreadLocalRandom.current().nextInt(1,warmup));
            log.print(" waiting "+ready_to_go.getCount()+" blocks before bootstrap...");
            try {
                ready_to_go.await();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        log.print("Starting bootstrap!");

        while (behavior.getBoostrapChannels() > initiated_channels) {


            if (getOnChainBalance() <= behavior.getMin_channel_size()) {
                log.print("No more onchain balance for opening further channels of min size "+behavior.getMin_channel_size());
                break;
            }

            var peer_node = uvm.getRandomNode();
            var peer_pubkey = peer_node.getPubKey();
            if (ConfigManager.verbose)
                log.print("Trying to open a channel with "+peer_pubkey);

            // TODO: add more complex requirements for peers
            if (peer_pubkey.equals(this.getPubKey())) continue;

            if (this.hasChannelWith(peer_pubkey)) {
                continue;
            }

            int max = Math.min(behavior.getMax_channel_size(),onchain_balance);
            int min = behavior.getMin_channel_size();
            var channel_size = ThreadLocalRandom.current().nextInt(min,max+1);
            channel_size = (channel_size/100000)*100000;


            if (openChannel(peer_node,channel_size)) {
                log.print("Successufull opened channel to "+peer_node.getPubKey());
                //+ " balance onchain:"+onchain_balance+ " ln:"+getLightningBalance());
                initiated_channels++;
            }
            else log.print("Failed opening channel to "+peer_node.getPubKey());
        } // while
        bootstrap_completed = true;
        uvm.bootstrap_latch.countDown();
        log.print("Bootstrap completed");

    }

    /**
     * This is the main method running while the node is considered active on the network
     */
    @Override
    public void run() {

        log.print("Starting node "+this.pubkey+" on thread "+Thread.currentThread().getName()+" Onchain funding: "+getOnChainBalance());
        // UV notes: a p2p node thread is also started, managing all the events related to the peer to peer messaging network

        // UV notes: in the initial phase, the node will always try to reach some minimum number of channels, as defined by the behavior
        //bootstrapNode();
    }

    /**
     *
     * @param node_id
     * @return
     */
    // TODO: here
    public boolean hasChannelWith(String node_id) {
        if (ConfigManager.verbose)
            log.print(">>> Checking if node has already channel with "+node_id);

        for (UVChannel c:this.channels.values()) {
            boolean initiated_with_peer = c.getPeerPubKey().equals(node_id);
            boolean accepted_from_peer = c.getInitiatorPubKey().equals(node_id);

            if (initiated_with_peer || accepted_from_peer) {
                if (ConfigManager.verbose)
                    log.print("<<< Channel already present with peer "+node_id);
                return true;
            }
        }
        if (ConfigManager.verbose)
            log.print("<<< NO Channel already present with peer "+node_id);
        return false;
    }

    /**
     * Open a channel with a peer node, with the features defined by the node behavior and configuration
     * @param peer_UV_node the target node partner to open the channel
     * @return true if the channel has been successful opened
     */
    public boolean openChannel(UVNode peer_UV_node, int channel_size) {
        log.print(">>> Start Opening "+channel_size+ " sats channel to "+ peer_UV_node.getPubKey());

        /* UV NOTE: is not computed with the block number + tx index + output index.
           There is no need to locate the funding multisig tx on the chain, nor to validate signatures etc
           But we still want some id that locate the block height and gives hint about the signers pubkeys
         */
        var channel_id = "CH_"+ uvm.getTimechain().getCurrent_block()+"_"+this.getPubKey()+"->"+ peer_UV_node.getPubKey();
        var channel_proposal = new UVChannel(this, peer_UV_node,channel_size,0,channel_id,0,10,0,10,50);

        // onchain balance is changed only from initiator, so no problems of sync
        if (channel_size>getOnChainBalance()) {
            log.print("<<< Insufficient liquidity for "+channel_size+" channel opening");
            return false;
        }

        // notice: nothing forbids opening channels with each other reciprocally
        if (!peer_UV_node.acceptChannel(channel_proposal)) {
            log.print("<<< Channel proposal to "+ peer_UV_node.getPubKey()+" not accepted");
            return false;
        }

        log.print(">>> Channel to "+ peer_UV_node.getPubKey()+" accepted, updating node status...");
        // Updates on channel status and balances should be in sync with other accesses (e.g. accept())
        this.addPeer(peer_UV_node);
        this.channels.put(channel_id,channel_proposal);
        channel_graph.addChannel(channel_proposal);
        updateOnChainBalance(getOnChainBalance()-channel_proposal.getInitiatorBalance());

        var message = new MsgChannelAnnouncement(channel_proposal);

        broadcastAnnounceChannel(message);
        log.print("<<< End opening channel with "+ peer_UV_node.getPubKey());

        return true;
    }
    /**
     * Called by the channel initiator on a target node to propose a channel opening
     * synchronized to accept one channel per time to avoid inconsistency
     * @param new_UV_channel  the channel proposal
     * @return true if accepted
     */
    public synchronized boolean acceptChannel(UVChannel new_UV_channel) {
        log.print(">>> Start Accepting channel from "+ new_UV_channel.getInitiatorPubKey());
        if (this.hasChannelWith(new_UV_channel.getInitiatorPubKey())) {
            return false;
        }

        this.channels.put(new_UV_channel.getId(), new_UV_channel);
        this.addPeer(new_UV_channel.getInitiator());
        channel_graph.addChannel(new_UV_channel);

        log.print("Adding and Announcing channel to others: " + new_UV_channel.getId());

        var message = new MsgChannelAnnouncement(new_UV_channel);

        broadcastAnnounceChannel(message);

        log.print("<<< End Accepting channel from "+ new_UV_channel.getInitiatorPubKey());
        return true;
    }

    /**
     *
     * @param msg
     */
    public void broadcastAnnounceChannel(MsgChannelAnnouncement msg) {
        var peer_list = new ArrayList<P2PNode>(peers.values());
        // not including itself
        peer_list.removeIf(x->x.getPubKey().equals(this.getPubKey()));

        for (P2PNode peer: peer_list) {
            peer.receiveAnnounceChannel(this.getPubKey(),msg);
        }
    }

    /**
     *
     * @param from_peer
     * @param msg
     */
    public void receiveAnnounceChannel(String from_peer, MsgChannelAnnouncement msg) {
        //log.print("Broadcast request for channel "+ch.getChannelId()+ " from peer:"+from_peer);
        boolean never_seen = false;
        if (!channel_graph.hasChannel(msg.channel)) {
            never_seen = true;
            this.channel_graph.addChannel(msg.channel);
        }
        if (never_seen && msg.forwardings< ConfigManager.max_gossip_hops) {
            var new_message = msg.getNext();

            if (ConfigManager.verbose)
                log.print("Broadcasting never seen message: "+new_message);

                broadcastAnnounceChannel(new_message);
        }
    }

    private synchronized void updateOnChainBalance(int new_balance) {
        this.onchain_balance = new_balance;
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
            int new_peer_balance =target_channel.getPeer_balance()+amount;

            if(new_initiator_balance>0) {
               success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log.print("Insufficient funds in channel "+target_channel.getId()+" : cannot push  "+amount+ " sats to "+target_channel.getPeerPubKey());
                success = false;
            }
        }
        else { // this node is not the initiator
            int new_initiator_balance =target_channel.getInitiatorBalance()+amount;
            int new_peer_balance =target_channel.getPeer_balance()-amount;

            if(new_peer_balance>0) {
                success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log.print("Insufficient funds in channel " + target_channel.getId() + " : cannot push  " + amount + " sats to " + target_channel.getInitiatorPubKey());
                log.print("local funds:" + getLightningBalance());
                success = false;
            }
        }

        if (success)
            log.print("Pushed "+amount+ " sats towards channel "+channel_id);
        return success;
    }

    /**
     *
     * @param channel_id channel to check for being its initiator
     * @return
     */
    public boolean isInitiatorOf(String channel_id) {
        return channels.get(channel_id).getInitiatorPubKey().equals(this.getPubKey());
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
                balance+=c.getPeer_balance();
        }
        return balance;
    }

    @Override
    public String toString() {
        return "Node{" +
                "pubkey='" + pubkey + '\'' +
                ", alias='" + alias + '\'' +
                ", nchannels=" + channels.size() +
                ", onchain_balance=" + onchain_balance +
                ", lightning_balance=" + getLightningBalance() +
                ", bootstrapped = "+isBootstrap_completed() +
                '}';
    }

}
