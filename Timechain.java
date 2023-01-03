public class Timechain implements Runnable{

    private int current_block;
    private final int blocktime;

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

    @Override
    public void run() {
        System.out.println("Timechain started!");

        // TODO: method to stop timechain
        while (true) {
            try {
                Thread.sleep(blocktime);
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
