public class NodeBehavior {

    private int boostrap_nodes = 1;
    private int min_channel_size = 100000;
    private int max_channel_size = 10000000;

    public int getBoostrap_nodes() {
        return boostrap_nodes;
    }

    public void setBoostrap_nodesi(int boostrap_nodesi) {
        this.boostrap_nodes = boostrap_nodesi;
    }

    public int getMin_channel_size() {
        return min_channel_size;
    }

    public void setMin_channel_size(int min_channel_size) {
        this.min_channel_size = min_channel_size;
    }

    public int getMax_channel_size() {
        return max_channel_size;
    }

    public void setMax_channel_size(int max_channel_size) {
        this.max_channel_size = max_channel_size;
    }
}
