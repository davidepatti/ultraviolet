import java.util.*;

@SuppressWarnings("OptionalGetWithoutIsPresent")
public class GlobalStats {

    private final UVNetworkManager uvm;

    public GlobalStats(UVNetworkManager uv_manager) {
        uvm = uv_manager;
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

    public String generateNetworkReport() {
        StringBuilder s = new StringBuilder("Network Resport");

        var graph_node_count =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getNodeCount()).summaryStatistics();
        var graph_channel_count =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getChannelCount()).summaryStatistics();
        var onchain_balance =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getOnChainBalance()).summaryStatistics();
        var channel_number =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getLNChannelList().size()).summaryStatistics();
        var lighting_balance =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getLightningBalance()).summaryStatistics();
        var generated_invoices =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getGeneratedInvoices().size()).summaryStatistics();

        s.append("\nGraph Nodes:\t").append(graph_node_count);
        s.append("\nGraph Channels:\t").append(graph_channel_count);
        s.append("\nOchain balance:\t").append(onchain_balance);
        s.append("\nnchannels:\t").append(channel_number);
        s.append("\nLightning balance:\t").append(lighting_balance);
        s.append("\nGenerated invoices:\t").append(generated_invoices);

        return s.toString();
    }

    public String generateInvoiceReport() {

        var s = new StringBuilder("hash,sender,dest,amount,total_paths,candidate_paths,fail_capacity, miss_out_liquidity, exceed_fees, attempted, failed_htlc,success_htlc");

            for (UVNode node: uvm.getUVNodeList().values()) {
                if (node.getInvoiceReports().size()>0) {
                    for (UVNode.InvoiceReport report: node.getInvoiceReports()) {
                        s.append("\n"+report);
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
}
