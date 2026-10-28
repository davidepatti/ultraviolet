package network;

import message.MsgUpdateAddHTLC;
import misc.CryptoKit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Authoritative HTLC outcomes in UV's shared-channel abstraction. This models
 * irreversible channel resolution, not commitment transactions or force-close.
 * No method takes a node lock or invokes a node callback while holding this lock.
 */
final class HTLCResolution {
    static final String MODEL = "uv-shared-channel-htlc-v1";
    enum State { PENDING, FULFILLED, FAILED }
    record Key(String channel, int id) {
        static Key of(MsgUpdateAddHTLC message) { return new Key(message.getChannel_id(), message.getId()); }
    }
    record Outcome(State state, String reason, long preimage) {}

    private static final class Contract {
        final Key key;
        final UVChannel channel;
        final String sender, receiver, hash;
        final int amount, expiry;
        Key outgoing;
        Outcome outcome = new Outcome(State.PENDING, null, 0);
        Contract(UVChannel channel, String sender, MsgUpdateAddHTLC message) {
            this.key = Key.of(message);
            this.channel = channel;
            this.sender = sender;
            this.receiver = sender.equals(channel.getNode1PubKey()) ? channel.getNode2PubKey() : channel.getNode1PubKey();
            this.hash = message.getPayment_hash();
            this.amount = message.getAmount();
            this.expiry = message.getCLTVExpiry();
        }
        void check(MsgUpdateAddHTLC message) {
            if (amount != message.getAmount() || expiry != message.getCLTVExpiry() || !hash.equals(message.getPayment_hash())) {
                throw new IllegalStateException("Conflicting HTLC identity: " + key);
            }
        }
    }

    // Terminal records deliberately survive retries, so old messages cannot act on a new attempt.
    // Contracts retain scalar metadata, not onion packets or node references.
    private final UVNetwork network;
    HTLCResolution(UVNetwork network) { this.network = network; }

    private final Map<Key, Contract> contracts = new LinkedHashMap<>();
    private final Map<Key, Contract> pending = new LinkedHashMap<>();

    synchronized boolean offer(UVChannel channel, String sender, MsgUpdateAddHTLC message, MsgUpdateAddHTLC incoming) {
        Key key = Key.of(message);
        if (!channel.getChannelId().equals(key.channel()) || message.getAmount() <= 0
                || (!sender.equals(channel.getNode1PubKey()) && !sender.equals(channel.getNode2PubKey()))) {
            throw new IllegalArgumentException("Invalid HTLC offer");
        }
        if (contracts.containsKey(key)) throw new IllegalStateException("Reused HTLC ID: " + key);
        Contract parent = incoming == null ? null : require(incoming);
        if (parent != null) {
            if (!parent.receiver.equals(sender) || !parent.hash.equals(message.getPayment_hash())
                    || parent.amount < message.getAmount() || parent.expiry <= message.getCLTVExpiry()) {
                throw new IllegalStateException("Invalid forwarding linkage");
            }
            if (parent.outcome.state() != State.PENDING) return false;
            if (parent.outgoing == null && network.getTimechain().getCurrentBlockHeight() >= parent.expiry) {
                resolve(parent, new Outcome(State.FAILED, "attempt_timeout", 0));
                return false;
            }
            if (parent.outgoing != null) throw new IllegalStateException("HTLC already forwarded");
        }
        if (!channel.reservePending(sender, message.getAmount())) return false;
        Contract contract = new Contract(channel, sender, message);
        contracts.put(key, contract);
        pending.put(key, contract);
        if (parent != null) parent.outgoing = key;
        return true;
    }

    private Contract require(MsgUpdateAddHTLC message) {
        Contract contract = contracts.get(Key.of(message));
        if (contract == null) throw new IllegalStateException("Unregistered HTLC: " + Key.of(message));
        contract.check(message);
        return contract;
    }

    synchronized Outcome receive(String receiver, MsgUpdateAddHTLC message) {
        Contract contract = require(message);
        if (!contract.receiver.equals(receiver)) throw new IllegalStateException("Wrong HTLC receiver");
        return contract.outcome;
    }

    synchronized boolean forwarded(MsgUpdateAddHTLC message) { return require(message).outgoing != null; }

