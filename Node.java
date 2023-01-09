import java.util.HashMap;
import java.util.concurrent.ThreadLocalRandom;

public class Node implements Runnable, Comparable<Node> {

    // internal values are in sat
    private final int Msat = (int)1e6;
    private NodeBehavior behavior;
    private final HashMap<String, Channel> channels = new HashMap<>();
    private final String pubkey;
    // abstracted: indeed alias can be changed
    private final String alias;
    private int onchain_balance;
    private int initiated_channels = 0;
    private final UVManager uvm;
    private p2pNode p2p_node;
    // TODO: the concept of peers and and channel nodes should not coincide

    Log log;
    private boolean bootstrap_completed = false;

    /**
     * Create a lightning node instance attaching it to some Ultraviolet Manager
     * @param uvm an instance of a Ultraviolet Manager to attach
     * @param pubkey the public key to be used as node id
     * @param alias an alias
     * @param onchain_balance initial onchain balance
     */
    public Node(UVManager uvm, String pubkey, String alias, int onchain_balance) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        // change lamba function here to log to a different target
        this.log = s -> UVManager.log.print(this.getPubkey()+":"+s);
        setP2p_node(new p2pNode(this));
    }

    /**
     *
     * @return returs the hashmap representing the current list of channels for the node
     */
    public synchronized HashMap<String, Channel> getChannels() {
        return this.channels;
    }

    /**
     *
     * @return the hashmap of the current list of peers
     */
    public HashMap<String,Node> getPeers() {
        return this.getP2p_node().getPeers();
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
    public String getPubkey() {
        return pubkey;
    }

    /**
     *
     * @param behavior defines some operational policies,e.g., how many channel try to open, of which size etc
     */
    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
    }

    /**
     * This is the main method running while the node is considered active on the network
     */
    @Override
    public void run() {

        log.print("Starting node "+this.pubkey+" on thread "+Thread.currentThread().getName()+" Onchain funding: "+getOnChainBalance());
        // UV notes: a p2p node thread is also started, managing all the events related to the peer to peer messaging network
        new Thread(getP2p_node(),"t_p2p_"+this.getPubkey()).start();

        // UV notes: in the initial phase, the node will always try to reach some minimum number of channels, as defined by the behavior
        while (behavior.getBoostrapChannels() > initiated_channels) {

            log.print(" Searching for a peer to open a channel");

            if (getOnChainBalance() < behavior.getMin_channel_size()) {
                log.print("No more onchain balance for opening further channels of min size "+behavior.getMin_channel_size());
                break;
            }

            var peer_node = uvm.getRandomNode();
            var peer_pubkey = peer_node.getPubkey();

            // TODO: add more complex requirements for peers
            if (peer_pubkey.equals(this.getPubkey())) continue;

            if (this.hasChannelWith(peer_pubkey)) {
                continue;
            }
            if (openChannel(peer_node)) {
                log.print("Successufull opened channel to "+peer_node.getPubkey()+ "new balance (onchain/lightning):"+onchain_balance+ " "+getLightningBalance());
                initiated_channels++;
            }
            else log.print("Failed opening channel to "+peer_node.getPubkey());
        } // while
        bootstrap_completed = true;
        log.print("Bootstrap completed");
    }

    /**
     *
     * @param node_id
     * @return
     */
    public boolean hasChannelWith(String node_id) {
        for (Channel c:this.getChannels().values()) {
            boolean initiated_with_peer = c.getPeer_public_key().equals(node_id);
            boolean accepted_from_peer = c.getInitiator_public_key().equals(node_id);

            if (initiated_with_peer || accepted_from_peer) {
                log.print("Channel already present with peer "+node_id);
                return true;
            }
        }
        return false;
    }
    /**
     * Called by the channel initiator on a target node to propose a channel opening
     * @param new_channel  the channel proposal
     * @return true if accepted
     */
    public synchronized boolean acceptChannel(Channel new_channel) {
        if (this.hasChannelWith(new_channel.getInitiator_public_key())) {
            return false;
        }
        log.print("Accepting channel from "+new_channel.getInitiator_public_key());

        this.channels.put(new_channel.getChannel_id(),new_channel);
        this.getP2p_node().addPeer(new_channel.getInitiator_node());
        this.getP2p_node().channel_graph.addChannel(new_channel);
        return true;
    }

    /**
     * Open a channel with a peer node, with the features defined by the node behavior and configuration
     * @param peer_node the target node partner to open the channel
     * @return true if the channel has been successful opened
     */
    public boolean openChannel(Node peer_node) {


        /* UV NOTE: is not computed with the block number + tx index + output index.
           There is no need to locate the funding multisig tx on the chain, nor to validate signatures etc
           But we still want some id that locate the block height and gives hint about the signers pubkeys
         */
        var channel_id = "CH_"+ uvm.getTimechain().getCurrent_block()+"_"+this.getPubkey()+"->"+peer_node.getPubkey();

        int max = (int) (behavior.getMax_channel_size()/Msat);
        int min = (int) (behavior.getMin_channel_size()/Msat);
        var channel_size = Msat*ThreadLocalRandom.current().nextInt(min,max+1);

        var channel_proposal = new Channel(this,peer_node,channel_size,0,channel_id,0,10,0,10,50);

        // onchain balance is changed only from initiator, so no problems of sync
        if (channel_size>getOnChainBalance()) {
            log.print("Insufficient liquidity for "+channel_size+" channel opening");
            return false;
        }

        if (!peer_node.acceptChannel(channel_proposal)) {
            log.print("channel proposal to "+peer_node.getPubkey()+" not accepted");
            return false;
        }

        // Updates on channel status and balances should be in sync with other accesses (e.g. accept())
        synchronized (this) {
            this.channels.put(channel_id,channel_proposal);
            onchain_balance = onchain_balance-channel_proposal.getInitiator_balance();
            this.getP2p_node().addPeer(peer_node);
            this.getP2p_node().announceChannel(channel_proposal);
        }

        return true;
    }


    /**
     *
     * @return a random node channel
     */
    public synchronized Channel getRandomChannel() {
        var some_channel_id = channels.keySet().toArray()[ThreadLocalRandom.current().nextInt(channels.size())];
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
            int new_initiator_balance =target_channel.getInitiator_balance()-amount;
            int new_peer_balance =target_channel.getPeer_balance()+amount;

            if(new_initiator_balance>0) {
               success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log.print("Insufficient funds in channel "+target_channel.getChannel_id()+" : cannot push  "+amount+ " sats to "+target_channel.getPeer_public_key());
                success = false;
            }
        }
        else { // this node is not the initiator
            int new_initiator_balance =target_channel.getInitiator_balance()+amount;
            int new_peer_balance =target_channel.getPeer_balance()-amount;

            if(new_peer_balance>0) {
                success = target_channel.newCommitment(new_initiator_balance,new_peer_balance);
            }
            else {
                log.print("Insufficient funds in channel " + target_channel.getChannel_id() + " : cannot push  " + amount + " sats to " + target_channel.getInitiator_public_key());
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
        return channels.get(channel_id).getInitiator_public_key().equals(this.getPubkey());
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
    public synchronized int getLightningBalance() {
        int balance = 0;
        for (Channel c:channels.values()) {
            if (this.isInitiatorOf(c.getChannel_id())) balance+=c.getInitiator_balance();
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

    @Override
    public int compareTo(Node node) {
        return this.getPubkey().compareTo(node.getPubkey());
    }

    public p2pNode getP2p_node() {
        return p2p_node;
    }

    public void setP2p_node(p2pNode p2p_node) {
        this.p2p_node = p2p_node;
    }
}
