import misc.UVConfig;
import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.UVNetwork;
import network.UVNode;
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
    private JsonObject runReport;

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

        JsonObject experiment = readJsonObject(experimentPath);
        String experimentName = getString(experiment, "name", "experiment");
        JsonArray commands = getArray(experiment, "commands");
        if (commands == null || commands.isEmpty()) {
            throw new IllegalArgumentException("Experiment '" + experimentName + "' has no commands");
        }

        UVNetwork.Log = ignored -> { };
        UVNetwork network = null;
        Instant start = Instant.now();

        runReport = new JsonObject();
        runReport.put("status", "running");
        runReport.put("experiment_name", experimentName);
        runReport.put("config", configPath.toString());
        runReport.put("experiment_spec", experimentPath.toString());
        runReport.put("output_dir", outputDir.toString());
        runReport.put("started_at", start.toString());
        runReport.put("commands", new JsonArray());
        runReport.put("reports", new JsonArray());

        try {
            network = new UVNetwork(new UVConfig(configPath.toString()));
            JsonArray commandResults = new JsonArray();
            runReport.put("commands", commandResults);

            for (Object rawCommand : commands) {
                JsonObject command = normalizeCommand(rawCommand);
                commandResults.add(executeCommand(network, command));
                writeRunReport();
            }

            JsonArray reportResults = writeSelectedReports(network, experiment);
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

    private JsonObject executeCommand(UVNetwork network, JsonObject command) {
        String commandName = getCommandName(command);
        Instant start = Instant.now();
        JsonObject result = new JsonObject();
        result.put("command", commandName);
        result.put("started_at", start.toString());

        try {
            switch (commandName) {
                case "boot" -> executeBoot(network, result);
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

    private void executeBoot(UVNetwork network, JsonObject result) {
        if (network.isBootstrapStarted() || network.isBootstrapCompleted()) {
            throw new IllegalStateException("Command 'boot' cannot run after the network has already been bootstrapped.");
        }
        network.bootstrapNetwork();
        result.put("nodes", network.getUVNodeList().size());
        result.put("channels", countUniqueChannels(network));
        result.put("current_block", network.getTimechain().getCurrentBlockHeight());
    }

    private void executeBal(UVNetwork network, JsonObject command, JsonObject result) {
        requireBootstrapped(network, "bal");
        double level = getRequiredDouble(command, "level");
        int minDelta = getInt(command, "min_delta", 10_000);
        network.setLocalBalances(level, minDelta);
        result.put("level", level);
        result.put("min_delta", minDelta);
    }

    private void executeRndBal(UVNetwork network, JsonObject command, JsonObject result) {
        requireBootstrapped(network, "rndbal");
        int minDelta = getInt(command, "min_delta", 10_000);
        network.setRandomLiquidity(minDelta);
        result.put("min_delta", minDelta);
    }

    private void executePath(UVNetwork network, JsonObject command, JsonObject result) {
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

        JsonArray strategies = new JsonArray();
        if ("all".equalsIgnoreCase(strategyChoice)) {
            for (PathFinderFactory.Strategy strategy : PathFinderFactory.Strategy.values()) {
                strategies.add(runPathFinder(network, startNode, start, destination, amount, topk, strategy));
            }
        } else {
            strategies.add(runPathFinder(network, startNode, start, destination, amount, topk, parsePathFinderStrategy(strategyChoice)));
        }
        result.put("path_finding", strategies);
    }

    private void executeRoute(UVNetwork network, JsonObject command, JsonObject result) {
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

    private void executeInv(UVNetwork network, JsonObject command, JsonObject result) {
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

    private JsonObject runPathFinder(
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

        JsonObject strategyResult = new JsonObject();
        strategyResult.put("strategy", strategy.name().toLowerCase(Locale.ROOT));
        strategyResult.put("stats", searchStatsToJson(searchResult.stats()));

        JsonArray paths = new JsonArray();
        for (PathFinder.PathDetails pathDetails : searchResult.paths()) {
            JsonObject pathJson = new JsonObject();
            pathJson.put("path", pathDetails.path().toString());
            pathJson.put("total_cost", pathDetails.totalCost());
            JsonArray components = new JsonArray();
            for (PathFinder.CostComponent component : pathDetails.components()) {
                JsonObject componentJson = new JsonObject();
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

    private JsonArray writeSelectedReports(UVNetwork network, JsonObject experiment) throws IOException {
        EnumSet<ReportExporter.ReportType> reportTypes = parseOutputs(experiment);
        Path reportsDir = outputDir.resolve("reports");
        JsonArray reports = new JsonArray();
        for (ReportExporter.WrittenReport report : ReportExporter.writeReports(network, reportsDir, "", "", reportTypes)) {
            JsonObject reportJson = new JsonObject();
            reportJson.put("type", report.type().name().toLowerCase(Locale.ROOT));
            reportJson.put("path", outputDir.relativize(report.path()).toString());
            reports.add(reportJson);
        }
        return reports;
    }

    private EnumSet<ReportExporter.ReportType> parseOutputs(JsonObject experiment) {
        JsonArray outputs = getArray(experiment, "outputs");
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

    private JsonObject searchStatsToJson(PathFinder.SearchStats stats) {
        JsonObject json = new JsonObject();
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
        Files.writeString(outputDir.resolve("run.json"), runReport.toJsonString() + System.lineSeparator());
    }

    private static JsonObject readJsonObject(Path path) throws Exception {
        Object parsed = Json.parse(Files.newBufferedReader(path));
        return requireObject(parsed, path.toString());
    }

    private static JsonObject normalizeCommand(Object value) {
        if (value instanceof JsonObject object) {
            return object;
        }
        if (value instanceof String commandName && !commandName.isBlank()) {
            JsonObject command = new JsonObject();
            command.put("command", commandName);
            return command;
        }
        throw new IllegalArgumentException("Expected command object or command string");
    }

    private static JsonObject requireObject(Object value, String context) {
        if (value instanceof JsonObject object) {
            return object;
        }
        throw new IllegalArgumentException("Expected JSON object for " + context);
    }

    private static JsonArray getArray(JsonObject object, String key) {
        Object value = object.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof JsonArray array) {
            return array;
        }
        throw new IllegalArgumentException("Expected array for key '" + key + "'");
    }

    private String getCommandName(JsonObject command) {
        String value = getString(command, "command", null);
        if (value == null) {
            value = getString(command, "cmd", null);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Command is missing 'command'");
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String getString(JsonObject object, String key, String defaultValue) {
        Object value = object.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }

    private static int getInt(JsonObject object, String key, int defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(String.valueOf(value));
    }

    private static double getDouble(JsonObject object, String key, double defaultValue) {
        Object value = object.get(key);
        if (value == null) {
            return defaultValue;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(String.valueOf(value));
    }

    private static double getRequiredDouble(JsonObject object, String key) {
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
