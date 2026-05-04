package stats;

import network.UVChannel;
import network.UVNetwork;
import network.UVNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class ReportExporter {
    public enum ReportType {
        NETWORK("network", ".csv"),
        INVOICE("invoice", ".csv"),
        NODES("nodes", ".csv"),
        ALL("all", ".txt");

        private final String stem;
        private final String extension;

        ReportType(String stem, String extension) {
            this.stem = stem;
            this.extension = extension;
        }
    }

    public record WrittenReport(ReportType type, Path path) {}

    private ReportExporter() {}

    public static String timestampNow() {
        return new SimpleDateFormat("yyyyMMddHHmm").format(new Date());
    }

    public static List<WrittenReport> writeTimestampedReports(UVNetwork network, Path outputDir, String prefix) throws IOException {
        return writeReports(network, outputDir, prefix, timestampNow(), EnumSet.allOf(ReportType.class));
    }

    public static List<WrittenReport> writeReports(
            UVNetwork network,
            Path outputDir,
            String prefix,
            String timestamp,
            Set<ReportType> reportTypes
    ) throws IOException {
        Files.createDirectories(outputDir);

        ArrayList<WrittenReport> writtenReports = new ArrayList<>();
        for (ReportType type : reportTypes) {
            Path path = outputDir.resolve(buildFileName(prefix, timestamp, type));
            Files.writeString(path, generateReport(network, type));
            writtenReports.add(new WrittenReport(type, path));
        }
        return writtenReports;
    }

    public static String generateReport(UVNetwork network, ReportType type) {
        return switch (type) {
            case NETWORK -> network.getStats().generateNetworkReport();
            case INVOICE -> network.getStats().generateInvoiceReport();
            case NODES -> generateNodesReport(network);
            case ALL -> generateAllReport(network);
        };
    }

    private static String buildFileName(String prefix, String timestamp, ReportType type) {
        String cleanPrefix = prefix == null ? "" : prefix.trim();
        String cleanTimestamp = timestamp == null ? "" : timestamp.trim();

        if (!cleanTimestamp.isEmpty()) {
            if (cleanPrefix.isEmpty()) {
                return type.stem + "." + cleanTimestamp + type.extension;
            }
            return cleanPrefix + "_" + type.stem + "." + cleanTimestamp + type.extension;
        }

        if (cleanPrefix.isEmpty()) {
            return type.stem + type.extension;
        }
        return cleanPrefix + "_" + type.stem + type.extension;
    }

    private static String generateNodesReport(UVNetwork network) {
        StringBuilder report = new StringBuilder(GlobalStats.NodeStats.generateStatsHeader()).append('\n');
        for (UVNode node : network.getSortedNodeListByPubkey()) {
            report.append(node.getNodeStats().generateStatsCSV(node)).append('\n');
        }
        return report.toString();
    }

    private static String generateAllReport(UVNetwork network) {
        StringBuilder report = new StringBuilder();
        for (UVNode node : network.getSortedNodeListByPubkey()) {
            report.append("--------------------------------------------\n");
            report.append(UVNode.generateNodeLabelString()).append('\n');
            report.append(node).append('\n');
            report.append(UVChannel.generateLabels()).append('\n');
            node.getChannels().values().stream()
                    .sorted(Comparator.comparing(UVChannel::getChannelId))
                    .forEach(channel -> report.append(channel).append('\n'));
        }
        return report.toString();
    }

    public static ReportType parseReportType(String value) {
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "network", "stat", "stats" -> ReportType.NETWORK;
            case "invoice", "invoices", "invoice_report" -> ReportType.INVOICE;
            case "nodes", "node" -> ReportType.NODES;
            case "all" -> ReportType.ALL;
            default -> throw new IllegalArgumentException("Unknown report type: " + value);
        };
    }
}
