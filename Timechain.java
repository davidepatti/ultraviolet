import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

public class Timechain implements Runnable{

    private int current_block;
    private final int blocktime;
    private final Set<CountDownLatch> timers = new HashSet<>();

    private synchronized void tictocNextBlock() {
       current_block++;
    }

    public synchronized int getCurrent_block() {
        return current_block;
    }

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

    @Override
    public void run() {
        System.out.println("Timechain started!");

        // TODO: method to stop timechain
        while (true) {
            try {
                Thread.sleep(blocktime);
                synchronized (timers) {
                    for (CountDownLatch t:timers ) {
                        t.countDown();
                    }
                    for (CountDownLatch t:timers ) {
                        if (t.getCount()==0) timers.remove(t);
                    }
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
