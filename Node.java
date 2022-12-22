public class Node {
    private final String pubkey;
    // abstracted: indeed alias can be changed
    private final String alias;

    private int onchain_balance;
    private int lightning_balance;

    public String getPubkey() {
        return pubkey;
    }

    public String getAlias() {
        return alias;
    }

    public Node(String pubkey, String alias, int onchain_balance, int lightning_balance) {
        this.pubkey = pubkey;
        this.alias = alias;
        this.onchain_balance = onchain_balance;
        this.lightning_balance = lightning_balance;
    }
}
