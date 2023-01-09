import java.util.HashMap;

public class p2pNode implements Runnable{
    ChannelGraph channel_graph = new ChannelGraph();
    private final HashMap<String,Node> peers = new HashMap<>();

    Node node;
    Log log;

    public p2pNode(Node n) {
        this.node = n;
        log = s -> System.out.println(Thread.currentThread().getName()+s);
    }

    public void addPeer(Node node) {
        this.peers.put(node.getPubkey(),node);
        channel_graph.addNode(node.getPubkey());
    }

    public void announceChannel(Channel channel) {
        log.print("Announcing channel "+channel.getChannel_id());
        channel_graph.addChannel(channel);
    }


    public synchronized HashMap<String,Node> getPeers() {
        return peers;
    }

    @Override
    public void run() {
        log.print("Starting thread");
    }
}
