import java.util.HashSet;

public class Node implements Runnable{

    private NodeBehavior behavior;
    private HashSet<Channel> channels = new HashSet<>();
    private final String pubkey;
    // abstracted: indeed alias can be changed
    private final String alias;
    private int onchain_balance;
    private int lightning_balance;

    public String getPubkey() {
        return pubkey;
    }

    public String getAlias() {
        return alias;
    }

    public Node(String pubkey, String alias, int onchain_balance, int lightning_balance) {
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        this.lightning_balance = lightning_balance;
        setBehavior(new NodeBehavior());
    }

    public void setBehavior(NodeBehavior behavior) {
        this.behavior = behavior;
    }

    @Override
    public void run() {

        System.out.println(" Bootstrapping node "+this.pubkey);

        int peers = 0;

        while (this.behavior.getBoostrap_nodes() > peers) {

            var peer_node = UVMananager.findPeer(this);
            var channel_proposal = new Channel(this.getPubkey(),10000,123,1);
            if (peer_node.openChannel(channel_proposal)) {
                System.out.println("Successufull opened channel from "+this.getPubkey()+ " to "+peer_node.getPubkey());
                peers++;
            }
            else
                System.out.println("Failed opening node...");

        }
    }

    public boolean openChannel(Channel ch) {
        System.out.println("Opening channel with "+ch.getPeer_public_key());
        this.channels.add(ch);
        System.out.println("Ok channel opened:");
        System.out.println(ch);
        return true;
    }
}
