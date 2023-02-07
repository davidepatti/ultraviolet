import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    boolean routeInvoice(LNInvoice invoice, LNode destination);
    ArrayList<LNChannel> getLNChannelList();
    boolean updateAddHTLC(OnionLayer onionLayer);

    LNChannel getRandomChannel();
}
