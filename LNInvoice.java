import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

public class LNInvoice {
    // antonop 339
    private final long r;
    private final String H;
    private final int amount;
    private final String destination;
    private final String message;
    private final int min_cltv_expiry = 9;

    public LNInvoice(long r, int amount, String recipient, String message) {
        this.r = r;
        H = Kit.bytesToHexString(Kit.sha256(BigInteger.valueOf(r).toByteArray()));
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

    public int getMin_cltv_expiry() {
        return min_cltv_expiry;
    }

    @Override
    public String toString() {
        return "LNInvoice{" +
                "H='" + H + '\'' +
                ", amount=" + amount +
                ", recipient='" + destination + '\'' +
                ", message=" + message +
                '}';
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