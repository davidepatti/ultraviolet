import java.util.HashSet;
import java.util.concurrent.ThreadLocalRandom;

public class Node implements Runnable, Comparable<Node> {

    // internal values are in sat
    private final int Msat = (int)1e6;
    private NodeBehavior behavior;
    private HashSet<Channel> channels = new HashSet<>();
    private final String pubkey;
    // abstracted: indeed alias can be changed
    private final String alias;
    private int onchain_balance;
    private int lightning_balance;
    private int initiated_channels = 0;
    private UVManager uvm;

    Log log;

    public Node(UVManager uvm, String pubkey, String alias, int onchain_balance, int lightning_balance) {
        this.uvm = uvm;
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        this.lightning_balance = lightning_balance;
        // change lamba function here to log to a different target
        this.log = s -> {
            uvm.log.print(this.getPubkey()+":"+s);
        };
    }

    public synchronized HashSet<Channel> getChannels() {
        return this.channels;
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

            var peer_node = uvm.findPeer(this);
            if (peer_node==null) {
                log.print("failed peer search, retrying later");
                try {
                    Thread.sleep((long) (Math.random()*5000));
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                continue;
            }

            boolean already_opened = false;

            synchronized (channels) {
                for (Channel c:channels) {
                    if (c.getPeer_public_key().compareTo(peer_node.getPubkey())==0) {
                        log.print("Channel already present with peer "+peer_node.getPubkey());
                        already_opened = true;
                        break;
                    }
                }
                if (!already_opened) {
                    if (openChannel(peer_node)) {
                        log.print("Successufull opened channel to "+peer_node.getPubkey()+ "new balance (onchain/lightning):"+onchain_balance+ " "+lightning_balance);
                        initiated_channels++;
                    }
                    else log.print("Failed opening channel to "+peer_node.getPubkey());
                }
            }
        } // while
    }

    public boolean openChannel(Node peer_node) {

        var channel_id = "CH_"+ uvm.getTimechain().getCurrent_block()+"_"+this.getPubkey()+"->"+peer_node.getPubkey();
        int max = (int) (behavior.getMax_channel_size()/Msat);
        int min = (int) (behavior.getMin_channel_size()/Msat);
        var size = Msat*ThreadLocalRandom.current().nextInt(min,max+1);
        var channel_proposal = new Channel(size,0,channel_id,this.getPubkey(), peer_node.getPubkey(),1,0);
        if (size>getOnChainBalance()) {
            log.print("Insufficient liquidity for "+size+" channel opening");
            return false;
        }

        if (peer_node.acceptChannel(channel_proposal)) {
            this.channels.add(channel_proposal);
            onchain_balance = onchain_balance-channel_proposal.getInitiator_balance();
            lightning_balance += channel_proposal.getInitiator_balance();
        }
        else {
            log.print("channel proposal to "+peer_node.getPubkey()+" not accepted");
            return false;
        }
        return true;
    }

    public void updateChannel() {

        var some_channel = (Channel)channels.toArray()[ThreadLocalRandom.current().nextInt(channels.size())];
        log.print("updading channel "+some_channel.getChannel_id());

        some_channel.updateChannel(1234,4321);

    }

    //synchronized so that channels structure is updated correctly
    public synchronized boolean acceptChannel(Channel new_channel) {
        log.print("Accepting channel from "+new_channel.getInitiator_public_key());

        this.channels.add(new_channel);
        //log.print("Ok channel accepted:"+ch);
        return true;
    }

    @Override
    public String toString() {
        return "Node{" +
                "pubkey='" + pubkey + '\'' +
                ", alias='" + alias + '\'' +
                ", nchannels=" + channels.size() +
                ", onchain_balance=" + onchain_balance +
                ", lightning_balance=" + lightning_balance +
                '}';
    }

    @Override
    public int compareTo(Node node) {
        return this.getPubkey().compareTo(node.getPubkey());
    }
}
