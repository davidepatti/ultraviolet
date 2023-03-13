import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    void payInvoice(LNInvoice invoice);
    ArrayList<LNChannel> getLNChannelList();

    LNChannel getRandomChannel();
}
