package network;

import misc.UVConfig;
import stats.DistributionGenerator;

import java.util.*;

/**
 * Directed channel fee policies for the traffic-role study, drawn once at the settled branch point
 * from a dedicated seed (no draw from any simulator stream). Two laws:
 * <ul>
 *   <li>{@code per_edge}: UV's opening law redrawn independently per directed edge (base from
 *       {@code base_fee_set}, ppm from the owner's profile {@code ppm_fees} distribution);</li>
 *   <li>{@code node_policy}: one (base, ppm) per node from its profile/role group, applied to each of its
 *       channel directions with multiplicative lognormal ppm jitter.</li>
 * </ul>
 * Pure function of (spec, seed, sorted nodes/channels, profiles, roles): deterministic and testable alone.
 */
public final class FeePolicyLaw {
    public static final String VERSION = "journal-fee-policy-law-v1";

    public record Distribution(int min, int max, double median, double mean) {
        public Distribution {
            if (min < 0 || max < min || !(median >= min && median <= max) || !Double.isFinite(mean) || mean < min || mean > max)
                throw new IllegalArgumentException("Invalid ppm distribution " + min + "/" + max + "/" + median + "/" + mean);
        }
    }
    public record Group(Distribution ppm, List<Integer> baseMsatSet) {
        public Group {
            baseMsatSet = List.copyOf(baseMsatSet);
            if (baseMsatSet.isEmpty() || baseMsatSet.stream().anyMatch(b -> b == null || b < 0)) throw new IllegalArgumentException("Invalid base fee set");
        }
    }
    public record Spec(String mode, long seed, Map<String, Group> groups, double jitterSigma, String calibrationId) {
        public Spec {
            if (!Set.of("per_edge", "node_policy").contains(mode)) throw new IllegalArgumentException("Unknown fee mode " + mode);
            if (seed <= 0) throw new IllegalArgumentException("Fee seed must be positive");
            groups = Collections.unmodifiableMap(new TreeMap<>(groups == null ? Map.of() : groups));
            if (mode.equals("node_policy")) {
                if (groups.isEmpty()) throw new IllegalArgumentException("node_policy requires groups");
                if (!Double.isFinite(jitterSigma) || jitterSigma < 0 || jitterSigma > 2) throw new IllegalArgumentException("Invalid jitter sigma");
                if (calibrationId == null || calibrationId.isBlank()) throw new IllegalArgumentException("node_policy requires a recorded calibration_id");
            }
        }
    }
    /** directed[channelId][sourcePubkey] -> policy; nodePolicies only for node_policy. */
    public record Result(Map<String, Map<String, LNChannel.Policy>> directed, Map<String, Map<String, Object>> nodePolicies) {}

    private FeePolicyLaw() {}

    static Group group(Spec spec, String profile, String role) {
        for (String key : new String[]{profile + "_" + role, role, profile, "default"}) {
            if (key != null && spec.groups().containsKey(key)) return spec.groups().get(key);
        }
        throw new IllegalArgumentException("No fee group for profile " + profile + " role " + role);
    }

    /**
     * @param nodeProfiles pubkey -> profile name (all nodes)
     * @param roles pubkey -> role; required (complete) for node_policy, ignored for per_edge
     * @param channels channelId -> {node1, node2, cltv1, cltv2}
     */
    public static Result draw(Spec spec, UVConfig config, Map<String, String> nodeProfiles, Map<String, String> roles,
                              Map<String, String[]> channels) {
        var rng = new Random(spec.seed());
        var sortedNodes = new TreeMap<>(nodeProfiles);
        Map<String, int[]> nodeDraw = new TreeMap<>();
        Map<String, Map<String, Object>> nodeTable = new TreeMap<>();
        if (spec.mode().equals("node_policy")) {
            for (var e : sortedNodes.entrySet()) {
                String role = roles == null ? null : roles.get(e.getKey());
                if (role == null) throw new IllegalArgumentException("Missing role for " + e.getKey());
                var g = group(spec, e.getValue(), role);
                int base = g.baseMsatSet().get(rng.nextInt(g.baseMsatSet().size()));
                var d = g.ppm();
                int ppm = DistributionGenerator.generateIntSample(rng, d.min(), d.max(), d.median(), d.mean());
                nodeDraw.put(e.getKey(), new int[]{base, ppm, d.min(), d.max()});
                var row = new TreeMap<String, Object>();
                row.put("profile", e.getValue()); row.put("role", role); row.put("base_msat", base); row.put("ppm", ppm);
                nodeTable.put(e.getKey(), row);
            }
        }
        List<String> baseSet = null;
        if (spec.mode().equals("per_edge")) {
            Object raw = config.getEffectiveSettings().get("base_fee_set"); // same list UV's opening law draws from
            if (raw == null || String.valueOf(raw).isBlank()) throw new IllegalArgumentException("base_fee_set required");
            baseSet = Arrays.stream(String.valueOf(raw).split(",")).map(String::trim).toList();
        }
        Map<String, Map<String, LNChannel.Policy>> directed = new TreeMap<>();
        for (var c : new TreeMap<>(channels).entrySet()) {
            String[] row = c.getValue();
            var sides = new TreeMap<String, LNChannel.Policy>();
            for (int side = 0; side < 2; side++) {
                String owner = row[side];
                int cltv = Integer.parseInt(row[2 + side]);
                if (!nodeProfiles.containsKey(owner)) throw new IllegalArgumentException("Unknown endpoint " + owner);
                int base, ppm;
                if (spec.mode().equals("per_edge")) {
                    base = Integer.parseInt(baseSet.get(rng.nextInt(baseSet.size())).trim());
                    ppm = config.getNodeProfile(nodeProfiles.get(owner)).getRandomSample(rng, "ppm_fees");
                } else {
                    int[] n = nodeDraw.get(owner);
                    base = n[0];
                    double z = rng.nextGaussian();
                    long jittered = Math.round(n[1] * Math.exp(spec.jitterSigma() * z));
                    ppm = (int) Math.max(n[2], Math.min(n[3], jittered));
                }
                sides.put(owner, new LNChannel.Policy(cltv, base, ppm));
            }
            if (sides.size() != 2) throw new IllegalArgumentException("Channel needs two distinct endpoints: " + c.getKey());
            directed.put(c.getKey(), sides);
        }
        return new Result(directed, nodeTable);
    }
}
