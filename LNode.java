import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public interface LNode {
    String getPubKey();
    String getAlias();
    ArrayList<LNChannel> getLNChannelList();
}
