package topology;

import network.LNChannel;

public class LNDPathFinder extends MiniDijkstra{
    private static final double RISK_FACTOR = 15e-9;
    private static final double BASE_ATTEMPT_COST_MSAT = 100.0;
    private static final double ATTEMPT_COST_PPM = 1000.0;
    private static final double DEFAULT_PATH_PROBABILITY = 0.6;
    private static final int DEFAULT_PAYMENT_AMOUNT_SAT = 10_000;

    private int paymentAmountSat = DEFAULT_PAYMENT_AMOUNT_SAT;
    private double pathProbability = DEFAULT_PATH_PROBABILITY;

    @Override
    public void setPaymentAmount(int amountSat) {
        paymentAmountSat = Math.max(amountSat, 0);
    }

    @Override
    public double weight(ChannelGraph.Edge edge, Path path) {
        // MiniDijkstra already accumulates cost across expansions.
        // Return only the incremental cost of this newly added edge.
        var p = edge.policy();
        if (p == null) {
            return Double.POSITIVE_INFINITY;
        }
        double incrementalCost = routingFees(p) + timelockOpportunityCost(p);
        if (path.getSize() == 1) {
            incrementalCost += probabilisticPenalty();
        }
        return incrementalCost;
    }

    @Override
    public double totalCost(Path path) {
        if (path.edges().isEmpty()) {
            return 0.0;
        }

        double totalCost = probabilisticPenalty();
        for (ChannelGraph.Edge edge : path.edges()) {
            LNChannel.Policy policy = edge.policy();
            if (policy == null) {
                return Double.POSITIVE_INFINITY;
            }
            totalCost += routingFees(policy) + timelockOpportunityCost(policy);
        }
        return totalCost;
    }

    private double routingFees(LNChannel.Policy policy) {
        return policy.getBaseFee() / 1000.0
                + (paymentAmountSat * policy.getFeePpm()) / 1_000_000.0;
    }

    private double timelockOpportunityCost(LNChannel.Policy policy) {
        return paymentAmountSat * policy.getCLTVDelta() * RISK_FACTOR;
    }

    private double probabilisticPenalty() {
        double attemptPenaltySat = BASE_ATTEMPT_COST_MSAT / 1000.0
                + (paymentAmountSat * ATTEMPT_COST_PPM) / 1_000_000.0;
        return attemptPenaltySat / pathProbability;
    }
}
