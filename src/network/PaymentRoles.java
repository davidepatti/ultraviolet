package network;

import misc.UVConfig;

import java.util.*;

/**
 * Journal payment roles (payer, merchant, router), assigned once per node at creation, conditionally on
 * the node's structural profile. Roles are private oracle data: they shape opening-target choice here and
 * request demand in the offline schedule generator, and are never exported as public features.
 *
 * Disabled unless {@code journal.roles.enabled=true}; when disabled, bootstrap is unchanged.
 * Assignment uses its own named seed ({@code journal.roles.seed}) so the network RNG stream is untouched.
 */
public final class PaymentRoles {
    public static final List<String> ROLES = List.of("payer", "merchant", "router");
    public static final String VERSION = "journal-payment-roles-v1";

    private final Random rng;
    private final long seed;
    private final Map<String, double[]> probabilities;   // profile -> P(role) in ROLES order
    private final double[] openWeights;                    // ROLES order
    private final double maxOpenWeight;
    private final Map<String, String> assigned = new LinkedHashMap<>();

    private PaymentRoles(long seed, Map<String, double[]> probabilities, double[] openWeights) {
        this.seed = seed;
        this.rng = new Random(seed);
        this.probabilities = probabilities;
        this.openWeights = openWeights;
        this.maxOpenWeight = Arrays.stream(openWeights).max().orElseThrow();
    }

    /** Returns null when roles are disabled. Rejects incomplete or invalid settings explicitly. */
    public static PaymentRoles fromConfig(UVConfig config) {
        var settings = config.getEffectiveSettings();
        String enabled = String.valueOf(settings.getOrDefault("journal.roles.enabled", "false")).trim();
        if (enabled.equals("false")) return null;
        if (!enabled.equals("true")) throw new IllegalArgumentException("journal.roles.enabled must be true or false");
        long seed = Long.parseLong(required(settings, "journal.roles.seed"));
        if (seed <= 0) throw new IllegalArgumentException("journal.roles.seed must be positive");
        double[] weights = new double[ROLES.size()];
        for (int i = 0; i < ROLES.size(); i++) {
            weights[i] = finite(Double.parseDouble(required(settings, "journal.roles.open_weight." + ROLES.get(i))), "open weight");
            if (weights[i] <= 0) throw new IllegalArgumentException("Opening weights must be positive");
        }
        Map<String, double[]> probs = new TreeMap<>();
        for (String profile : new TreeSet<>(config.getNodeProfiles().keySet())) {
            if (profile.equals("default")) continue; // never selected when profile probabilities sum to 1
            double[] p = new double[ROLES.size()];
            double sum = 0;
            for (int i = 0; i < ROLES.size(); i++) {
                String value;
                try { value = config.getProfileAttribute(profile, "role_" + ROLES.get(i)); }
                catch (IllegalArgumentException missing) { throw new IllegalArgumentException("Missing profile." + profile + ".role_" + ROLES.get(i)); }
                p[i] = finite(Double.parseDouble(value), "role probability");
                if (p[i] < 0) throw new IllegalArgumentException("Negative role probability for " + profile);
                sum += p[i];
            }
            if (Math.abs(sum - 1.0) > 1e-9) throw new IllegalArgumentException("Role probabilities for " + profile + " sum to " + sum);
            probs.put(profile, p);
        }
        if (probs.isEmpty()) throw new IllegalArgumentException("No profiles with role probabilities");
        return new PaymentRoles(seed, probs, weights);
    }

    private static String required(Map<String, Object> settings, String key) {
        Object value = settings.get(key);
        if (value == null || String.valueOf(value).isBlank()) throw new IllegalArgumentException("Missing " + key);
        return String.valueOf(value).trim();
    }

    private static double finite(double value, String what) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Non-finite " + what);
        return value;
    }

    /** Called once per node, in creation order, on the single bootstrap thread. */
    public synchronized String assign(String pubkey, UVConfig.NodeProfile profile) {
        if (assigned.containsKey(pubkey)) throw new IllegalStateException("Role already assigned: " + pubkey);
        double[] p = probabilities.get(profile.getName());
        if (p == null) throw new IllegalStateException("No role probabilities for profile " + profile.getName());
        double u = rng.nextDouble(), cumulative = 0;
        String role = null;
        for (int i = 0; i < ROLES.size(); i++) {
            cumulative += p[i];
            if (u < cumulative) { role = ROLES.get(i); break; }
        }
        if (role == null) { // rounding at the upper end: last role with positive probability
            for (int i = ROLES.size() - 1; i >= 0; i--) if (p[i] > 0) { role = ROLES.get(i); break; }
        }
        assigned.put(pubkey, role);
        return role;
    }

    public synchronized String roleOf(String pubkey) {
        String role = assigned.get(pubkey);
        if (role == null) throw new IllegalStateException("No role for " + pubkey);
        return role;
    }

    /** Acceptance probability for a profile-matching opening candidate (rejection sampling within profile). */
    public double openAcceptance(String pubkey) {
        return openWeights[ROLES.indexOf(roleOf(pubkey))] / maxOpenWeight;
    }

    /** Retry multiplier keeping the expected number of profile matches comparable to the role-free bootstrap. */
    public int retryMultiplier() {
        double min = Arrays.stream(openWeights).min().orElseThrow();
        return (int) Math.ceil(maxOpenWeight / min);
    }

    public synchronized Map<String, String> assignments() { return Collections.unmodifiableMap(new TreeMap<>(assigned)); }

    public Map<String, Object> description() {
        var result = new TreeMap<String, Object>();
        result.put("version", VERSION);
        result.put("seed", seed);
        var p = new TreeMap<String, Object>();
        probabilities.forEach((profile, values) -> {
            var row = new TreeMap<String, Object>();
            for (int i = 0; i < ROLES.size(); i++) row.put(ROLES.get(i), values[i]);
            p.put(profile, row);
        });
        result.put("role_probabilities", p);
        var w = new TreeMap<String, Object>();
        for (int i = 0; i < ROLES.size(); i++) w.put(ROLES.get(i), openWeights[i]);
        result.put("open_weights", w);
        result.put("retry_multiplier", retryMultiplier());
        return result;
    }
}
