public interface UVNetwork {
    public UVNode getUVNode(String pubkey);
    public void sendToPeer(P2PNode peer, P2PMessage msg);
    public void broadcastTx( UVTimechain.Transaction tx);

    }
