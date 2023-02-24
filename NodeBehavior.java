import java.io.Serial;
import java.io.Serializable;

public class NodeBehavior implements Serializable {
    @Serial
    private static final long serialVersionUID = 9579L;
    // TODO: define more profiles here
    public final static int Msat = (int)1e6;
    public final static NodeBehavior MANY_SMALL = new NodeBehavior(100,Msat/10,5*Msat);
    public final static NodeBehavior MANY_BIG = new NodeBehavior(100,5*Msat,10*Msat);
    public final static NodeBehavior MEDIUM_SMALL = new NodeBehavior(30,Msat/10,5*Msat);
    public final static NodeBehavior MEDIUM_BIG = new NodeBehavior(30,5*Msat,10*Msat);
    public final static NodeBehavior FEW_SMALL = new NodeBehavior(10,Msat/10,5*Msat);
    public final static NodeBehavior FEW_BIG = new NodeBehavior(10,5*Msat,10*Msat);

    private int target_channel_number;
    private int min_channel_size;
    private int max_channel_size;

    public int getBoostrapChannels() {
        return target_channel_number;
    }

    public void setBoostrapChannels(int boostrap_channels) {
        this.target_channel_number = boostrap_channels;
    }

    public int getMinChannelSize() {
        return min_channel_size;
    }

    public void setMin_channel_size(int min_channel_size) {
        this.min_channel_size = min_channel_size;
    }

    public int getMaxChannelSize() {
        return max_channel_size;
    }

    public void setMax_channel_size(int max_channel_size) {
        this.max_channel_size = max_channel_size;
    }

    public NodeBehavior(int target_channel_number, int min_channel_size, int max_channel_size) {
        this.target_channel_number = target_channel_number;
        this.min_channel_size = min_channel_size;
        this.max_channel_size = max_channel_size;
    }
}
