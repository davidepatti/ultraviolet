import misc.UVConfig;
import network.UVNetwork;
import network.UVNode;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import protocol.LNInvoice;
import stats.ReportExporter;
import topology.PathFinder;
import topology.PathFinderFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;

public class DseExperimentRunner {
    private final Path configPath;
    private final Path experimentPath;
    private final Path outputDir;
    private JSONObject runReport;

    public DseExperimentRunner(Path configPath, Path experimentPath, Path outputDir) {
        this.configPath = configPath;
        this.experimentPath = experimentPath;
        this.outputDir = outputDir;
    }

    public static void main(String[] args) {
        if (args.length != 3) {
            System.err.println("Usage: DseExperimentRunner <config.properties> <experiment.json> <output-dir>");
            System.exit(2);
        }

        int exitCode = 1;
        try {
            exitCode = new DseExperimentRunner(
                    Path.of(args[0]).toAbsolutePath().normalize(),
                    Path.of(args[1]).toAbsolutePath().normalize(),
                    Path.of(args[2]).toAbsolutePath().normalize()
            ).run();
        } catch (Throwable t) {
            t.printStackTrace(System.err);
        }
        System.exit(exitCode);
    }

    private int run() throws Exception {
        Files.createDirectories(outputDir);

        JSONObject experiment = readJsonObject(experimentPath);
        String experimentName = getString(experiment, "name", "experiment");
        JSONArray commands = getArray(experiment, "commands");
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("Experiment '" + experimentName + "' has no commands");
        }

        UVNetwork.Log = ignored -> { };
        UVNetwork network = null;
        Instant start = Instant.now();

        runReport = new JSONObject();
        runReport.put("status", "running");
        runReport.put("experiment_name", experimentName);
        runReport.put("config", configPath.toString());
        runReport.put("experiment_spec", experimentPath.toString());
        runReport.put("output_dir", outputDir.toString());
        runReport.put("started_at", start.toString());
        runReport.put("commands", new JSONArray());
        runReport.put("reports", new JSONArray());

