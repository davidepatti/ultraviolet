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

        var node_count =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getNodeCount()).summaryStatistics();
        var channel_count =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getChannelGraph().getChannelCount()).summaryStatistics();
        var onchain_balance =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getOnChainBalance()).summaryStatistics();
        var onchain_pending =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getOnchainPending()).summaryStatistics();
        var channel_list_size =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getLNChannelList().size()).summaryStatistics();
        var lighting_balance =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getLightningBalance()).summaryStatistics();
        var generated_invoices =  uvm.getUVNodeList().values().stream().mapToDouble(e->e.getGeneratedInvoices().size()).summaryStatistics();

        s.append("\nNode count:\t").append(node_count);
        s.append("\nChannel count:\t").append(channel_count);
        s.append("\nOchain balance:\t").append(onchain_balance);
        s.append("\nOchain pending:\t").append(onchain_pending);
        s.append("\nChannel list size:\t").append(channel_list_size);
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
