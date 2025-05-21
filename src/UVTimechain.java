import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.Comparator;

public class UVTimechain implements Runnable, Serializable {

    @Serial
    private static final long serialVersionUID = 1207897L;
    private static final int BLOCK_WEIGHT_LIMIT = 4000000;
    // Defines fee-per-byte bands (inclusive upper bounds) for congestion tracking: 0-5, 5-15, 15-35, 35-100, 100-200, 200-500, 500-1000, 1000-2000
    private static final int[] FEE_BYTE_BANDS = {5, 15, 35, 100, 200, 500, 1000, 2000};
    private int current_height;
    private final int blocktime;
    private final Set<CountDownLatch> wait_blocks_latch = new HashSet<>();
    private final PriorityQueue<UVTransaction> mempoolQueue = new PriorityQueue<>(Comparator.comparingDouble(UVTransaction::getFeesPerByte).reversed());
    private final List<Block>  blockChain = new ArrayList<>();
    boolean running = false;

    // Maps each fee band (upper bound) to the cumulative bytes of pending transactions within that band
    private final NavigableMap<Integer, Long> congestionLevelsByFeeBand = new TreeMap<>();

    transient private UVNetwork uvm;

    public static final record Block(int height, List<UVTransaction> txs) implements Serializable { }
    public static final record ChainLocation(int height, int tx_index) { }

    public UVTimechain(int blocktime, UVNetwork uvNetwork) {
        current_height = 0;
        this.blocktime = blocktime;
        this.running = false;
        this.uvm = uvNetwork;

        // Initialize congestion levels map with zero bytes for each fee band
        for (int threshold : FEE_BYTE_BANDS) {
            congestionLevelsByFeeBand.put(threshold, 0L);
        }
    }

    public void setUVM(UVNetwork uvm) {
        this.uvm = uvm;
    }

    /* mempool management*/

    public synchronized void addToMempool(UVTransaction tx) {
        mempoolQueue.add(tx);
        var band = getTxFeeBand(tx);
        long currentBytes = congestionLevelsByFeeBand.get(band);
        congestionLevelsByFeeBand.put(band, currentBytes + tx.getSize());
    }

    public synchronized void injectMempoolBlob(int feePerByte, int bytes) {
        var blob_tx = UVTransaction.createTx("BLOB", UVTransaction.Type.EXTERNAL_BLOB,-1,"B1","B2",feePerByte,bytes);
        mempoolQueue.add(blob_tx);
    }

    int getTxFeeBand(UVTransaction tx ) {
        int txThreshold = -1;
        for (int threshold : FEE_BYTE_BANDS) {
            if (tx.getFeesPerByte() <= threshold) {
                txThreshold = threshold;
                break;
            }
        }
        if (txThreshold==-1)
            throw new IllegalArgumentException("Invalid fee rate, no corresponding band: " + tx.getFeesPerByte());
        return txThreshold;
    }

    List<UVTransaction> selectTransactionsForBlock() {
        List<UVTransaction> blockTxs = new ArrayList<>();
        int currentBlockWeight = 0;
        int currentBandThreshold = -1;

        while (currentBlockWeight < BLOCK_WEIGHT_LIMIT && !mempoolQueue.isEmpty()) {
            UVTransaction tx = mempoolQueue.peek();

            var txFeeBand = getTxFeeBand(tx);
            // Set starting band if first (highest feerate) transaction of mempool
            if (currentBandThreshold < 0) {
                currentBandThreshold = txFeeBand;
            }

            int txSize = tx.getSize();
            long bandBytes = congestionLevelsByFeeBand.get(currentBandThreshold);

            if (currentBlockWeight + txSize <= BLOCK_WEIGHT_LIMIT) {
                // Add transaction and decrement its bytes from the band
                mempoolQueue.poll();
                blockTxs.add(tx);
                currentBlockWeight += txSize;
                congestionLevelsByFeeBand.put(currentBandThreshold, bandBytes - txSize);

            }
            // blob pseudo txs can be partially removed, since they represent multiple txs
            else if (tx.getType().equals(UVTransaction.Type.EXTERNAL_BLOB)){
                int left_bytes = BLOCK_WEIGHT_LIMIT-currentBlockWeight;
                if (!mempoolQueue.remove(tx))
                    throw new IllegalArgumentException("Removing non-existent tx from mempool: " + tx);
                var new_tx = UVTransaction.createTx(tx.getTxId(),tx.getType(),tx.getAmount(),tx.getNode1Pub(),tx.getNode2Pub(),tx.getFeesPerByte(),left_bytes);
                mempoolQueue.add(new_tx);
                break;
            }else {
                break;
            }
        }

        return blockTxs;
    }


    private void log(String s) {
        uvm.log("*TIMECHAIN*"+s);
    }

    private synchronized void tictocNextBlock() {
       current_height++;
       var newBlock = new Block(current_height,selectTransactionsForBlock());
       blockChain.add(newBlock);

       if (!newBlock.txs().isEmpty()) {
           StringBuilder sb = new StringBuilder("New Block Found! "+newBlock.txs().size()+" Transactions >>> ");
           for (UVTransaction t: newBlock.txs()) {
               sb.append("\n").append(t.toString());
           }
           sb.append("\n <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<");
           // Print congestion levels for each fee band
           sb.append("\nCongestion levels per fee band: ");
           for (Map.Entry<Integer, Long> entry : congestionLevelsByFeeBand.entrySet()) {
               sb.append("\n  <= ").append(entry.getKey()).append(" sat/B: ").append(entry.getValue()).append(" bytes");
           }
           log(sb.toString());
       }
    }

    public synchronized Optional<ChainLocation> findTxLocation(UVTransaction tx, int start_block) {
        for (int i = start_block; i < blockChain.size(); i++) {
            Block block = blockChain.get(i);
            int index = block.txs().indexOf(tx);
            if (index != -1) return Optional.of(new ChainLocation(block.height(), index));
        }
        return Optional.empty();
    }

    public int getCurrentBlockHeight() {
        return current_height;
    }

    public int getBlockToMillisecTimeDelay(int n_blocks) {
        return blocktime*n_blocks;
    }

    public synchronized int getCurrentLatches() {
        return wait_blocks_latch.size();
    }


    public CountDownLatch getWaitBlocksLatch(int blocks) {
        var new_latch = new CountDownLatch(blocks);
        synchronized (wait_blocks_latch) {
            wait_blocks_latch.add(new_latch);
        }
        return new_latch;
    }

    public synchronized void setRunning(boolean running_status) {
        // not running --> running, must start the thread
        if (running_status && !isRunning()) {
                Thread timechainThread = new Thread(this, "Timechain");
                this.running = true;
                timechainThread.start();
        }
        else  // just update the status if required
            this.running = running_status;
    }

    public synchronized boolean isRunning() {
        return this.running;
    }

    @Override
    public void run(){
        log("Starting timechain thread...");
        while (isRunning()) {
            try {
                Thread.sleep(blocktime);
                synchronized (wait_blocks_latch) {
                    for (CountDownLatch t: wait_blocks_latch) {
                        t.countDown();
                    }
                    wait_blocks_latch.removeIf(t -> t.getCount() == 0);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            tictocNextBlock();
        }
        log("Exiting timechain thread...");
    }

    @Override
    public String toString() {
        return "Timechain{" +
                "current_height=" + current_height +
                ", blocktime=" + blocktime +
                '}';
    }
}
