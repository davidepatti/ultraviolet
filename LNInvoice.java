import java.math.BigInteger;
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

    @Override
    public String toString() {
        return "LNInvoice{" +
                "H='" + H + '\'' +
                ", amount=" + amount +
                ", recipient='" + recipient + '\'' +
                ", message=" + message +
                '}';
    }
}