    synchronized Outcome outcome(MsgUpdateAddHTLC message) {
        Contract contract = contracts.get(Key.of(message));
        if (contract == null) return null; // Preserve orphan state for the integrity barrier to detect.
        contract.check(message);
        return contract.outcome;
    }

    private void resolve(Contract contract, Outcome outcome) {
        if (contract.outcome.state() != State.PENDING) return;
        contract.channel.resolvePending(contract.sender, contract.amount, outcome.state() == State.FULFILLED);
        contract.outcome = outcome;
        pending.remove(contract.key);
    }

    synchronized Outcome fulfill(String receiver, MsgUpdateAddHTLC message, long preimage) {
        Contract contract = require(message);
        if (!contract.receiver.equals(receiver)) throw new IllegalStateException("Wrong fulfilling peer");
        String hash = CryptoKit.bytesToHexString(CryptoKit.sha256(BigInteger.valueOf(preimage).toByteArray()));
        if (!contract.hash.equals(hash)) throw new IllegalStateException("Invalid HTLC preimage");
        if (contract.outcome.state() != State.PENDING) return contract.outcome;
        if (contract.outgoing != null) {
            Outcome child = contracts.get(contract.outgoing).outcome;
            if (child.state() != State.FULFILLED || child.preimage() != preimage) {
                throw new IllegalStateException("Cannot fulfill before downstream resolution");
            }
        }
        if (contract.outgoing == null && network.getTimechain().getCurrentBlockHeight() >= contract.expiry) {
            resolve(contract, new Outcome(State.FAILED, "attempt_timeout", 0));
        } else resolve(contract, new Outcome(State.FULFILLED, null, preimage));
        return contract.outcome;
    }

    synchronized Outcome fail(String receiver, MsgUpdateAddHTLC message, String reason) {
        Contract contract = require(message);
        if (!contract.receiver.equals(receiver)) throw new IllegalStateException("Wrong failing peer");
        if (contract.outcome.state() != State.PENDING) return contract.outcome;
        if (contract.outgoing != null) {
            Outcome child = contracts.get(contract.outgoing).outcome;
            if (child.state() == State.PENDING) return contract.outcome;
            resolve(contract, child); // Fulfillment wins over a contradictory failure request.
        } else resolve(contract, new Outcome(State.FAILED, Objects.requireNonNull(reason), 0));
        return contract.outcome;
    }

    /** Validate the channel/HTLC ID and proof, never match a reply by payment hash alone. */
    synchronized void acceptFulfillment(String sender, Key key, long preimage) {
        Contract contract = contracts.get(key);
        if (contract == null) return; // An obsolete message after a settled restore has no effect.
        if (!contract.sender.equals(sender)) throw new IllegalStateException("Wrong HTLC reply recipient");
        String hash = CryptoKit.bytesToHexString(CryptoKit.sha256(BigInteger.valueOf(preimage).toByteArray()));
        if (!contract.hash.equals(hash)) throw new IllegalStateException("Invalid HTLC preimage");
        if (contract.outcome.state() == State.PENDING) throw new IllegalStateException("Fulfillment lacks a resolved channel outcome");
    }

    synchronized void acceptFailure(String sender, Key key) {
        Contract contract = contracts.get(key);
        if (contract == null) return;
        if (!contract.sender.equals(sender)) throw new IllegalStateException("Wrong HTLC reply recipient");
        if (contract.outcome.state() == State.PENDING) throw new IllegalStateException("Failure lacks a resolved channel outcome");
    }

    /** Called by the ordinary clock. Resolve downstream first, including stopped peers. */
    synchronized void onBlock(int height) {
        boolean changed;
        do {
            changed = false;
            for (Contract contract : new ArrayList<>(pending.values())) {
                if (contract.outgoing != null) {
                    Outcome child = contracts.get(contract.outgoing).outcome;
                    if (child.state() != State.PENDING) { resolve(contract, child); changed = true; }
                } else if (height >= contract.expiry) {
                    resolve(contract, new Outcome(State.FAILED, "attempt_timeout", 0));
                    changed = true;
                }
            }
        } while (changed);
    }

    synchronized boolean terminal(Key key) {
        Contract contract = contracts.get(key);
        return contract != null && contract.outcome.state() != State.PENDING;
    }

    synchronized int pendingCount() { return pending.size(); }
    synchronized void reset() { contracts.clear(); pending.clear(); }
}
