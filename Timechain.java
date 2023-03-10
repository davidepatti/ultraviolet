import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

public class Timechain implements Runnable, Serializable  {


    private void log(String s) {
       UVNetworkManager.log("[TIMECHAIN]"+s);
    }

    enum TxType {FUNDING_TX, COOPERATIVE_CLOSE,FORCE_CLOSE}
    record Transaction(String txId, TxType type, String node1_pub, String node2_pub) {
        @Override
        public String toString() {
            return "Tx{" + "Id='" + txId + '\'' + ", type=" + type + ", node1_pub='" + node1_pub + '\'' + ", node2_pub='" + node2_pub + '\'' + '}';
        }
    };
    record Block(int height, List<Transaction> txs) {};

    @Serial
    private static final long serialVersionUID = 1207897L;
    private int current_block;
    private final int blocktime;
    private final Set<CountDownLatch> timers = new HashSet<>();
    private final Set<Transaction> mempool = new HashSet<>();
    private final List<Block>  blockChain = new LinkedList<>();

    private synchronized void tictocNextBlock() {
       current_block++;
       var newBlock = new Block(current_block,new ArrayList<>(mempool));
       blockChain.add(newBlock);

       if (mempool.size()>0) {
           log("Confirmed block with mempool txs: ");
           mempool.stream().forEach(s->log(s.toString()));
       }
       mempool.clear();
    }

    public synchronized int getCurrentBlock() {
        return current_block;
    }

    public int getBlockToMillisecTimeDelay(int n_blocks) {
        return blocktime*n_blocks;
    }


    boolean running = false;

    public Timechain(int blocktime) {
        current_block = 0;
        this.blocktime = blocktime;
        this.running = false;
    }

    public synchronized int getCurrentLatches() {
        return timers.size();
    }

    public void broadcastTx( Transaction tx) {
        mempool.add(tx);
    }

    public CountDownLatch getTimechainLatch(int blocks) {
        var new_latch = new CountDownLatch(blocks);
        synchronized (timers) {
            timers.add(new_latch);
        }
        return new_latch;
    }

    public synchronized void setRunning(boolean running) {
        this.running = running;
        log("MEMPOOLSIZE "+mempool.size());
    }

    public synchronized boolean isRunning() {
        return this.running;
    }

    @Override
    public void run(){
        setRunning(true);
        while (isRunning()) {
            try {
                Thread.sleep(blocktime);
                synchronized (timers) {
                    for (CountDownLatch t:timers ) {
                        t.countDown();
                    }
                    timers.removeIf(t -> t.getCount() == 0);
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            tictocNextBlock();
        }
    }

    @Override
    public String toString() {
        return "Timechain{" +
                "current_block=" + current_block +
                ", blocktime=" + blocktime +
                '}';
    }
}
