import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    ArrayList<LNChannel> getLNChannelList();
}
