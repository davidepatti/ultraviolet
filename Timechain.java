import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class Timechain implements Runnable, Serializable  {

    private int current_block;
    private final int blocktime;
    private final Set<CountDownLatch> timers = new HashSet<>();

    private synchronized void tictocNextBlock() {
       current_block++;
    }

    public synchronized int getCurrent_block() {
        return current_block;
    }

    public int getTimeDelay(int n_blocks) {
        return blocktime*n_blocks;
    }

    boolean running = false;

    public Timechain(int blocktime) {
        current_block = 0;
        this.blocktime = blocktime;
    }

    public CountDownLatch getTimechainLatch(int blocks) {
        var new_latch = new CountDownLatch(blocks);
        synchronized (timers) {
            timers.add(new_latch);
        }
        return new_latch;
    }

    public synchronized void stop() {
        this.running = false;
    }

    public synchronized void start() {
        this.running = true;
    }

    public synchronized boolean isRunning() {

        return this.running;
    }

    @Override
    public void run() {
        start();
        System.out.println("Timechain started!");

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
