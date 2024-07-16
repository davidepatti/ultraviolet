import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

public class LNInvoice implements Serializable {
    // antonop 339
    private final String H;
    private final int amount;
    private final String destination;
    private final String message;
    @SuppressWarnings("FieldCanBeLocal")
    //	The min_final_cltv_expiry_delta is set by the final recipient of the payment
    //  and communicated via invoice to each node in the path
    //  The sender constructs the payment route, they use the min_final_cltv_expiry_delta
    //  to calculate the CLTV expiry for the final hop.
    private final int min_cltv_expiry = 9; // antonop 339

    public LNInvoice(String paymentHash, int amount, String recipient, String message) {
        this.H = paymentHash;
        this.amount = amount;
        this.destination = recipient;
        this.message = message;

    }

    public String getHash() {
       return H;
    }

    public int getAmount() {
        return amount;
    }

    public String getDestination() {
        return destination;
    }

    public int getMinFinalCltvExpiry() {
        return min_cltv_expiry;
    }

    public Optional<String> getMessage() {
        return Optional.ofNullable(message);
    }

    @Override
    public String toString() {
        return "LNInvoice{" + "H='" + H + '\'' + ", amt=" + amount + ", dst='" + destination + '\'' + ", msg=" + message + '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LNInvoice lnInvoice = (LNInvoice) o;
        return H.equals(lnInvoice.H);
    }

    @Override
    public int hashCode() {
        return Objects.hash(H);
    }
}