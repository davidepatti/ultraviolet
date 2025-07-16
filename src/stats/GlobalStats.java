package stats;

import network.*;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class GlobalStats {

    private final UVNetwork uvNetwork;

    public GlobalStats(UVNetwork network) {
        uvNetwork = network;
    }

    public static class StatFunctions {

        private final List<Double> sortedList;

        public StatFunctions(DoubleStream doubleStream) {
            this.sortedList = doubleStream.boxed().sorted().collect(Collectors.toList());
        }

        public double calculateMin() {
            OptionalDouble min = sortedList.stream()
                    .mapToDouble(Double::doubleValue)
                    .min();

            return min.isPresent() ? min.getAsDouble() : 0;
        }

        public double calculateMax() {
            OptionalDouble max = sortedList.stream()
                    .mapToDouble(Double::doubleValue)
                    .max();

            return max.isPresent() ? max.getAsDouble() : 0;
        }

        public double calculateAverage() {
            OptionalDouble average = sortedList.stream()
                    .mapToDouble(Double::doubleValue)
                    .average();

            return average.isPresent() ? average.getAsDouble() : 0;
        }


        public double calculateStandardDeviation() {
            double sum = 0.0, standardDeviation = 0.0;
            int length = sortedList.size();

            for(double num : sortedList) {
                sum += num;
            }

            double mean = sum/length;

            for(double num: sortedList) {
                standardDeviation += Math.pow(num - mean, 2);
            }

            return Math.sqrt(standardDeviation/length);
        }

        public double calculatePercentile(int percentile) {
            int index = (int) Math.ceil(percentile * 0.01 * sortedList.size());
            return sortedList.get(index-1);  // Subtracting 1, because the index is zero-based
        }

        public double calculateFirstQuartile() {
            return calculatePercentile(25);
        }

        public double calculateMedian() {
            return calculatePercentile(50);
        }

        public double calculateThirdQuartile() {
            return calculatePercentile(75);
        }
    }


    public UVNode getMaxGraphSizeNode() {

        Optional<UVNode> max = uvNetwork.getUVNodeList().values().stream().max(Comparator.comparingInt(e -> e.getChannelGraph().getNodeCount()));
        return max.orElse(null);
    }
    public UVNode getMinGraphSizeNode() {

        Optional<UVNode> max = uvNetwork.getUVNodeList().values().stream().min(Comparator.comparingInt(e -> e.getChannelGraph().getNodeCount()));
        return max.orElse(null);
    }

    public double getAverageGraphSize() {
        return uvNetwork.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getNodeCount()).average().getAsDouble();
    }


    private String generateStatsItem(String label, DoubleStream stream) {

        StatFunctions q = new StatFunctions(stream);
        String result = String.format("\n%-15s%-15.0f%-15.0f%-15.2f%-15.2f%-15.2f%-15.2f%-15.2f",
                label,
                q.calculateMin(),
                q.calculateMax(),
                q.calculateAverage(),
                q.calculateStandardDeviation(),
                q.calculateFirstQuartile(),
                q.calculateMedian(),
                q.calculateThirdQuartile()
        );
        return result;
    }

    public String generateNetworkReport() {

        System.out.println("Generating stats...");
// DoubleStream Variables
        DoubleStream graphNodeStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getChannelGraph().getNodeCount());
        DoubleStream graphChannelStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getChannelGraph().getChannelCount());
        DoubleStream channelNumberStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getLNChannelList().size());
        DoubleStream nodeCapacityStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getNodeCapacity());
        DoubleStream lightingBalanceStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getLocalBalance());
        DoubleStream outboundFractionStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getOverallOutboundFraction());
        DoubleStream generatedInvoicesStream = uvNetwork.getUVNodeList().values().stream().mapToDouble(e -> e.getGeneratedInvoices().size());

        String s1 = generateStatsItem("Graph Nodes",graphNodeStream);
        String s2 = generateStatsItem("Graph Channels",graphChannelStream);
        String s3 = generateStatsItem("Node Channels",channelNumberStream);
        String s4 = generateStatsItem("Node Capacity",nodeCapacityStream);
        String s5 = generateStatsItem("LN balance",lightingBalanceStream);
        String s6 = generateStatsItem("Invoices",generatedInvoicesStream);
        String s7 = generateStatsItem("Outbound %",outboundFractionStream);
        System.out.println("End generating stats.");

        String s = String.format("\n%-15s%-15s%-15s%-15s%-15s%-15s%-15s%-15s",
                "Value",
                "Min",
                "Max",
                "Average",
                "Std Deviation",
                "1st quartile",
                "median",
                "3rd quartile"
        );
        return s+s1+s2+s3+s4+s5+s6+s7;
    }

    public String generateInvoiceReport() {

        var s = new StringBuilder(NodeStats.InvoiceReport.generateInvoiceReportHeader());

            for (UVNode node: uvNetwork.getSortedNodeListByPubkey()) {
                if (!node.getInvoiceReports().isEmpty()) {
                    for (NodeStats.InvoiceReport report: node.getInvoiceReports()) {
                        s.append("\n").append(report);
                    }
                }
            }
            return s.toString();
    }

    public static double calculateLambda(ArrayList<Double> V) {
        double sum = 0;
        for (Double value : V) {
            sum += value;
        }
        double mean = sum / V.size();
        return 1 / mean;
    }

    public static double getValue(double lambda) {
        
        Random rand = new Random();
        double u = rand.nextDouble(); // a uniform random number between 0 (inclusive) and 1 (exclusive)
        return -Math.log(1 - u) / lambda;
    }

    public static class NodeStats implements Serializable{

        // this refers to the invoices processed
        public final ArrayList<InvoiceReport> invoiceReports = new ArrayList<>();
        // these refer to partecipation in HTLC routings
        private int invoice_processing_success = 0;
        private int invoice_processing_failures = 0;
        private int invoice_processing_volume = 0;
        // local forwarding events outcome.
        // For example, the local forwarding event can be a success, while eventually the HTLC routing could still fail
        private int forwarding_failures = 0;
        private int forwarding_successes = 0;
        private int forwarded_volume = 0;

        private int expiry_too_soon = 0;
        private int temporary_channel_failure = 0;

        public int getInvoiceProcessingSuccesses() { return invoice_processing_success; }
        public int getInvoiceProcessingFailures() { return invoice_processing_failures; }
        public int getForwarding_failures() { return forwarding_failures; }
        public int getForwarding_successes() { return forwarding_successes; }
        public int getForwarded_volume() { return forwarded_volume; }
        public ArrayList<InvoiceReport> getInvoiceReports() { return invoiceReports; }
        public void incrementInvoiceProcessingSuccessses() { this.invoice_processing_success++; }
        public int getInvoiceProcessingVolume() { return invoice_processing_volume; }
        public void incrementInvoiceProcessingFailures(String reason) { this.invoice_processing_failures++; }

        public void incrementForwardingFailures(String reason) {
            switch (reason) {
                case "expiry_too_soon":
                    expiry_too_soon++;
                    break;
                case "temporary_channel_failure":
                    temporary_channel_failure++;
                    break;
                default:
                    throw new IllegalArgumentException("Invalid reason for forwarding failure");
            }
            this.forwarding_failures++;
        }
        public void incrementForwardingSuccesses() { this.forwarding_successes++; }
        public void incrementForwardeVolume(int amt) { this.forwarded_volume+=amt; }
        public void incrementInvoiceProcessingVolume(int amt) { this.invoice_processing_volume+=amt; }

        public String generateHTLCStatsCSV() {
            return invoice_processing_success + "," + invoice_processing_failures + "," + invoice_processing_volume + "," + forwarding_failures + "," + forwarding_successes + "," + forwarded_volume + "," + expiry_too_soon + "," + temporary_channel_failure;
        }
        public static String generateHTLCStatsHeader() {
            return "invoice_processing_success, invoice_processing_failures, invoice_processing_volume, forwarding_failures, forwarding_successes, forwarded_volume, expiry_too_soon, temporary_channel_failure";
        }

        public String generateStatsCSV(UVNode uvNode) {

            if (uvNode.getChannels().isEmpty()) { return "-"; }

            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            double sum = 0;

            List<Integer> sizes = new ArrayList<>();
            for (var channel : uvNode.getChannels().values()) {
                int size = channel.getCapacity();
                sizes.add(size);
                if (size < min) { min = size; }
                if (size > max) { max = size; }
                sum += size;
            }

            double rawAverage = sum / uvNode.getChannels().size();
            int average = (int) Math.round(rawAverage);

            double rawMedian;
            Collections.sort(sizes);
            if (sizes.size() % 2 == 0)
                rawMedian = ((double)sizes.get(sizes.size()/2) + (double)sizes.get(sizes.size()/2 - 1))/2;
            else
                rawMedian = (double) sizes.get(sizes.size()/2);

            int median = (int) Math.round(rawMedian);

            return String.format("%s,%s,%d,%d,%.2f,%d,%d,%d,%d,%s",
                    uvNode.getPubKey(), uvNode.getAlias(),
                    uvNode.getNodeCapacity(),
                    uvNode.getChannels().size(),
                    uvNode.getOverallOutboundFraction(),
                    max, min, average, median,
                    uvNode.getNodeStats().generateHTLCStatsCSV());
        }
        public static String generateStatsHeader() {
            return "PubKey,Alias,Capacity,Channels,OutboundFraction,Max,Min,Average,Median,"+generateHTLCStatsHeader();
        }

        public record InvoiceReport(String hash,
                                    String sender,
                                    String dest,
                                    int amt,
                                    int total_paths,
                                    int candidate_paths,
                                    int miss_policy,
                                    int miss_capacity,
                                    int miss_local_liquidity,
                                    int miss_fees,
                                    int attempted_paths,
                                    int temporary_channel_failures,
                                    int expiry_too_soon,
                                    boolean success) implements Serializable {

            public static String generateInvoiceReportHeader() {
                return "hash, sender, dest, amt, total_paths, candidate_paths, miss_policy, miss_capacity," +
                        " miss_local_liquidity, miss_fees, attempted_paths, temporary_channel_failures," +
                        " expiry_too_soon, success";
            }

            @Override
            public String toString() {
                return hash + ',' + sender + ',' + dest + ',' +amt+","+ total_paths + "," + candidate_paths + "," +miss_policy + ","+miss_capacity +
                        "," + miss_local_liquidity + "," + miss_fees + "," + attempted_paths + "," + temporary_channel_failures +
                        "," + expiry_too_soon + "," + success;
            }

        }
    }
}
