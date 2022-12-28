public class NodeBehavior {

    private int boostrap_channels;
    private double min_channel_size;
    private double max_channel_size;

    public int getBoostrapChannels() {
        return boostrap_channels;
    }

    public void setBoostrapChannels(int boostrap_channels) {
        this.boostrap_channels = boostrap_channels;
    }

    public double getMin_channel_size() {
        return min_channel_size;
    }

    public void setMin_channel_size(int min_channel_size) {
        this.min_channel_size = min_channel_size;
    }

    public double getMax_channel_size() {
        return max_channel_size;
    }

    public void setMax_channel_size(int max_channel_size) {
        this.max_channel_size = max_channel_size;
    }

    public NodeBehavior(int boostrap_channels, double min_channel_size, double max_channel_size) {
        this.boostrap_channels = boostrap_channels;
        this.min_channel_size = min_channel_size;
        this.max_channel_size = max_channel_size;
    }
}
