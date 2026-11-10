package protocol;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

public class LNInvoice implements Serializable {
    // Pinned to the value the class had before min_cltv_expiry became configurable,
    // so that existing snapshots keep loading.
    @java.io.Serial
    private static final long serialVersionUID = -8909898530012650530L;
    public static final int DEFAULT_MIN_FINAL_CLTV_EXPIRY = 9; // antonop 339
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
    private final int min_cltv_expiry;

    public LNInvoice(String paymentHash, int amount, String recipient, String message) {
        this(paymentHash, amount, recipient, message, DEFAULT_MIN_FINAL_CLTV_EXPIRY);
    }

    public LNInvoice(String paymentHash, int amount, String recipient, String message, int minFinalCltvExpiry) {
        if (minFinalCltvExpiry <= 0) throw new IllegalArgumentException("Positive min_final_cltv_expiry required");
        this.H = paymentHash;
        this.amount = amount;
        this.destination = recipient;
        this.message = message;
        this.min_cltv_expiry = minFinalCltvExpiry;
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
        return "protocol.LNInvoice{" + "H='" + H + '\'' + ", amt=" + amount + ", dst='" + destination + '\'' + ", msg=" + message + '}';
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