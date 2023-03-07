import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount);
    @SuppressWarnings("SameReturnValue")
    boolean payInvoice(LNInvoice invoice);
    ArrayList<LNChannel> getLNChannelList();

    LNChannel getRandomChannel();
}
