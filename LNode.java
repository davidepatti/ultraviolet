import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    @SuppressWarnings("SameReturnValue")
    boolean routeInvoice(LNInvoice invoice, LNode destination);
    ArrayList<LNChannel> getLNChannelList();

    LNChannel getRandomChannel();
}
