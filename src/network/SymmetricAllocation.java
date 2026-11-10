package network;

/** Exact raw-balance allocation, called only inside the network's exclusive settled boundary. */
public final class SymmetricAllocation {
    private SymmetricAllocation() {}

    public static void preflight(UVChannel channel) {
        synchronized (channel) {
            String a = channel.getNode1PubKey(), b = channel.getNode2PubKey();
            int capacity = channel.getCapacity();
            if (a.compareTo(b) >= 0 || capacity <= 0 || channel.getBalance(a) < 0
                    || channel.getBalance(b) < 0 || Math.addExact((long) channel.getBalance(a), channel.getBalance(b)) != capacity
                    || channel.getPending(a) != 0 || channel.getPending(b) != 0
                    || channel.getLastCommitNumber() == Integer.MAX_VALUE) {
                throw new IllegalStateException("Invalid exact-allocation preflight: " + channel.getChannelId());
            }
        }
    }

    public static long apply(UVChannel channel) {
        synchronized (channel) {
            preflight(channel);
            String a = channel.getNode1PubKey(), b = channel.getNode2PubKey();
            int left = channel.getCapacity() / 2;
            int right = Math.subtractExact(channel.getCapacity(), left);
            long moved = Math.abs(Math.subtractExact((long) channel.getBalance(a), left));
            if (moved != 0) {
                int next = Math.incrementExact(channel.getLastCommitNumber());
                channel.newCommitment(left, right);
                if (channel.getLastCommitNumber() != next) throw new IllegalStateException("Rejected exact-allocation commitment");
            }
            if (channel.getBalance(a) != left || channel.getBalance(b) != right) {
                throw new IllegalStateException("Rejected exact-allocation balances");
            }
            return moved;
        }
    }
}
