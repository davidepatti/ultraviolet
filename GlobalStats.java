import java.io.*;
import java.util.Comparator;
import java.util.Optional;

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


    public String generateReport() {
        StringBuilder s = new StringBuilder("Statistical Resport");

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

    public void writeReport(String filename) {
        String report = generateReport();
        try (PrintWriter os = new PrintWriter(filename)){
            os.write(report);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
