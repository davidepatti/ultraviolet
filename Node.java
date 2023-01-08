import java.util.HashMap;
import java.util.HashSet;
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

    private ChannelGraph channelGraph = new ChannelGraph();
    // TODO: the concept of peers and and channel nodes should not coincide
    private final HashMap<String,Node> peers = new HashMap<>();

    Log log;
    private boolean bootstrap_completed = false;

    public Node(UVManager uvm, String pubkey, String alias, int onchain_balance) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        // change lamba function here to log to a different target
        this.log = s -> UVManager.log.print(this.getPubkey()+":"+s);
    }

    public synchronized HashMap<String, Channel> getChannels() {
        return this.channels;
    }
    public synchronized HashMap<String,Node> getPeers() {
        return this.peers;
    }
    private int getOnChainBalance() {
        return onchain_balance;
    }

    public synchronized int getNumberOfChanneles() {
        return channels.size();
    }
    private void setOnChainBalance(int new_balance) {
        onchain_balance = new_balance;
    }


    public String getPubkey() {
        return pubkey;
    }

    public String getAlias() {
        return alias;
    }


    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
    }

    @Override
    public void run() {

        System.out.println("Starting node "+this.pubkey+" on thread "+Thread.currentThread().getName()+" Onchain: "+getOnChainBalance());

        while (behavior.getBoostrapChannels() > initiated_channels) {

            log.print(" Searching for a peer to open a channel");

            if (getOnChainBalance() < behavior.getMin_channel_size()) {
                log.print("No sufficient onchain balance for opening min channels size "+behavior.getMin_channel_size());
                break;
            }

            var peer_node = uvm.getRandomNode();

            // TODO: add more complex requirements for peers
            if (peer_node.getPubkey().equals(this.getPubkey())) continue;

            boolean already_opened = false;

            synchronized (channels) {
                for (Channel c:channels.values()) {
                    boolean initiated_with_peer = c.getPeer_public_key().equals(peer_node.getPubkey());
                    boolean accepted_from_peer = c.getInitiator_public_key().equals(peer_node.getPubkey());

                    if (initiated_with_peer || accepted_from_peer) {
                        log.print("Channel already present with peer "+peer_node.getPubkey());
                        already_opened = true;
                        break;
                    }
                }
                if (!already_opened) {
                    if (openChannel(peer_node)) {
                        log.print("Successufull opened channel to "+peer_node.getPubkey()+ "new balance (onchain/lightning):"+onchain_balance+ " "+getLightningBalance());
                        initiated_channels++;
                    }
                    else log.print("Failed opening channel to "+peer_node.getPubkey());
                }
            }
        } // while
        bootstrap_completed = true;
        log.print("Bootstrap completed");
    }

    public boolean openChannel(Node peer_node) {

        var channel_id = "CH_"+ uvm.getTimechain().getCurrent_block()+"_"+this.getPubkey()+"->"+peer_node.getPubkey();
        int max = (int) (behavior.getMax_channel_size()/Msat);
        int min = (int) (behavior.getMin_channel_size()/Msat);
        var size = Msat*ThreadLocalRandom.current().nextInt(min,max+1);
        var channel_proposal = new Channel(this,peer_node,size,0,channel_id,0,10,0,10,50);
        if (size>getOnChainBalance()) {
            log.print("Insufficient liquidity for "+size+" channel opening");
            return false;
        }

        if (peer_node.acceptChannel(channel_proposal)) {
            this.channels.put(channel_id,channel_proposal);
            onchain_balance = onchain_balance-channel_proposal.getInitiator_balance();
        }
        else {
            log.print("channel proposal to "+peer_node.getPubkey()+" not accepted");
            return false;
        }

        this.channelGraph.channel_announcement(channel_proposal);
        peers.put(peer_node.getPubkey(),peer_node);
        return true;
    }

    public synchronized Channel getRandomChannel() {
        var some_channel_id = channels.keySet().toArray()[ThreadLocalRandom.current().nextInt(channels.size())];
        return channels.get(some_channel_id);
    }


    public synchronized void pushSats(String channel_id, int amount) {

        var target_channel = this.channels.get(channel_id);

        boolean success = true;

        if (this.isInitiator(channel_id)) {
            int new_initiator_balance =target_channel.getInitiator_balance()-amount;
            int new_peer_balance =target_channel.getPeer_balance()+amount;

            if(new_initiator_balance>0) {
               success = target_channel.updateChannel(new_initiator_balance,new_peer_balance);
            }
            else
                log.print("Insufficient funds in channel "+target_channel.getChannel_id()+" : cannot push  "+amount+ " sats to "+target_channel.getPeer_public_key());
        }
        else { // this node is not the initiator
            int new_initiator_balance =target_channel.getInitiator_balance()+amount;
            int new_peer_balance =target_channel.getPeer_balance()-amount;

            if(new_peer_balance>0) {
                success = target_channel.updateChannel(new_initiator_balance,new_peer_balance);
            }
            else {
                log.print("Insufficient funds in channel " + target_channel.getChannel_id() + " : cannot push  " + amount + " sats to " + target_channel.getInitiator_public_key());
                log.print("local funds:" + getLightningBalance());
            }
        }

        if (success)
            log.print("Pushed "+amount+ " sats towards channel "+channel_id);
    }

    public boolean isInitiator(String channel_id) {
        return channels.get(channel_id).getInitiator_public_key().equals(this.getPubkey());
    }

    //synchronized so that channels structure is updated correctly
    public synchronized boolean acceptChannel(Channel new_channel) {
        log.print("Accepting channel from "+new_channel.getInitiator_public_key());

        this.channels.put(new_channel.getChannel_id(),new_channel);
        this.channelGraph.channel_announcement(new_channel);
        peers.put(new_channel.getInitiator_public_key(), new_channel.getInitiator_node());
        //log.print("Ok channel accepted:"+ch);
        return true;
    }

    public boolean isBootstrap_completed() {
        return bootstrap_completed;
    }

    public synchronized int getLightningBalance() {
        int balance = 0;
        for (Channel c:channels.values()) {
            if (this.isInitiator(c.getChannel_id())) balance+=c.getInitiator_balance();
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
}
