import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class GlobalStats {

    private final UVManager uvm;

    public GlobalStats(UVManager uv_manager) {
        uvm = uv_manager;
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

        Optional<UVNode> max = uvm.getUVNodeList().values().stream().max(Comparator.comparingInt(e -> e.getChannelGraph().getNodeCount()));
        return max.orElse(null);
    }
    public UVNode getMinGraphSizeNode() {

        Optional<UVNode> max = uvm.getUVNodeList().values().stream().min(Comparator.comparingInt(e -> e.getChannelGraph().getNodeCount()));
        return max.orElse(null);
    }

    public double getAverageGraphSize() {
        return uvm.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getNodeCount()).average().getAsDouble();
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

// DoubleStream Variables
        System.out.println("Calculating node count...");
        DoubleStream graphNodeStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getChannelGraph().getNodeCount());
        System.out.println("Calculating channel graph sizes... ");
        DoubleStream graphChannelStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getChannelGraph().getChannelCount());
        System.out.println("Calculating node channels count... ");
        DoubleStream channelNumberStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getLNChannelList().size());
        System.out.println("Calculating node capacities... ");
        DoubleStream nodeCapacityStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getNodeCapacity());
        System.out.println("Calculating local balances... ");
        DoubleStream lightingBalanceStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getLocalBalance());
        System.out.println("Calculating outbound fraction... ");
        DoubleStream outboundFractionStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getOverallOutboundFraction());
        System.out.println("Calculating node node invoice count... ");
        DoubleStream generatedInvoicesStream = uvm.getUVNodeList().values().stream().mapToDouble(e -> e.getGeneratedInvoices().size());

        System.out.println("Generating stats...");
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

        var s = new StringBuilder("\nhash,sender,dest,amt,tot_paths,cand_paths,no_policy,fail_cap,miss_out_liquidity,exceed_fees,attempts,temp_failures,expiry,success");

            for (UVNode node: uvm.getUVNodeList().values()) {
                if (!node.getInvoiceReports().isEmpty()) {
                    for (InvoiceReport report: node.getInvoiceReports()) {
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
                                boolean htlc_routing_success) implements Serializable {

        @Override
        public String toString() {
            return hash + ',' + sender + ',' + dest + ',' +amt+","+ total_paths + "," + candidate_paths + "," +miss_policy + ","+miss_capacity +
                    "," + miss_local_liquidity + "," + miss_fees + "," + attempted_paths + "," + temporary_channel_failures +
                    "," + expiry_too_soon + "," + htlc_routing_success;
        }

    }
}