        try {
            network = new UVNetwork(new UVConfig(configPath.toString()));
            JSONArray commandResults = new JSONArray();
            runReport.put("commands", commandResults);

            for (Object rawCommand : commands) {
                JSONObject command = normalizeCommand(rawCommand);
                commandResults.add(executeCommand(network, command));
                writeRunReport();
            }

            JSONArray reportResults = writeSelectedReports(network, experiment);
            runReport.put("reports", reportResults);
            runReport.put("status", "success");
            runReport.put("ended_at", Instant.now().toString());
            runReport.put("duration_ms", Duration.between(start, Instant.now()).toMillis());
            writeRunReport();
            return 0;
        } catch (Throwable t) {
            runReport.put("status", "failed");
            runReport.put("error", t.toString());
            runReport.put("ended_at", Instant.now().toString());
            runReport.put("duration_ms", Duration.between(start, Instant.now()).toMillis());
            writeRunReport();
            throw t;
        } finally {
            if (network != null) {
                network.shutdown();
            }
        }
    }

    private JSONObject executeCommand(UVNetwork network, JSONObject command) {
        String commandName = getCommandName(command);
        Instant start = Instant.now();
        JSONObject result = new JSONObject();
        result.put("command", commandName);
        result.put("started_at", start.toString());

        try {
            switch (commandName) {
                case "boot" -> executeBoot(network, command, result);
                case "bal" -> executeBal(network, command, result);
                case "rndbal" -> executeRndBal(network, command, result);
                case "path" -> executePath(network, command, result);
                case "route" -> executeRoute(network, command, result);
                case "inv" -> executeInv(network, command, result);
                default -> throw new IllegalArgumentException("Unsupported DSE command: " + commandName);
            }
            result.put("status", "success");
        } catch (RuntimeException e) {
            result.put("status", "failed");
            result.put("error", e.toString());
            throw e;
        } finally {
            result.put("ended_at", Instant.now().toString());
            result.put("duration_ms", Duration.between(start, Instant.now()).toMillis());
        }

        return result;
    }

    private void executeBoot(UVNetwork network, JSONObject command, JSONObject result) {
        String mode = normalizeBootMode(getString(command, "mode", getString(command, "source", "scratch")));
        result.put("mode", mode);
        if ("load".equals(mode)) {
            executeBootFromSnapshot(network, command, result);
            return;
        }

        if (network.isBootstrapStarted() || network.isBootstrapCompleted()) {
            throw new IllegalStateException("Command 'boot' cannot run after the network has already been bootstrapped.");
        }
        network.bootstrapNetwork();
        result.put("nodes", network.getUVNodeList().size());
        result.put("channels", countUniqueChannels(network));
        result.put("current_block", network.getTimechain().getCurrentBlockHeight());
    }

    private void executeBootFromSnapshot(UVNetwork network, JSONObject command, JSONObject result) {
        if (network.isBootstrapStarted() || network.isBootstrapCompleted()) {
            throw new IllegalStateException("Command 'boot' cannot load after the network has already been initialized.");
        }

        String rawFile = getString(command, "file", getString(command, "path", getString(command, "snapshot", ""))).trim();
        if (rawFile.isBlank()) {
            throw new IllegalArgumentException("Command 'boot' with mode=load requires a snapshot file.");
        }
        Path snapshot = resolveInputPath(rawFile);
        if (!network.loadStatus(snapshot.toString())) {
            String detail = network.getLastLoadStatusError();
            throw new IllegalArgumentException(detail == null || detail.isBlank() ? "Could not load " + snapshot : detail);
        }

        result.put("file", snapshot.toString());
        result.put("nodes", network.getUVNodeList().size());
        result.put("channels", countUniqueChannels(network));
        result.put("current_block", network.getTimechain().getCurrentBlockHeight());
    }

    private void executeBal(UVNetwork network, JSONObject command, JSONObject result) {
        requireBootstrapped(network, "bal");
        double level = getRequiredDouble(command, "level");
        int minDelta = getInt(command, "min_delta", 10_000);
        network.setLocalBalances(level, minDelta);
        result.put("level", level);
        result.put("min_delta", minDelta);
    }

    private void executeRndBal(UVNetwork network, JSONObject command, JSONObject result) {
        requireBootstrapped(network, "rndbal");
        int minDelta = getInt(command, "min_delta", 10_000);
        network.setRandomLiquidity(minDelta);
        result.put("min_delta", minDelta);
    }

    private void executePath(UVNetwork network, JSONObject command, JSONObject result) {
        requireBootstrapped(network, "path");
        String start = getString(command, "start", getString(command, "sender", "pk0"));
        String destination = getString(command, "destination", getString(command, "dest", defaultDestination(network)));
        int amount = getInt(command, "amount", network.getConfig().pathfinding_lnd_default_payment_amount_sat);
        int topk = getInt(command, "topk", 20);
        String strategyChoice = getString(command, "path_finder", "lnd");

        UVNode startNode = requireNode(network, start);
        result.put("start", start);
        result.put("destination", destination);
        result.put("amount", amount);
        result.put("topk", topk);

        JSONArray strategies = new JSONArray();
        if ("all".equalsIgnoreCase(strategyChoice)) {
            for (PathFinderFactory.Strategy strategy : PathFinderFactory.Strategy.values()) {
                strategies.add(runPathFinder(network, startNode, start, destination, amount, topk, strategy));
            }
        } else {
            strategies.add(runPathFinder(network, startNode, start, destination, amount, topk, parsePathFinderStrategy(strategyChoice)));
        }
        result.put("path_finding", strategies);
    }

    private void executeRoute(UVNetwork network, JSONObject command, JSONObject result) {
        requireBootstrapped(network, "route");
        requireTimechainRunning(network, "route");

        String senderId = getString(command, "sender", getString(command, "start", "pk0"));
        String destinationId = getString(command, "destination", getString(command, "dest", defaultDestination(network)));
        int amount = getInt(command, "amount", network.getConfig().pathfinding_lnd_default_payment_amount_sat);
        int maxFees = getInt(command, "max_fees", 1000);
        String message = getString(command, "message", "dse-route-" + senderId + "-" + destinationId + "-" + amount);
        PathFinderFactory.Strategy strategy = parsePathFinderStrategy(getString(command, "path_finder", "lnd"));

        UVNode sender = requireNode(network, senderId);
        UVNode destination = requireNode(network, destinationId);
        applyPathFinderStrategy(network, sender, strategy, amount);

        LNInvoice invoice = destination.generateInvoice(amount, message, true);
        sender.processInvoice(invoice, maxFees, false);

        result.put("sender", senderId);
        result.put("destination", destinationId);
        result.put("amount", amount);
        result.put("max_fees", maxFees);
        result.put("path_finder", strategy.name().toLowerCase(Locale.ROOT));
        result.put("invoice_hash", invoice.getHash());
        result.put("success", sender.getPayedInvoices().containsKey(invoice.getHash()));
    }

    private void executeInv(UVNetwork network, JSONObject command, JSONObject result) {
        requireBootstrapped(network, "inv");
        requireTimechainRunning(network, "inv");

        double nodeEventsPerBlock = getDouble(command, "node_events_per_block", 0.1);
        int blocks = getInt(command, "blocks", getInt(command, "duration_blocks", 500));
        int minAmount = getInt(command, "min_amt", getInt(command, "amount_min", 1000));
        int maxAmount = getInt(command, "max_amt", getInt(command, "amount_max", 1_000_000));
        int maxFees = getInt(command, "max_fees", 1000);
        PathFinderFactory.Strategy strategy = parsePathFinderStrategy(getString(command, "path_finder", "lnd"));

        applyPathFinderStrategyToAllNodes(network, strategy);
        network.generateInvoiceEvents(nodeEventsPerBlock, blocks, minAmount, maxAmount, maxFees);

        result.put("node_events_per_block", nodeEventsPerBlock);
        result.put("blocks", blocks);
        result.put("min_amt", minAmount);
        result.put("max_amt", maxAmount);
        result.put("max_fees", maxFees);
        result.put("path_finder", strategy.name().toLowerCase(Locale.ROOT));
        result.put("invoice_report_rows", countInvoiceReports(network));
    }

    private JSONObject runPathFinder(
            UVNetwork network,
            UVNode startNode,
            String start,
            String destination,
            int amount,
            int topk,
            PathFinderFactory.Strategy strategy
    ) {
        PathFinder pathFinder = PathFinderFactory.of(strategy, network.getConfig());
        pathFinder.setPaymentAmount(amount);
        PathFinder.SearchResult searchResult = pathFinder.findPaths(startNode.getChannelGraph(), start, destination, topk);

        JSONObject strategyResult = new JSONObject();
        strategyResult.put("strategy", strategy.name().toLowerCase(Locale.ROOT));
        strategyResult.put("stats", searchStatsToJson(searchResult.stats()));

        JSONArray paths = new JSONArray();
        for (PathFinder.PathDetails pathDetails : searchResult.paths()) {
            JSONObject pathJson = new JSONObject();
            pathJson.put("path", pathDetails.path().toString());
            pathJson.put("total_cost", pathDetails.totalCost());
            JSONArray components = new JSONArray();
            for (PathFinder.CostComponent component : pathDetails.components()) {
                JSONObject componentJson = new JSONObject();
                componentJson.put("label", component.label());
                componentJson.put("value", component.value());
                components.add(componentJson);
            }
            pathJson.put("components", components);
            paths.add(pathJson);
        }
        strategyResult.put("paths", paths);
        return strategyResult;
    }

    private JSONArray writeSelectedReports(UVNetwork network, JSONObject experiment) throws IOException {
        EnumSet<ReportExporter.ReportType> reportTypes = parseOutputs(experiment);
        Path reportsDir = outputDir.resolve("reports");
        JSONArray reports = new JSONArray();
        for (ReportExporter.WrittenReport report : ReportExporter.writeReports(network, reportsDir, "", "", reportTypes)) {
            JSONObject reportJson = new JSONObject();
            reportJson.put("type", report.type().name().toLowerCase(Locale.ROOT));
            reportJson.put("path", outputDir.relativize(report.path()).toString());
            reports.add(reportJson);
        }
        return reports;
    }

    private EnumSet<ReportExporter.ReportType> parseOutputs(JSONObject experiment) {
        JSONArray outputs = getArray(experiment, "outputs");
        if (outputs == null) {
            outputs = getArray(experiment, "reports");
        }
        EnumSet<ReportExporter.ReportType> reportTypes = EnumSet.noneOf(ReportExporter.ReportType.class);
        if (outputs == null || outputs.isEmpty()) {
            reportTypes.add(ReportExporter.ReportType.NETWORK);
            return reportTypes;
        }

        for (Object output : outputs) {
            ReportExporter.ReportType type = ReportExporter.parseReportType(String.valueOf(output));
            if (type != ReportExporter.ReportType.NETWORK && type != ReportExporter.ReportType.INVOICE) {
                throw new IllegalArgumentException("DSE outputs currently support only network/stat and invoice reports: " + output);
            }
            reportTypes.add(type);
        }
        return reportTypes;
    }

    private JSONObject searchStatsToJson(PathFinder.SearchStats stats) {
        JSONObject json = new JSONObject();
        json.put("investigated_states", stats.investigatedStates());
        json.put("expanded_edges", stats.expandedEdges());
        json.put("excluded_capacity", stats.excludedByCapacity());
        json.put("excluded_visited_state", stats.excludedByVisitedState());
        json.put("excluded_cycle", stats.excludedByCycle());
        json.put("excluded_max_hops", stats.excludedByMaxHops());
        json.put("excluded_cost", stats.excludedByCost());
        json.put("returned_paths", stats.returnedPaths());
        return json;
    }

    private void writeRunReport() throws IOException {
        Files.writeString(outputDir.resolve("run.json"), runReport.toJSONString() + System.lineSeparator());
    }

    private static JSONObject readJsonObject(Path path) throws Exception {
        Object parsed = new JSONParser().parse(Files.newBufferedReader(path));
        return requireObject(parsed, path.toString());
    }

    private static JSONObject normalizeCommand(Object value) {
        if (value instanceof JSONObject object) {
            return object;
        }
        if (value instanceof String commandName && !commandName.isBlank()) {
            JSONObject command = new JSONObject();
            command.put("command", commandName);
            return command;
        }
        throw new IllegalArgumentException("Expected command object or command string");
    }

    private static JSONObject requireObject(Object value, String context) {
        if (value instanceof JSONObject object) {
            return object;
        }
        throw new IllegalArgumentException("Expected JSON object for " + context);
    }

    private static JSONArray getArray(JSONObject object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof JSONArray array) {
            return array;
        }
        throw new IllegalArgumentException("Expected array for key '" + key + "'");
    }

    private String getCommandName(JSONObject command) {
        String value = getString(command, "command", null);
        if (value == null) {
            value = getString(command, "cmd", null);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Command is missing 'command'");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeBootMode(String value) {
        String mode = value == null ? "scratch" : value.trim().toLowerCase(Locale.ROOT);
        if (mode.isEmpty() || "scratch".equals(mode) || "bootstrap".equals(mode) || "generate".equals(mode)) {
            return "scratch";
        }
        if ("load".equals(mode) || "snapshot".equals(mode) || "dat".equals(mode)) {
            return "load";
        }
        throw new IllegalArgumentException("Unsupported boot mode: " + value);
    }

    private Path resolveInputPath(String value) {
        Path path = Path.of(value);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        Path base = configPath.getParent() == null ? Path.of(".").toAbsolutePath() : configPath.getParent();
        return base.resolve(path).toAbsolutePath().normalize();
    }

    private static String getString(JSONObject object, String key, String defaultValue) {
        Object value = object.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int getInt(JSONObject object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static double getDouble(JSONObject object, String key, double defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static double getRequiredDouble(JSONObject object, String key) {
        if (!object.containsKey(key)) {
            throw new IllegalArgumentException("Missing required numeric key: " + key);
        }
        return getDouble(object, key, 0.0);
    }

    private static PathFinderFactory.Strategy parsePathFinderStrategy(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lnd" -> PathFinderFactory.Strategy.LND;
            case "mini_dijkstra", "mini", "dijkstra" -> PathFinderFactory.Strategy.MINI_DIJKSTRA;
            case "shortest_hop", "shortest", "hop" -> PathFinderFactory.Strategy.SHORTEST_HOP;
            case "bfs" -> PathFinderFactory.Strategy.BFS;
            default -> throw new IllegalArgumentException("Unknown path finder: " + value);
        };
    }

    private static void applyPathFinderStrategy(UVNetwork network, UVNode node, PathFinderFactory.Strategy strategy, int amount) {
        PathFinder pathFinder = PathFinderFactory.of(strategy, network.getConfig());
        pathFinder.setPaymentAmount(amount);
        node.setPathFinder(pathFinder);
    }

    private static void applyPathFinderStrategyToAllNodes(UVNetwork network, PathFinderFactory.Strategy strategy) {
        for (UVNode node : network.getUVNodeList().values()) {
            node.setPathFinder(PathFinderFactory.of(strategy, network.getConfig()));
        }
    }

    private static UVNode requireNode(UVNetwork network, String pubkey) {
        UVNode node = network.getUVNode(pubkey);
        if (node == null) {
            throw new IllegalArgumentException("Unknown node: " + pubkey);
        }
        return node;
    }

    private static void requireBootstrapped(UVNetwork network, String command) {
        if (!network.isBootstrapCompleted()) {
            throw new IllegalStateException("Command '" + command + "' requires a bootstrapped network. Add a boot command first.");
        }
    }

    private static void requireTimechainRunning(UVNetwork network, String command) {
        if (!network.getTimechainStatus()) {
            throw new IllegalStateException("Command '" + command + "' requires the timechain to be running.");
        }
    }

    private static String defaultDestination(UVNetwork network) {
        int n = Math.max(0, network.getConfig().bootstrap_nodes - 1);
        return "pk" + n;
    }

    private static int countUniqueChannels(UVNetwork network) {
        return network.getUVNodeList().values().stream()
                .mapToInt(node -> node.getChannels().size())
                .sum() / 2;
    }

    private static int countInvoiceReports(UVNetwork network) {
        return network.getUVNodeList().values().stream()
                .mapToInt(node -> node.getInvoiceReports().size())
                .sum();
    }
}
