import utils.CryptoKit;

import java.io.Serializable;

public class UVTransaction implements Serializable {

    public enum Type {
        CHANNEL_FUNDING,
        COOPERATIVE_CLOSE,
        FORCE_CLOSE,
        HTLC_TIMEOUT_SUCCESS,
        PENALTY_TRANSACTION,
        EXTERNAL_BLOB
    }

    /**
     * Returns the typical transaction size in vBytes for a given Lightning Network transaction type.
     * <p>
     * Transaction Types Summary:
     * <p>
     * CHANNEL_FUNDING:
     * - Typical Size: ~250 vB
     * - 1 input (P2PKH or SegWit), 2 outputs (multisig + change)
     * - If both peers fund, inputs increase.
     * <p>
     * COOPERATIVE_CLOSE:
     * - Typical Size: ~180 vB
     * - 1 input (multisig), 2 outputs (split funds)
     * - Clean, simple close without penalties or timelocks.
     * <p>
     * FORCE_CLOSE (Unilateral Close):
     * - Typical Size: ~280 vB
     * - 1 input (multisig), outputs include timelocks and possible HTLC outputs.
     * - More complex scripts to enforce LN rules.
     * <p>
     * HTLC_TIMEOUT_SUCCESS:
     * - Typical Size: ~350 vB
     * - Spending HTLC outputs with complex conditional scripts (hashlocks + timelocks).
     * - Each pending HTLC may require a separate transaction.
     * <p>
     * PENALTY_TRANSACTION:
     * - Typical Size: ~200 vB
     * - Triggered when a revoked state is published.
     * - Uses revocation keys to claim all funds.
     */
    private final String txId;
    private final Type type;
    private final int amount;
    private final String node1_pub;
    private final String node2_pub;
    private final int fees_per_byte;
    private final int size;

    private UVTransaction(String txId, Type type, int amount, String node1_pub, String node2_pub, int fees_per_byte,int size) {

        this.txId = txId;
        this.type = type;
        this.amount = amount;
        this.node1_pub = node1_pub;
        this.node2_pub = node2_pub;
        this.fees_per_byte = fees_per_byte;
        this.size = size;
    }
    public static UVTransaction createTx(String txId, Type type, int amount, String node1_pub, String node2_pub, int fees_per_byte) {
        var tx = new UVTransaction(txId,type,amount,node1_pub,node2_pub,fees_per_byte,getTransactionSize(type));
        return tx;
    }
    public static UVTransaction createTx(String txId, Type type, int amount, String node1_pub, String node2_pub, int fees_per_byte,int size) {
        var tx = new UVTransaction(txId,type,amount,node1_pub,node2_pub,fees_per_byte,size);
        return tx;
    }

    public String getTxId() {
        return txId;
    }

    public Type getType() {
        return type;
    }

    public int getAmount() {
        return amount;
    }

    public String getNode1Pub() {
        return node1_pub;
    }

    public String getNode2Pub() {
        return node2_pub;
    }

    public int getFeesPerByte() {
        return fees_per_byte;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Tx{").append(txId).append(", type: ").append(type);
        if (type == Type.EXTERNAL_BLOB) { sb.append(", size: ").append(size); }
        else {
            sb.append(", amt:") .append(amount) .append(", node1:").append(node1_pub) .append(", node2:").append(node2_pub);
        }
          sb.append(", fees:").append(fees_per_byte)
          .append('}');
        return sb.toString();
    }

    public int getFees() {
        return fees_per_byte * getSize();
    }

    public int getSize() {
       return getTransactionSize(this.getType()) ;
    }

    public static int getTransactionSize(Type type) {
        switch (type) {
            case CHANNEL_FUNDING:
                return 250;  // Average size for standard funding transaction
            case COOPERATIVE_CLOSE:
                return 180;  // Simple cooperative close
            case FORCE_CLOSE:
                return 280;  // Unilateral close with timelocks
            case HTLC_TIMEOUT_SUCCESS:
                return 350;  // Complex HTLC spend
            case PENALTY_TRANSACTION:
                return 200;  // Revoked state penalty
            default:
                throw new IllegalArgumentException("Unspecified tx size for type: " + type);
        }
    }
}
