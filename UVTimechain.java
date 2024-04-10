import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class UVTimechain implements Runnable, Serializable  {

    @Serial
    private static final long serialVersionUID = 1207897L;
    private int current_block;
    private final int blocktime;
    private final Set<CountDownLatch> timers = new HashSet<>();
    private final Set<Transaction> mempool = new HashSet<>();
    private final List<Block>  blockChain = new ArrayList<>();
    boolean running = false;

    transient private UVNetworkManager uvm;


    enum TxType {FUNDING_TX, COOPERATIVE_CLOSE,FORCE_CLOSE}
    public record Transaction(String txId, TxType type, int amount, String node1_pub, String node2_pub) implements Serializable{
        @Override
        public String toString() {
            return "Tx{0x"+ Kit.shortString(txId) +","+ type + ", amt:"+amount+", node1:" + node1_pub + ", node2:" + node2_pub + '}';
        }
    }

    record Block(int height, List<Transaction> txs) implements Serializable {}
    public record ChainLocation(int height, int tx_index) {}


    public UVTimechain(int blocktime, UVNetworkManager uvNetworkManager) {
        current_block = 0;
        this.blocktime = blocktime;
        this.running = false;
        this.uvm = uvNetworkManager;
    }

    public void setUVM(UVNetworkManager uvm) {
        this.uvm = uvm;
    }

    private void log(String s) {
        uvm.log("*TIMECHAIN*"+s);
    }

    private synchronized void tictocNextBlock() {
       current_block++;
       var newBlock = new Block(current_block,new ArrayList<>(mempool));
       blockChain.add(newBlock);

       if (!newBlock.txs().isEmpty()) {
           StringBuilder sb = new StringBuilder("New Block Found "+newBlock.height()+", Transactions >>> ");
           for (Transaction t: newBlock.txs()) {
               sb.append(t.toString());
           }
           sb.append(" <<<");
           log(sb.toString());
       }
       mempool.clear();
    }

    public synchronized Optional<ChainLocation> getTxLocation(Transaction tx) {

        for (Block block: blockChain) {
            int index = block.txs().indexOf(tx);
            if (index!=-1) return Optional.of(new ChainLocation(block.height(),index));
        }

        return Optional.empty();
    }

    public int getCurrentBlock() {
        return current_block;
    }

    public int getBlockToMillisecTimeDelay(int n_blocks) {
        return blocktime*n_blocks;
    }




    public synchronized int getCurrentLatches() {
        return timers.size();
    }

    public synchronized void broadcastTx( Transaction tx) {
        mempool.add(tx);
    }

    public CountDownLatch getTimechainLatch(int blocks) {
        var new_latch = new CountDownLatch(blocks);
        synchronized (timers) {
            timers.add(new_latch);
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
