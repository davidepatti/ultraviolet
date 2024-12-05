import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.concurrent.CountDownLatch;

public class UVTimechain implements Runnable, Serializable, Timechain {

    @Serial
    private static final long serialVersionUID = 1207897L;
    private int current_height;
    private final int blocktime;
    private final Set<CountDownLatch> wait_blocks_latch = new HashSet<>();
    private final Set<Transaction> mempool = new HashSet<>();
    private final List<Block>  blockChain = new LinkedList<>();
    boolean running = false;

    transient private UVManager uvm;


    public UVTimechain(int blocktime, UVManager uvManager) {
        current_height = 0;
        this.blocktime = blocktime;
        this.running = false;
        this.uvm = uvManager;
    }

    public void setUVM(UVManager uvm) {
        this.uvm = uvm;
    }

    private void log(String s) {
        uvm.log("*TIMECHAIN*"+s);
    }

    private synchronized void tictocNextBlock() {
       current_height++;
       var newBlock = new Block(current_height,new ArrayList<>(mempool));
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

    @Override
    public synchronized Optional<ChainLocation> getTxLocation(Transaction tx) {

        for (Block block: blockChain) {
            int index = block.txs().indexOf(tx);
            if (index!=-1) return Optional.of(new ChainLocation(block.height(),index));
        }

        return Optional.empty();
    }

    @Override
    public int getCurrentBlockHeight() {
        return current_height;
    }

    public int getBlockToMillisecTimeDelay(int n_blocks) {
        return blocktime*n_blocks;
    }

    public synchronized int getCurrentLatches() {
        return wait_blocks_latch.size();
    }

    @Override
    public synchronized void addToMempool(Transaction tx) {
        mempool.add(tx);
    }

    @Override
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
