import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    boolean RouteInvoice(LNInvoice invoice, LNode destination);
    ArrayList<LNChannel> getLNChannelList();
}
