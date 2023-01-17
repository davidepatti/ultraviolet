import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    ArrayList<LNChannel> getLNChannelList();
}
