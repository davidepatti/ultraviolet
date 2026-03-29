package topology;

import network.LNChannel;

import java.util.List;

public class LNDPathFinder extends MiniDijkstra{
    public static final double DEFAULT_RISK_FACTOR = 15e-9;
    public static final double DEFAULT_BASE_ATTEMPT_COST_MSAT = 100.0;
    public static final double DEFAULT_ATTEMPT_COST_PPM = 1000.0;
    public static final double DEFAULT_PATH_PROBABILITY = 0.6;
    public static final int DEFAULT_PAYMENT_AMOUNT_SAT = 10_000;

    private final double riskFactor;
    private final double baseAttemptCostMsat;
    private final double attemptCostPpm;
    private double pathProbability = DEFAULT_PATH_PROBABILITY;

    public LNDPathFinder() {
        this(
                DEFAULT_MAX_HOPS,
                DEFAULT_RISK_FACTOR,
                DEFAULT_BASE_ATTEMPT_COST_MSAT,
                DEFAULT_ATTEMPT_COST_PPM,
                DEFAULT_PATH_PROBABILITY,
                DEFAULT_PAYMENT_AMOUNT_SAT
        );
    }

    public LNDPathFinder(int maxHops,
                         double riskFactor,
                         double baseAttemptCostMsat,
                         double attemptCostPpm,
                         double defaultPathProbability,
                         int defaultPaymentAmountSat) {
        super(maxHops);
        this.riskFactor = riskFactor;
        this.baseAttemptCostMsat = baseAttemptCostMsat;
        this.attemptCostPpm = attemptCostPpm;
        this.pathProbability = defaultPathProbability > 0.0 ? defaultPathProbability : DEFAULT_PATH_PROBABILITY;
        paymentAmountSat = Math.max(defaultPaymentAmountSat, 0);
    }

    @Override
    public double weight(ChannelGraph.Edge edge, Path path) {
        // MiniDijkstra already accumulates cost across expansions.
        // Return only the incremental cost of this newly added edge.
        var p = edge.policy();
        if (p == null) {
            return Double.POSITIVE_INFINITY;
        }
        double incrementalCost = 0.0;
        if (path.getSize() > 1) {
            incrementalCost += routingFees(p) + timelockOpportunityCost(p);
        }
        if (path.getSize() == 1) {
            incrementalCost += probabilisticPenalty();
        }
        return incrementalCost;
    }

    @Override
    public PathDetails describePath(Path path) {
        if (path.edges().isEmpty()) {
            return new PathDetails(path, 0.0, List.of());
        }

        double routingFees = 0.0;
        double timelockCost = 0.0;
        for (ChannelGraph.Edge edge : path.forwardingEdges()) {
            LNChannel.Policy policy = edge.policy();
            if (policy == null) {
                return new PathDetails(
                        path,
                        Double.POSITIVE_INFINITY,
                        List.of(new CostComponent("invalid_policy", Double.POSITIVE_INFINITY))
                );
            }
            routingFees += routingFees(policy);
            timelockCost += timelockOpportunityCost(policy);
        }

        double probabilisticPenalty = probabilisticPenalty();
        double total = routingFees + timelockCost + probabilisticPenalty;
        return new PathDetails(path, total, List.of(
                new CostComponent("routing_fees", routingFees),
                new CostComponent("timelock_opportunity_cost", timelockCost),
                new CostComponent("probabilistic_penalty", probabilisticPenalty)
        ));
    }

    @Override
    public double totalCost(Path path) {
        return describePath(path).totalCost();
    }

    private double routingFees(LNChannel.Policy policy) {
        return policy.getBaseFee() / 1000.0
                + (paymentAmountSat * policy.getFeePpm()) / 1_000_000.0;
    }

    private double timelockOpportunityCost(LNChannel.Policy policy) {
        return paymentAmountSat * policy.getCLTVDelta() * riskFactor;
    }

    private double probabilisticPenalty() {
        double attemptPenaltySat = baseAttemptCostMsat / 1000.0
                + (paymentAmountSat * attemptCostPpm) / 1_000_000.0;
        return attemptPenaltySat / pathProbability;
    }
}
