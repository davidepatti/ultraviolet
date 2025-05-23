import java.io.Serializable;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;

public interface Timechain {
    enum TxType {FUNDING_TX, COOPERATIVE_CLOSE, FORCE_CLOSE}
    record Transaction(String txId, TxType type, int amount, String node1_pub, String node2_pub) implements Serializable {
        @Override
        public String toString() {
            return "Tx{0x" + CryptoKit.shortString(txId) + "," + type + ", amt:" + amount + ", node1:" + node1_pub + ", node2:" + node2_pub + '}';
        }
    }
    record Block(int height, List<Transaction> txs) implements Serializable { }
    record ChainLocation(int height, int tx_index) { }

    Optional<ChainLocation> getTxLocation(Transaction tx);
    int getCurrentBlockHeight();
    void addToMempool(Transaction tx);
    CountDownLatch getWaitBlocksLatch(int blocks);


}
