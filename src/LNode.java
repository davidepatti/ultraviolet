/*
This interface exposes all the methods that be related to the external services offered by a LN node to the other nodes/actor of
the network.
 */
import java.util.ArrayList;

public interface LNode {
    String getPubKey();
    String getAlias();
    LNInvoice generateInvoice(int amount,String msg);
    ArrayList<LNChannel> getLNChannelList();
    int getNodeCapacity();
}
