import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class P2PNode implements Runnable{
    private final ChannelGraph channel_graph = new ChannelGraph();
    private final HashMap<String,Node> peers = new HashMap<>();
    private final Node node;
    Log log;

    public P2PNode(Node n) {
        this.node = n;
        channel_graph.addNode(this.node);
        log = s -> System.out.println("p2p ("+this.getId()+"):"+s);
    }

    public String getId() {
        return this.node.getPubkey();
    }

    public ChannelGraph getChannel_graph() {
        return this.channel_graph;
    }

    /**
     * Add a node to the list of peers and update the channel graph
     * @param node
     */
    public synchronized void addPeer(Node node) {
        if (!peers.containsKey(node.getPubkey())) {
            this.peers.put(node.getPubkey(),node);
            channel_graph.addNode(node);
        }
    }

    public synchronized void addChannel(Channel ch) {
        this.channel_graph.addChannel(ch);
    }

    /**
     * Announce to peers a channel opened from local side
     * @param channel
     */
    public void announceChannel(Channel channel) {
            log.print("Adding and Announcing channel to others: " + channel.getChannel_id());
            synchronized (channel_graph) {
                channel_graph.addChannel(channel);
            }

            List<Node> peers_snapshot = null;
            synchronized (peers) {
                peers_snapshot = new ArrayList<>(peers.values());
            }
            for (Node p : peers_snapshot) {
                broadcastAnnounceChannel(p.getP2PNode(), channel);
            }
    }

    private void broadcastAnnounceChannel(P2PNode target_peer, Channel ch) {
        target_peer.receiveAnnounceChannel(this.getId(),ch);
    }

    // synch required for multiple external node calls
    public void receiveAnnounceChannel(String from_peer,Channel ch) {
        log.print("Received Broadcast request for channel "+ch.getChannel_id()+ " from peer:"+from_peer);
        synchronized (channel_graph) {
            this.channel_graph.addChannel(ch);
        }
    }

    public HashMap<String,Node> getPeers() {
        return peers;
    }

    @Override
    public void run() {
        log.print("Starting thread");
    }
}
