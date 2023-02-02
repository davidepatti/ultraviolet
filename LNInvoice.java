import java.math.BigInteger;
import java.util.Objects;
import java.util.Optional;

public class LNInvoice {
    private final long r;
    private final String H;
    private final int amount;
    private final String recipient;
    private final Optional<String> message;

    public LNInvoice(long r, int amount, String recipient, Optional<String> message) {
        this.r = r;
        H = Kit.bytesToHexString(Kit.sha256(BigInteger.valueOf(r).toByteArray()));
        this.amount = amount;
        this.recipient = recipient;
        this.message = message;
    }

    public String getHash() {
       return H;
    }

    public int getAmount() {
        return amount;
    }

    public String getRecipient() {
        return recipient;
    }

    @Override
    public String toString() {
        return "LNInvoice{" +
                "H='" + H + '\'' +
                ", amount=" + amount +
                ", recipient='" + recipient + '\'' +
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