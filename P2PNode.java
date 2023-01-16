import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class P2PNode {
    private final ChannelGraph channel_graph = new ChannelGraph();
    private final HashMap<String, UVNode> uvpeers = new HashMap<>();
    private final UVNode uvnode;
    Log log;

    public P2PNode(UVNode owner) {
        this.uvnode = owner;
        channel_graph.addNode(this.uvnode);
        log = s -> uvnode.log.print("[p2p]"+s);
    }

    public String getId() {
        return this.uvnode.getPubKey();
    }

    public ChannelGraph getChannel_graph() {
        return this.channel_graph;
    }

    /**
     * Add a node to the list of peers and update the channel graph
     * @param node
     */
    public synchronized void addPeer(UVNode node) {
        if (!uvpeers.containsKey(node.getPubKey())) {
            this.uvpeers.put(node.getPubKey(),node);
            channel_graph.addNode(node);
        }
    }

    public synchronized void addChannel(UVChannel ch) {
        this.channel_graph.addChannel(ch);
    }

    /**
     * Announce to peers a channel opened from local side
     * @param channel
     */
    public void announceChannel(UVChannel channel) {
            log.print("Adding and Announcing channel to others: " + channel.getChannelId());
            synchronized (channel_graph) {
                channel_graph.addChannel(channel);
            }

            List<UVNode> peers_snapshot = null;
            synchronized (uvpeers) {
                peers_snapshot = new ArrayList<>(uvpeers.values());
            }
            for (UVNode node : peers_snapshot) {
                broadcastAnnounceChannel(node.getP2PNode(), channel);
            }
    }

    private void broadcastAnnounceChannel(P2PNode target_peer, UVChannel ch) {
        target_peer.receiveAnnounceChannel(this.getId(),ch);
    }

    // synch required for multiple external node calls
    public void receiveAnnounceChannel(String from_peer, UVChannel ch) {
        log.print("Broadcast request for channel "+ch.getChannelId()+ " from peer:"+from_peer);
        boolean never_seen = false;
        synchronized (this.channel_graph) {
            if (!channel_graph.hasChannel(ch)) {
                never_seen = true;
                this.channel_graph.addChannel(ch);
            }
        }
        if (never_seen) {
            ArrayList<UVNode> peers_snapshot;
            synchronized (uvpeers) {
                peers_snapshot = new ArrayList<>(uvpeers.values());
            }
            log.print("Broadcasting never seen channel "+ch.getChannelId());
            for (UVNode n: peers_snapshot) {
                broadcastAnnounceChannel(n.getP2PNode(),ch);
            }
        }
    }

    public HashMap<String, UVNode> getUvpeers() {
        return uvpeers;
    }
}
