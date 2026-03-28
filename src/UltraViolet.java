import misc.UVConfig;
import network.*;
import stats.*;
import topology.*;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.DoubleFunction;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;


public class UltraViolet {
    private static final String MENU_SEPARATOR = "__________________________________________________";
    private static final int MENU_KEY_WIDTH = 8;
    private static final String CONFIG_DIRECTORY = "uv_configs";
    private static final String DEFAULT_CONFIG_FILE = "test.properties";

    private UVNetwork networkManager;
    boolean quit = false;
    private String imported_graph_root;
    private static final int LOOP_SLEEP_TIME = 500;
    private final Scanner menuInputScanner;
    private final TerminalStyle ui;
    private final Object consoleOutputLock = new Object();
    private String selectedConfigPath;
    private String lastBootstrapProgressLine;
    private int bootstrapProgressWidth;
    private boolean bootstrapProgressVisible;

    private static final class TerminalStyle {
        private static final String RESET = "\033[0m";
        private static final String BOLD = "\033[1m";
        private static final String DIM = "\033[2m";
        private static final String BLUE = "\033[34m";
        private static final String CYAN = "\033[36m";
        private static final String GREEN = "\033[32m";
        private static final String MAGENTA = "\033[35m";
        private static final String YELLOW = "\033[33m";
        private final boolean enabled;

        private TerminalStyle(boolean enabled) {
            this.enabled = enabled;
        }

        static TerminalStyle detect() {
            if (System.getenv("NO_COLOR") != null) {
                return new TerminalStyle(false);
            }

            String mode = System.getenv("UV_COLOR");
            if (mode != null) {
                if ("always".equalsIgnoreCase(mode)) return new TerminalStyle(true);
                if ("never".equalsIgnoreCase(mode)) return new TerminalStyle(false);
            }

            String term = System.getenv("TERM");
            boolean supportsAnsi = term != null && !term.isBlank() && !"dumb".equalsIgnoreCase(term);
            return new TerminalStyle(supportsAnsi && System.console() != null);
        }

        private String apply(String prefix, String text) {
            return enabled ? prefix + text + RESET : text;
        }

        String separator() {
            return apply(DIM, MENU_SEPARATOR);
        }

        String title(String text) {
            return apply(BOLD + CYAN, text);
        }

        String key(String text) {
            return apply(BOLD + CYAN, text);
        }

        String label(String text) {
            return apply(BOLD, text);
        }

        String running(String text) {
            return apply(BOLD + GREEN, text);
        }

        String stopped(String text) {
            return apply(BOLD + YELLOW, text);
        }

        String hint(String text) {
            return apply(DIM, text);
        }

        String section(String text) {
            return apply(BOLD + BLUE, text);
        }

        String value(String text) {
            return apply(BOLD + GREEN, text);
        }

        String accent(String text) {
            return apply(BOLD + YELLOW, text);
        }

        String detail(String text) {
            return apply(MAGENTA, text);
        }
    }


    private static class MenuItem {
        public final String key;
        private final Supplier<String> descriptionSupplier;

        public final Consumer<Void> func;

        public MenuItem() {
            key = "";
            descriptionSupplier = () -> "";
            func = __ -> {};
        }

        public MenuItem(String key, String desc, Consumer<Void> func) {
            this(key, () -> desc, func);
        }

        public MenuItem(String key, Supplier<String> descriptionSupplier, Consumer<Void> func) {
            this.key = key;
            this.descriptionSupplier = descriptionSupplier;
            this.func = func;
        }

        public String render(TerminalStyle ui) {
            if (key.isEmpty()) {
                return ui.separator();
            }
            return ui.key(padRight(key, MENU_KEY_WIDTH)) + "- " + descriptionSupplier.get();
        }
    }

    private record ChannelDisplayRow(
            String channelId,
            String peerId,
            String capacity,
            String outbound,
            String inbound,
            String selfFees,
            String peerFees
    ) {}

    private record NodeOverviewRow(
            String pubkey,
            String alias,
            String capacity,
            String channels,
            String onChain,
            String local,
            String remote,
            String outbound,
            String profile
    ) {}

    private record GraphDisplayRow(
            String source,
            String destination,
            String channelId,
            String capacity,
            String policy
    ) {}

    private record NetworkStatRow(
            String label,
            String min,
            String max,
            String average,
            String stdDev,
            String firstQuartile,
            String median,
            String thirdQuartile
    ) {}

    private record InvoiceDisplayRow(
            String hash,
            String sender,
            String dest,
            String amount,
            String paths,
            String candidates,
            String attempts,
            String missPolicy,
            String missCapacity,
            String missLiquidity,
            String missFees,
            String temporaryFailures,
            String expirySoon,
            String success
    ) {}


    public UltraViolet(String configPath) {
        this.ui = TerminalStyle.detect();
        UVNetwork.Log = this::printNetworkLogLine;
        ArrayList<MenuItem> menuItems = new ArrayList<>();
        menuInputScanner = new Scanner(System.in);
        networkManager = createNetworkManager(configPath);

        menuItems.add(new MenuItem(
                "cfg",
                () -> "Select Config [" + new File(getSelectedConfigPath()).getName() + "]",
                this::selectPropertiesConfig
        ));
        menuItems.add(new MenuItem("boot", "Bootstrap LN from scratch", this::bootstrapNetwork));
        menuItems.add(new MenuItem("t", "Start/Stop Timechain and P2P messages", this::handleTimeChainAndP2PMessages));
        menuItems.add(new MenuItem("path", "Find paths between nodes", x -> findPathsCmd()));
        menuItems.add(new MenuItem("route", "Route Invoice", x -> testInvoiceRoutingCmd()));
        menuItems.add(new MenuItem("inv", "Generate Invoice Events", this::generateInvoiceEventsMethod));
        menuItems.add(new MenuItem("bal", "Set Local Channels balances", this::setLocalChannelsBalances));
        menuItems.add(new MenuItem("rndbal", "Set Random Channels balances", this::setRandomChannelsBalances));
        menuItems.add(new MenuItem());
        menuItems.add(new MenuItem("all", "Show Nodes and Channels", this::showNodesAndChannels));
        menuItems.add(new MenuItem("net", "Show All Nodes ", this::showAllNodes));
        menuItems.add(new MenuItem("node", "Show Node ", this::showNode));
        menuItems.add(new MenuItem("graph", "Show Node Graph", this::showNodeGraph));
        menuItems.add(new MenuItem("qs", "Show Queues Status", this::showQueuesStatus));
        menuItems.add(new MenuItem("rep", "Show Invoice Reports", this::invoiceReportsMethod));
        menuItems.add(new MenuItem("stat", "Show Network Stats", this::showNetworkStatsMethod));
        //menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> { networkManager.resetUVM(); }));
        //menuItems.add(new MenuItem("free", "Try to free memory", x -> { System.gc(); }));

        menuItems.add(new MenuItem());
        menuItems.add(new MenuItem("save", "Save UV Network Status", this::saveUVNetworkStatus));
        menuItems.add(new MenuItem("load", "Load UV Network Status", this::loadUVNetworkStatus));
        menuItems.add(new MenuItem("wr", "Write Reports", this::writeReports));
        menuItems.add(new MenuItem("import", "Import Topology Graph", this::myMethod));
        //menuItems.add(new MenuItem("purge", "Purge graph null channels", this::purgeGraph));
        menuItems.add(new MenuItem());
        menuItems.add(new MenuItem("q", "Quit ", this::quitMenu));

        try {
            while (!quit) {
                System.out.print("\033[H\033[2J");
                System.out.flush();
                System.out.println(ui.separator());
                System.out.println(ui.title(" U l t r a v i o l e t "));
                System.out.println(ui.separator());
                menuItems.forEach(item -> System.out.println(item.render(ui)));
                System.out.println(ui.separator());
                System.out.print(ui.label("Timechain: ") + networkManager.getTimechain().getCurrentBlockHeight());
                if (!networkManager.getTimechain().getStatus()) {
                    System.out.println(" " + ui.stopped("(NOT RUNNING)"));
                } else {
                    System.out.println(" " + ui.running("Running..."));
                }

                System.out.print("\n" + ui.label(" -> "));
                var ch = menuInputScanner.nextLine();

                for (MenuItem item : menuItems) {
                    if (item.key.equals(ch)) {
                        item.func.accept(null);
                        break;
                    }
                }
                System.out.println("\n" + ui.hint("[ Press ENTER to continue... ]"));
                menuInputScanner.nextLine();
            }
        } finally {
            networkManager.shutdown();
        }
        System.out.println("Exiting...");
        System.exit(0);
    }

    // menu implementations ////////////////////////////////////////

    void bootstrapNetwork(Object x) {
        var config = networkManager.getConfig();
        if (networkManager.isBootstrapStarted() || networkManager.isBootstrapCompleted()) {
            System.out.println("ERROR: network already bootstrapped!");
            return;
        }
        System.out.println("Bootstrap started, check " + config.logfile + " for details...");
        var bootstrapExec = Executors.newSingleThreadExecutor();
        Future<?> bootstrapOutcome = bootstrapExec.submit(networkManager::bootstrapNetwork);
        System.out.println("waiting bootstrap to finish...");
        resetBootstrapProgressOutput();
        while (!bootstrapOutcome.isDone()) {
            renderBootstrapProgressLine(formatBootstrapProgressLine(config.bootstrap_nodes));
            try {
                Thread.sleep(LOOP_SLEEP_TIME);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        finishBootstrapProgressOutput();
        bootstrapExec.shutdown();
        System.out.println("Done!");
    }
    private void myMethod(Object x) {
        System.out.println("A graph topology will be imported using the json output of 'lncli describegraph' command on some root node");
        showAvailableFiles(".json");
        System.out.print("Enter a JSON file: ");
        String json = menuInputScanner.nextLine();
        System.out.print("Enter root node pubkey:");
        String root = menuInputScanner.nextLine();
        imported_graph_root = root;
        new Thread(()-> networkManager.importTopology(json,root)).start();
    }

    private void handleTimeChainAndP2PMessages(Object x) {
        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("ERROR: must execute bootstrap or load/import a network!");
            return;
        }
        if (!networkManager.getTimechainStatus()) {
            System.out.println("Starting Timechain, check " + networkManager.getConfig().logfile);
            networkManager.setTimechainStatus(true);
            networkManager.startP2PNetwork();
        } else {
            System.out.print("Waiting for Timechain services to stop...");
            networkManager.setTimechainStatus(false);
            networkManager.stopP2PNetwork();
        }
    }

    private void showAllNodes(Void unused) {
        if (networkManager.getUVNodeList().isEmpty()) {
            System.out.println("EMPTY NODE LIST");
            return;
        }
        printNodeOverviewTable(" Network Nodes ", networkManager.getSortedNodeListByPubkey());
    }

    public void showNodesAndChannels(Object x) {
        if (networkManager.getUVNodeList().isEmpty()) {
            System.out.println("EMPTY NODE LIST");
            return;
        }
        var nodes = networkManager.getSortedNodeListByPubkey();
        System.out.println(ui.separator());
        System.out.println(ui.title(" Nodes And Channels "));
        printNodeCollectionSummary(nodes);
        int nodeAmountWidth = computeNodeAmountWidth(nodes);
        for (UVNode node : nodes) {
            printNodeAndChannels(node, nodeAmountWidth);
        }
        System.out.println(ui.separator());
    }

    private void showNode(Object x) {
        System.out.print("insert node public key:");
        String node = menuInputScanner.nextLine();
        showNodeCommand(node);
    }

    private void purgeGraph(Object x) {
        int purged;
        for (UVNode node : networkManager.getUVNodeList().values()) {
            purged = node.getChannelGraph().purgeNullPolicyChannels();
            if (purged > 0) System.out.println("Pruned " + purged + " null policy nodes on " + node.getPubKey());
        }
    }

    private void showNodeGraph(Object x) {
        System.out.print("Insert node public key:");
        String node = menuInputScanner.nextLine();
        showGraphCommand(node);
    }

    private void showQueuesStatus(Object x) {
        if (!networkManager.isBootstrapCompleted()) return;
        System.out.println("Showing not empty queues...");
        for (UVNode n : networkManager.getUVNodeList().values()) {
            n.showQueuesStatus();
        }
    }

    private void writeReports(Object x) {

        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("Bootstrap not completed!");
            return;
        }

        var networkReport = networkManager.getStats().generateNetworkReport();
        var invoiceReport = networkManager.getStats().generateInvoiceReport();

        String labels = GlobalStats.NodeStats.generateStatsHeader();
        StringBuilder r = new StringBuilder(labels).append('\n');
        for (var n : networkManager.getSortedNodeListByPubkey()) {
            r.append(n.getNodeStats().generateStatsCSV(n)).append("\n");
        }
        var csvReport = r.toString();
        System.out.print("Enter description prefix:");
        var prefix = menuInputScanner.nextLine();
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
        try {
            // For network report
            var filenameNetwork = new StringBuilder(prefix).append("_network.").append(sdf.format(new Date())).append(".csv");
            var fwNetwork = new FileWriter(filenameNetwork.toString());
            fwNetwork.write(networkReport);
            fwNetwork.close();
            System.out.println("Written " + filenameNetwork);

            // For invoice report
            var filenameInvoice = new StringBuilder(prefix).append("_invoice.").append(sdf.format(new Date())).append(".csv");
            var fwInvoice = new FileWriter(filenameInvoice.toString());
            fwInvoice.write(invoiceReport);
            fwInvoice.close();
            System.out.println("Written " + filenameInvoice);

            // For csv report
            var filenameCsv = new StringBuilder(prefix).append("_nodes.").append(sdf.format(new Date())).append(".csv");
            var fwCsv = new FileWriter(filenameCsv.toString());
            fwCsv.write(csvReport);
            fwCsv.close();
            System.out.println("Written " + filenameCsv);

            // For "all" report, similar to showNodesAndChannels
            var filenameAll = new StringBuilder(prefix).append("_all.").append(sdf.format(new Date())).append(".txt");
            StringBuilder allReport = new StringBuilder();
            var ln = networkManager.getUVNodeList().values().stream().sorted().toList();
            for (UVNode n : ln) {
                allReport.append("--------------------------------------------\n");
                allReport.append(UVNode.generateNodeLabelString()).append('\n');
                allReport.append(n.toString()).append('\n');
                allReport.append(UVChannel.generateLabels()).append('\n');
                n.getChannels().values().forEach(c -> allReport.append(c).append('\n'));
            }
            var fwAll = new FileWriter(filenameAll.toString());
            fwAll.write(allReport.toString());
            fwAll.close();
            System.out.println("Written " + filenameAll);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    // Method for "Generate Invoice Events"
    public void generateInvoiceEventsMethod(Object x) {
        // Default values for invoice generation
        final double DEFAULT_NODE_EVENTS_PER_BLOCK = 0.1;
        final int DEFAULT_N_BLOCKS = 500;
        final int DEFAULT_AMT_MIN = 1000;
        final int DEFAULT_AMT_MAX = 1000000;
        final int DEFAULT_FEES = 1000;
        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("Bootstrap not completed, cannot generate events!");
            return;
        }
        if (!networkManager.getTimechainStatus()) {
            System.out.println("Timechain not running, please start the timechain");
            return;
        }
        System.out.printf(
            "Invoice Generation Rate (events/node/block)\n[0 for defaults: rate=%.1f, blocks=%d, min=%d, max=%d, fees=%d]: ",
            DEFAULT_NODE_EVENTS_PER_BLOCK, DEFAULT_N_BLOCKS, DEFAULT_AMT_MIN, DEFAULT_AMT_MAX, DEFAULT_FEES);
        double node_events_per_block = Double.parseDouble(menuInputScanner.nextLine());

        int n_blocks;
        int amt_min;
        int amt_max;
        int fees;

        if(node_events_per_block == 0) {
            node_events_per_block = DEFAULT_NODE_EVENTS_PER_BLOCK;
            n_blocks = DEFAULT_N_BLOCKS;
            amt_min = DEFAULT_AMT_MIN;
            amt_max = DEFAULT_AMT_MAX;
            fees = DEFAULT_FEES;
        } else {
            System.out.print("Timechain duration (blocks): ");
            n_blocks = Integer.parseInt(menuInputScanner.nextLine());
            System.out.println("Min amount");
            amt_min = Integer.parseInt(menuInputScanner.nextLine());
            System.out.println("Max amount");
            amt_max = Integer.parseInt(menuInputScanner.nextLine());
            System.out.println("Max fees");
            fees = Integer.parseInt(menuInputScanner.nextLine());
        }

        applyPathFinderStrategyToAllNodes(readPathFinderStrategyOrDefault());

        networkManager.generateInvoiceEvents(node_events_per_block, n_blocks, amt_min, amt_max, fees);

    }

    // Method for "Invoice Reports"
    public void invoiceReportsMethod(Object x) {
        if (networkManager.isBootstrapCompleted()) {
            System.out.println(networkManager.getStats().generateInvoiceReport());
        } else {
            System.out.println("Bootstrap not completed!");
        }
    }

    // Method for "Show Network Stats"
    public void showNetworkStatsMethod(Object x) {
        if (networkManager.isBootstrapCompleted()) {
            printNetworkStatsDashboard();
        } else {
            System.out.println("Bootstrap not completed!");
        }
    }
    private void saveUVNetworkStatus(Object x) {
        System.out.print("Save to:");
        String file_to_save = menuInputScanner.nextLine();
        System.out.println("Start saving status, please wait... ");
        networkManager.saveStatus(file_to_save);
    }

    private void loadUVNetworkStatus(Object x) {
        showAvailableFiles(".dat");
        System.out.print("Load from:");
        String file_to_load = menuInputScanner.nextLine();
        if (networkManager.loadStatus(file_to_load)) {
            updateSelectedConfigPath(networkManager.getConfig(), selectedConfigPath);
            System.out.println("UVM LOADED");
        } else System.out.println("ERROR LOADING UVM from " + file_to_load);
    }

    private void setLocalChannelsBalances(Object x) {
        System.out.println("This will set the local balance of channels initiators");
        System.out.print("Enter a local level [0..1]:");
        double level = menuInputScanner.nextDouble();
        networkManager.setLocalBalances(level,10000);
    }

    private void setRandomChannelsBalances(Object x) {
        networkManager.setRandomLiquidity(10000);
    }

    private void quitMenu(Object x) {
        quit = true;
    }


    private void showNodeCommand(String pubkey) {

        if (networkManager.getUVNodeList().isEmpty()) {
            System.out.println("EMPTY NODE LIST");
            return;
        }
        var node = networkManager.searchNode(pubkey);
        printNodeAndChannels(node, computeNodeAmountWidth(List.of(node)));
        printNodeGraphSummary(node);
        printNodeHtlcStats(node);
        printInvoiceReports(node);
        node.showQueuesStatus();
        System.out.println(ui.separator());
    }


    public static void main(String[] args) {

        if (args.length > 1) {
            System.out.println("Usage: UltraViolet [config.properties]");
            System.exit(-1);
        }

        String configPath = args.length == 1
                ? args[0]
                : new File(CONFIG_DIRECTORY, DEFAULT_CONFIG_FILE).getPath();

        new UltraViolet(configPath);
    }

    private String readLineOrDefault(String prompt, String defaultValue) {
        System.out.print(ui.label(prompt) + " " + ui.hint("[" + defaultValue + "]:"));
        String value = menuInputScanner.nextLine();
        return value.isBlank() ? defaultValue : value;
    }

    private int readIntOrDefault(String prompt, int defaultValue) {
        String value = readLineOrDefault(prompt, Integer.toString(defaultValue));
        return Integer.parseInt(value);
    }

    private PathFinderFactory.Strategy parsePathFinderStrategy(String value) {
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "lnd" -> PathFinderFactory.Strategy.LND;
            case "mini_dijkstra", "mini", "dijkstra" -> PathFinderFactory.Strategy.MINI_DIJKSTRA;
            case "shortest_hop", "shortest", "hop" -> PathFinderFactory.Strategy.SHORTEST_HOP;
            case "bfs" -> PathFinderFactory.Strategy.BFS;
            default -> throw new IllegalArgumentException("Unknown path finder: " + value);
        };
    }

    private PathFinderFactory.Strategy readPathFinderStrategyOrDefault() {
        return parsePathFinderStrategy(
                readLineOrDefault("Path finder [lnd|mini_dijkstra|shortest_hop|bfs]", "lnd")
        );
    }

    private String readPathFinderChoiceOrDefault(boolean allowAll) {
        String prompt = allowAll
                ? "Path finder [lnd|mini_dijkstra|shortest_hop|bfs|all]"
                : "Path finder [lnd|mini_dijkstra|shortest_hop|bfs]";
        return readLineOrDefault(prompt, "lnd").trim().toLowerCase(Locale.ROOT);
    }

    private void applyPathFinderStrategy(UVNode node, PathFinderFactory.Strategy strategy, int amount) {
        var pathFinder = PathFinderFactory.of(strategy);
        pathFinder.setPaymentAmount(amount);
        node.setPathFinder(pathFinder);
    }

    private void applyPathFinderStrategyToAllNodes(PathFinderFactory.Strategy strategy) {
        for (UVNode node : networkManager.getUVNodeList().values()) {
            node.setPathFinder(PathFinderFactory.of(strategy));
        }
    }

    private void testInvoiceRoutingCmd() {

        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("Bootstrap not completed, cannot generate events!");
            return;
        }
        if (!networkManager.getTimechainStatus()) {
            System.out.println("Timechain not running, please start the timechain");
            return;
        }

        String start_id = readLineOrDefault("Starting node public key", "pk0");
        var sender = networkManager.searchNode(start_id);
        String end_id = readLineOrDefault("Destination node public key", "pk99");
        var dest = networkManager.searchNode(end_id);
        int amount = readIntOrDefault("Invoice amount", 10000);
        int fees = readIntOrDefault("Max fees", 1000);
        String msg = readLineOrDefault("Invoice message", "default");
        var strategy = readPathFinderStrategyOrDefault();

        applyPathFinderStrategy(sender, strategy, amount);

        var invoice = dest.generateInvoice(amount,msg,true);
        System.out.println("Generated Invoice: "+invoice);

        new Thread(()->sender.processInvoice(invoice, fees,true)).start();
    }

    /**
     *
     */
    private void findPathsCmd() {
        String start;
        if (imported_graph_root != null) {
            System.out.println("Using imported graph root node " + imported_graph_root + " as starting point");
            start = imported_graph_root;
        } else {
            if (networkManager.isBootstrapCompleted()) {
                start = readLineOrDefault("Starting node public key", "pk0");
            }
            else  {
                System.out.println("Bootstrap Non completed!");
                return;
            }
        }

        String destination = readLineOrDefault("Destination node public key", "pk99");
        int amount = readIntOrDefault("Payment amount", 10000);
        String choice = readLineOrDefault("Single[1] or All paths", "all");
        boolean stopfirst = choice.equals("1");
        int topk = stopfirst ? 1 : readIntOrDefault("Top K paths", 20);
        String strategyChoice = readPathFinderChoiceOrDefault(true);

        var startNode = networkManager.searchNode(start);

        if (strategyChoice.equals("all")) {
            for (PathFinderFactory.Strategy strategy : PathFinderFactory.Strategy.values()) {
                applyPathFinderStrategy(startNode, strategy, amount);
                var searchResult = startNode.findPaths(start, destination, topk);
                System.out.println(" -- " + strategy.name().toLowerCase() + " --------------------------------------");
                if (!searchResult.paths().isEmpty()) {
                    for (var pathDetails : searchResult.paths()) {
                        System.out.println(formatPathDetails(pathDetails));
                    }
                } else {
                    System.out.println("NO PATH FOUND");
                }
                System.out.println(ui.hint("search stats: " + formatSearchStats(searchResult.stats())));
            }
            return;
        }

        var strategy = parsePathFinderStrategy(strategyChoice);
        applyPathFinderStrategy(startNode, strategy, amount);
        var searchResult = startNode.findPaths(start, destination, topk);
        System.out.println(" -- " + strategy.name().toLowerCase() + " --------------------------------------");
        if (!searchResult.paths().isEmpty()) {
            for (var pathDetails : searchResult.paths()) {
                System.out.println(formatPathDetails(pathDetails));
            }
        } else {
            System.out.println("NO PATH FOUND");
        }
        System.out.println(ui.hint("search stats: " + formatSearchStats(searchResult.stats())));
    }

    private String formatPathDetails(PathFinder.PathDetails pathDetails) {
        StringBuilder s = new StringBuilder();
        s.append(pathDetails.path()).append(" COST: ").append(formatDouble(pathDetails.totalCost()));
        if (!pathDetails.components().isEmpty()) {
            s.append(" [");
            for (int i = 0; i < pathDetails.components().size(); i++) {
                if (i > 0) {
                    s.append(", ");
                }
                var component = pathDetails.components().get(i);
                s.append(component.label()).append('=').append(formatDouble(component.value()));
            }
            s.append(']');
        }
        return s.toString();
    }

    private String formatSearchStats(PathFinder.SearchStats stats) {
        return "investigated=" + stats.investigatedStates()
                + ", expanded_edges=" + stats.expandedEdges()
                + ", excluded_capacity=" + stats.excludedByCapacity()
                + ", excluded_visited=" + stats.excludedByVisitedState()
                + ", excluded_cycle=" + stats.excludedByCycle()
                + ", excluded_max_hops=" + stats.excludedByMaxHops()
                + ", excluded_cost=" + stats.excludedByCost()
                + ", returned=" + stats.returnedPaths();
    }

    private String formatDouble(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private void showGraphCommand(String node_id) {

        if (!networkManager.isBootstrapCompleted()) return;
        var n = networkManager.searchNode(node_id);
        printGraphTable(n);
    }

    private void showAvailableFiles(String extension) {
        File[] matches = new File(".").listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(extension));
        System.out.println("Available " + extension + " files:");
        if (matches == null || matches.length == 0) {
            System.out.println("  (none found in current directory)");
            return;
        }
        Arrays.sort(matches, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        for (File match : matches) {
            System.out.println("  " + match.getName());
        }
    }

    private void selectPropertiesConfig(Object x) {
        var availableConfigs = getAvailableConfigFiles();
        System.out.println("Available properties in " + CONFIG_DIRECTORY + ":");
        if (availableConfigs.isEmpty()) {
            System.out.println("  (none found)");
            return;
        }

        String currentConfigPath = getSelectedConfigPath();
        for (int i = 0; i < availableConfigs.size(); i++) {
            File configFile = availableConfigs.get(i);
            String marker = sameFile(configFile.getPath(), currentConfigPath) ? "*" : " ";
            System.out.println(" " + (i + 1) + ") " + marker + " " + configFile.getName());
        }
        System.out.println("* current selection");
        System.out.print("Select config by number or filename [ENTER to keep current]:");

        String selection = menuInputScanner.nextLine().trim();
        if (selection.isEmpty()) {
            System.out.println("Keeping " + formatConfigSelection(currentConfigPath));
            return;
        }

        String chosenConfigPath = resolveConfigSelection(selection, availableConfigs);
        if (chosenConfigPath == null) {
            System.out.println("Invalid selection: " + selection);
            return;
        }
        if (sameFile(chosenConfigPath, currentConfigPath)) {
            System.out.println("Already using " + formatConfigSelection(currentConfigPath));
            return;
        }

        if (hasLoadedNetworkState() && !confirmResetForConfigSwitch()) {
            System.out.println("Config switch cancelled");
            return;
        }

        UVConfig newConfig;
        try {
            newConfig = new UVConfig(chosenConfigPath);
        } catch (RuntimeException e) {
            String detail = e.getMessage();
            if (detail == null || detail.isBlank()) {
                detail = e.getClass().getSimpleName();
            }
            System.out.println("ERROR: cannot load " + formatConfigSelection(chosenConfigPath) + ": " + detail);
            return;
        }
        networkManager.shutdown();
        networkManager = new UVNetwork(newConfig);
        imported_graph_root = null;
        updateSelectedConfigPath(newConfig, chosenConfigPath);
        System.out.println("Selected " + formatConfigSelection(getSelectedConfigPath()));
    }

    private ArrayList<File> getAvailableConfigFiles() {
        File[] matches = new File(CONFIG_DIRECTORY)
                .listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".properties"));
        ArrayList<File> files = new ArrayList<>();
        if (matches == null) {
            return files;
        }
        files.addAll(Arrays.asList(matches));
        files.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return files;
    }

    private String resolveConfigSelection(String selection, List<File> availableConfigs) {
        try {
            int index = Integer.parseInt(selection);
            if (index >= 1 && index <= availableConfigs.size()) {
                return availableConfigs.get(index - 1).getPath();
            }
        } catch (NumberFormatException ignored) {
        }

        for (File configFile : availableConfigs) {
            if (configFile.getName().equalsIgnoreCase(selection)) {
                return configFile.getPath();
            }
        }
        return null;
    }

    private boolean hasLoadedNetworkState() {
        return networkManager.isBootstrapStarted()
                || !networkManager.getUVNodeList().isEmpty()
                || networkManager.getTimechainStatus();
    }

    private boolean confirmResetForConfigSwitch() {
        System.out.print(ui.label("Changing config resets the current network") + " " + ui.hint("[y/N]:"));
        String answer = menuInputScanner.nextLine().trim();
        return answer.equalsIgnoreCase("y") || answer.equalsIgnoreCase("yes");
    }

    private UVNetwork createNetworkManager(String configPath) {
        UVConfig config = new UVConfig(configPath);
        updateSelectedConfigPath(config, configPath);
        return new UVNetwork(config);
    }

    private void updateSelectedConfigPath(UVConfig config, String fallbackPath) {
        if (config != null && config.getSourcePath() != null && !config.getSourcePath().isBlank()) {
            selectedConfigPath = config.getSourcePath();
        } else {
            selectedConfigPath = fallbackPath;
        }
    }

    private String getSelectedConfigPath() {
        return selectedConfigPath;
    }

    private String formatConfigSelection(String configPath) {
        if (configPath == null || configPath.isBlank()) {
            return "<unknown>";
        }
        File configFile = new File(configPath);
        String name = configFile.getName();
        if (name.equals(configPath)) {
            return name;
        }
        return name + " [" + configPath + "]";
    }

    private boolean sameFile(String firstPath, String secondPath) {
        if (firstPath == null || secondPath == null) {
            return false;
        }
        return new File(firstPath).getAbsoluteFile().equals(new File(secondPath).getAbsoluteFile());
    }

    private void printNodeAndChannels(UVNode node, int nodeAmountWidth) {
        System.out.println(ui.separator());
        System.out.println(ui.title(" Node " + node.getPubKey() + " ") + " " + ui.accent(node.getAlias()));
        System.out.println(
                "  "
                        + ui.hint("Capacity: ") + ui.value(formatAmountWithUnit(node.getNodeCapacity(), nodeAmountWidth))
                        + "  "
                        + ui.hint("Channels: ") + ui.value(padLeft(Integer.toString(node.getChannels().size()), 3))
                        + "  "
                        + ui.hint("Profile: ") + ui.detail(node.getProfile().getName())
        );
        System.out.println(
                "  "
                        + ui.hint("On-chain: ") + ui.value(formatAmountWithUnit(node.getOnChainBalance(), nodeAmountWidth))
                        + "  "
                        + ui.hint("Pending: ") + ui.accent(formatAmountWithUnit(node.getOnchainPending(), nodeAmountWidth))
        );
        System.out.println(
                "  "
                        + ui.hint("Local: ") + ui.value(formatAmountWithUnit(node.getLocalBalance(), nodeAmountWidth))
                        + "  "
                        + ui.hint("Remote: ") + ui.accent(formatAmountWithUnit(node.getRemoteBalance(), nodeAmountWidth))
        );

        var rows = buildChannelDisplayRows(node);
        if (rows.isEmpty()) {
            System.out.println("  " + ui.hint("(no channels)"));
            return;
        }

        int channelIdWidth = maxWidth("Channel ID", rows.stream().map(ChannelDisplayRow::channelId).toList());
        int peerWidth = maxWidth("Peer", rows.stream().map(ChannelDisplayRow::peerId).toList());
        int capacityWidth = maxWidth("Capacity", rows.stream().map(ChannelDisplayRow::capacity).toList());
        int outboundWidth = maxWidth("Outbound", rows.stream().map(ChannelDisplayRow::outbound).toList());
        int inboundWidth = maxWidth("Inbound", rows.stream().map(ChannelDisplayRow::inbound).toList());
        int selfFeeWidth = maxWidth("Self fee", rows.stream().map(ChannelDisplayRow::selfFees).toList());
        int peerFeeWidth = maxWidth("Peer fee", rows.stream().map(ChannelDisplayRow::peerFees).toList());

        System.out.println("  " + ui.section("Channels"));
        System.out.println(
                "  " + ui.hint(
                        padRight("Channel ID", channelIdWidth) + "  "
                                + padRight("Peer", peerWidth) + "  "
                                + padLeft("Capacity", capacityWidth) + "  "
                                + padLeft("Outbound", outboundWidth) + "  "
                                + padLeft("Inbound", inboundWidth) + "  "
                                + padLeft("Self fee", selfFeeWidth) + "  "
                                + padLeft("Peer fee", peerFeeWidth)
                )
        );
        for (ChannelDisplayRow row : rows) {
            System.out.println(
                    "  "
                            + ui.key(padRight(row.channelId(), channelIdWidth)) + "  "
                            + ui.section(padRight(row.peerId(), peerWidth)) + "  "
                            + ui.value(padLeft(row.capacity(), capacityWidth)) + "  "
                            + ui.value(padLeft(row.outbound(), outboundWidth)) + "  "
                            + ui.accent(padLeft(row.inbound(), inboundWidth)) + "  "
                            + ui.detail(padLeft(row.selfFees(), selfFeeWidth)) + "  "
                            + ui.detail(padLeft(row.peerFees(), peerFeeWidth))
            );
        }
    }

    private void printNodeOverviewTable(String title, List<UVNode> nodes) {
        System.out.println(ui.separator());
        System.out.println(ui.title(title));
        printNodeCollectionSummary(nodes);

        var rows = buildNodeOverviewRows(nodes);
        int pubkeyWidth = maxWidth("Node", rows.stream().map(NodeOverviewRow::pubkey).toList());
        int aliasWidth = maxWidth("Alias", rows.stream().map(NodeOverviewRow::alias).toList());
        int capacityWidth = maxWidth("Capacity", rows.stream().map(NodeOverviewRow::capacity).toList());
        int channelsWidth = maxWidth("Channels", rows.stream().map(NodeOverviewRow::channels).toList());
        int onChainWidth = maxWidth("On-chain", rows.stream().map(NodeOverviewRow::onChain).toList());
        int localWidth = maxWidth("Local", rows.stream().map(NodeOverviewRow::local).toList());
        int remoteWidth = maxWidth("Remote", rows.stream().map(NodeOverviewRow::remote).toList());
        int outboundWidth = maxWidth("Outbound", rows.stream().map(NodeOverviewRow::outbound).toList());
        int profileWidth = maxWidth("Profile", rows.stream().map(NodeOverviewRow::profile).toList());

        System.out.println(
                "  " + ui.hint(
                        padRight("Node", pubkeyWidth) + "  "
                                + padRight("Alias", aliasWidth) + "  "
                                + padLeft("Capacity", capacityWidth) + "  "
                                + padLeft("Channels", channelsWidth) + "  "
                                + padLeft("On-chain", onChainWidth) + "  "
                                + padLeft("Local", localWidth) + "  "
                                + padLeft("Remote", remoteWidth) + "  "
                                + padLeft("Outbound", outboundWidth) + "  "
                                + padRight("Profile", profileWidth)
                )
        );
        for (NodeOverviewRow row : rows) {
            System.out.println(
                    "  "
                            + ui.key(padRight(row.pubkey(), pubkeyWidth)) + "  "
                            + ui.accent(padRight(row.alias(), aliasWidth)) + "  "
                            + ui.value(padLeft(row.capacity(), capacityWidth)) + "  "
                            + ui.value(padLeft(row.channels(), channelsWidth)) + "  "
                            + ui.value(padLeft(row.onChain(), onChainWidth)) + "  "
                            + ui.value(padLeft(row.local(), localWidth)) + "  "
                            + ui.accent(padLeft(row.remote(), remoteWidth)) + "  "
                            + ui.detail(padLeft(row.outbound(), outboundWidth)) + "  "
                            + ui.detail(padRight(row.profile(), profileWidth))
            );
        }
        System.out.println(ui.separator());
    }

    private void printNodeCollectionSummary(List<UVNode> nodes) {
        GlobalStats stats = networkManager.getStats();
        int uniqueChannels = countUniqueChannels(nodes);
        long totalCapacity = computeTotalNetworkCapacity(nodes);
        double averageOutbound = nodes.stream().mapToDouble(this::safeOverallOutboundFraction).average().orElse(0.0);
        UVNode maxGraphNode = stats.getMaxGraphSizeNode();
        UVNode minGraphNode = stats.getMinGraphSizeNode();

        System.out.println(
                "  "
                        + ui.hint("Nodes: ") + ui.value(padLeft(Integer.toString(nodes.size()), 4))
                        + "  "
                        + ui.hint("Channels: ") + ui.value(padLeft(Integer.toString(uniqueChannels), 4))
                        + "  "
                        + ui.hint("Capacity: ") + ui.value(formatAmountWithUnit(totalCapacity))
                        + "  "
                        + ui.hint("Avg outbound: ") + ui.detail(formatPercent(averageOutbound))
        );

        String largest = maxGraphNode == null
                ? "-"
                : maxGraphNode.getPubKey() + " (" + maxGraphNode.getChannelGraph().getNodeCount() + ")";
        String smallest = minGraphNode == null
                ? "-"
                : minGraphNode.getPubKey() + " (" + minGraphNode.getChannelGraph().getNodeCount() + ")";
        System.out.println(
                "  "
                        + ui.hint("Avg graph size: ") + ui.value(formatWhole(stats.getAverageGraphSize()))
                        + "  "
                        + ui.hint("Largest graph: ") + ui.section(largest)
                        + "  "
                        + ui.hint("Smallest graph: ") + ui.section(smallest)
        );
    }

    private ArrayList<NodeOverviewRow> buildNodeOverviewRows(List<UVNode> nodes) {
        ArrayList<NodeOverviewRow> rows = new ArrayList<>();
        for (UVNode node : nodes) {
            rows.add(new NodeOverviewRow(
                    node.getPubKey(),
                    node.getAlias(),
                    formatAmountNumber(node.getNodeCapacity()),
                    Integer.toString(node.getChannels().size()),
                    formatAmountNumber(node.getOnChainBalance()),
                    formatAmountNumber(node.getLocalBalance()),
                    formatAmountNumber(node.getRemoteBalance()),
                    formatPercent(safeOverallOutboundFraction(node)),
                    node.getProfile().getName()
            ));
        }
        return rows;
    }

    private void printNodeGraphSummary(UVNode node) {
        ChannelGraph graph = node.getChannelGraph();
        System.out.println("  " + ui.section("Graph View"));
        System.out.println(
                "  "
                        + ui.hint("Visible nodes: ") + ui.value(Integer.toString(graph.getNodeCount()))
                        + "  "
                        + ui.hint("Directed edges: ") + ui.value(Integer.toString(graph.getChannelCount()))
                        + "  "
                        + ui.hint("Null policies: ") + ui.accent(Integer.toString(graph.countNullPolicies()))
                        + "  "
                        + ui.hint("Outbound share: ") + ui.detail(formatPercent(safeOverallOutboundFraction(node)))
        );
    }

    private void printNodeHtlcStats(UVNode node) {
        GlobalStats.NodeStats stats = node.getNodeStats();
        System.out.println("  " + ui.section("HTLC Stats"));
        System.out.println(
                "  "
                        + ui.hint("Invoice processing: ")
                        + ui.value(Integer.toString(stats.getInvoiceProcessingSuccesses())) + " ok  "
                        + ui.accent(Integer.toString(stats.getInvoiceProcessingFailures())) + " fail  "
                        + ui.hint("Volume: ") + ui.value(formatAmountWithUnit(stats.getInvoiceProcessingVolume()))
        );
        System.out.println(
                "  "
                        + ui.hint("Forwarding: ")
                        + ui.value(Integer.toString(stats.getForwarding_successes())) + " ok  "
                        + ui.accent(Integer.toString(stats.getForwarding_failures())) + " fail  "
                        + ui.hint("Volume: ") + ui.value(formatAmountWithUnit(stats.getForwarded_volume()))
        );
        System.out.println(
                "  "
                        + ui.hint("Failure reasons: ")
                        + ui.accent("expiry-too-soon=" + stats.getExpiryTooSoon())
                        + "  "
                        + ui.accent("temporary-channel-failure=" + stats.getTemporaryChannelFailures())
        );
    }

    private void printInvoiceReports(UVNode node) {
        System.out.println("  " + ui.section("Invoice Reports"));
        var reports = node.getInvoiceReports();
        if (reports.isEmpty()) {
            System.out.println("  " + ui.hint("(none)"));
            return;
        }

        var rows = buildInvoiceDisplayRows(reports);
        int hashWidth = maxWidth("Hash", rows.stream().map(InvoiceDisplayRow::hash).toList());
        int senderWidth = maxWidth("From", rows.stream().map(InvoiceDisplayRow::sender).toList());
        int destWidth = maxWidth("To", rows.stream().map(InvoiceDisplayRow::dest).toList());
        int amountWidth = maxWidth("Amt", rows.stream().map(InvoiceDisplayRow::amount).toList());
        int pathsWidth = maxWidth("Paths", rows.stream().map(InvoiceDisplayRow::paths).toList());
        int candidatesWidth = maxWidth("Cand", rows.stream().map(InvoiceDisplayRow::candidates).toList());
        int attemptsWidth = maxWidth("Att", rows.stream().map(InvoiceDisplayRow::attempts).toList());
        int missPolicyWidth = maxWidth("Pol", rows.stream().map(InvoiceDisplayRow::missPolicy).toList());
        int missCapacityWidth = maxWidth("Cap", rows.stream().map(InvoiceDisplayRow::missCapacity).toList());
        int missLiquidityWidth = maxWidth("Liq", rows.stream().map(InvoiceDisplayRow::missLiquidity).toList());
        int missFeesWidth = maxWidth("Fees", rows.stream().map(InvoiceDisplayRow::missFees).toList());
        int tempWidth = maxWidth("Tmp", rows.stream().map(InvoiceDisplayRow::temporaryFailures).toList());
        int expiryWidth = maxWidth("Exp", rows.stream().map(InvoiceDisplayRow::expirySoon).toList());
        int successWidth = maxWidth("OK", rows.stream().map(InvoiceDisplayRow::success).toList());

        System.out.println(
                "  " + ui.hint(
                        padRight("Hash", hashWidth) + "  "
                                + padRight("From", senderWidth) + "  "
                                + padRight("To", destWidth) + "  "
                                + padLeft("Amt", amountWidth) + "  "
                                + padLeft("Paths", pathsWidth) + "  "
                                + padLeft("Cand", candidatesWidth) + "  "
                                + padLeft("Att", attemptsWidth) + "  "
                                + padLeft("Pol", missPolicyWidth) + "  "
                                + padLeft("Cap", missCapacityWidth) + "  "
                                + padLeft("Liq", missLiquidityWidth) + "  "
                                + padLeft("Fees", missFeesWidth) + "  "
                                + padLeft("Tmp", tempWidth) + "  "
                                + padLeft("Exp", expiryWidth) + "  "
                                + padLeft("OK", successWidth)
                )
        );
        for (InvoiceDisplayRow row : rows) {
            String successValue = padLeft(row.success(), successWidth);
            System.out.println(
                    "  "
                            + ui.detail(padRight(row.hash(), hashWidth)) + "  "
                            + ui.section(padRight(row.sender(), senderWidth)) + "  "
                            + ui.accent(padRight(row.dest(), destWidth)) + "  "
                            + ui.value(padLeft(row.amount(), amountWidth)) + "  "
                            + ui.value(padLeft(row.paths(), pathsWidth)) + "  "
                            + ui.value(padLeft(row.candidates(), candidatesWidth)) + "  "
                            + ui.value(padLeft(row.attempts(), attemptsWidth)) + "  "
                            + ui.accent(padLeft(row.missPolicy(), missPolicyWidth)) + "  "
                            + ui.accent(padLeft(row.missCapacity(), missCapacityWidth)) + "  "
                            + ui.accent(padLeft(row.missLiquidity(), missLiquidityWidth)) + "  "
                            + ui.accent(padLeft(row.missFees(), missFeesWidth)) + "  "
                            + ui.accent(padLeft(row.temporaryFailures(), tempWidth)) + "  "
                            + ui.accent(padLeft(row.expirySoon(), expiryWidth)) + "  "
                            + ("yes".equals(row.success()) ? ui.running(successValue) : ui.stopped(successValue))
            );
        }
    }

    private ArrayList<InvoiceDisplayRow> buildInvoiceDisplayRows(List<GlobalStats.NodeStats.InvoiceReport> reports) {
        ArrayList<InvoiceDisplayRow> rows = new ArrayList<>();
        for (GlobalStats.NodeStats.InvoiceReport report : reports) {
            rows.add(new InvoiceDisplayRow(
                    abbreviateMiddle(report.hash(), 16),
                    abbreviateMiddle(report.sender(), 14),
                    abbreviateMiddle(report.dest(), 14),
                    formatAmountNumber(report.amt()),
                    Integer.toString(report.total_paths()),
                    Integer.toString(report.candidate_paths()),
                    Integer.toString(report.attempted_paths()),
                    Integer.toString(report.miss_policy()),
                    Integer.toString(report.miss_capacity()),
                    Integer.toString(report.miss_local_liquidity()),
                    Integer.toString(report.miss_fees()),
                    Integer.toString(report.temporary_channel_failures()),
                    Integer.toString(report.expiry_too_soon()),
                    report.success() ? "yes" : "no"
            ));
        }
        return rows;
    }

    private void printGraphTable(UVNode node) {
        ChannelGraph graph = node.getChannelGraph();
        System.out.println(ui.separator());
        System.out.println(ui.title(" Graph " + node.getPubKey() + " ") + " " + ui.accent(node.getAlias()));
        System.out.println(
                "  "
                        + ui.hint("Visible nodes: ") + ui.value(Integer.toString(graph.getNodeCount()))
                        + "  "
                        + ui.hint("Directed edges: ") + ui.value(Integer.toString(graph.getChannelCount()))
                        + "  "
                        + ui.hint("Null policies: ") + ui.accent(Integer.toString(graph.countNullPolicies()))
        );

        var rows = buildGraphDisplayRows(graph);
        if (rows.isEmpty()) {
            System.out.println("  " + ui.hint("(graph is empty)"));
            System.out.println(ui.separator());
            return;
        }

        int sourceWidth = maxWidth("Source", rows.stream().map(GraphDisplayRow::source).toList());
        int destWidth = maxWidth("Destination", rows.stream().map(GraphDisplayRow::destination).toList());
        int channelWidth = maxWidth("Channel ID", rows.stream().map(GraphDisplayRow::channelId).toList());
        int capacityWidth = maxWidth("Capacity", rows.stream().map(GraphDisplayRow::capacity).toList());
        int policyWidth = maxWidth("Policy", rows.stream().map(GraphDisplayRow::policy).toList());

        System.out.println(
                "  " + ui.hint(
                        padRight("Source", sourceWidth) + "  "
                                + padRight("Destination", destWidth) + "  "
                                + padRight("Channel ID", channelWidth) + "  "
                                + padLeft("Capacity", capacityWidth) + "  "
                                + padLeft("Policy", policyWidth)
                )
        );
        for (GraphDisplayRow row : rows) {
            String source = row.source().equals(node.getPubKey())
                    ? ui.accent(padRight(row.source(), sourceWidth))
                    : ui.section(padRight(row.source(), sourceWidth));
            System.out.println(
                    "  "
                            + source + "  "
                            + ui.key(padRight(row.destination(), destWidth)) + "  "
                            + ui.detail(padRight(row.channelId(), channelWidth)) + "  "
                            + ui.value(padLeft(row.capacity(), capacityWidth)) + "  "
                            + ui.detail(padLeft(row.policy(), policyWidth))
            );
        }
        System.out.println(ui.separator());
    }

    private ArrayList<GraphDisplayRow> buildGraphDisplayRows(ChannelGraph graph) {
        ArrayList<GraphDisplayRow> rows = new ArrayList<>();
        ArrayList<String> sources = new ArrayList<>(graph.getAdjMap().keySet());
        sources.sort(String::compareTo);
        for (String source : sources) {
            var edges = new ArrayList<>(graph.getAdjMap().getOrDefault(source, Set.of()));
            edges.sort(Comparator.comparing(ChannelGraph.Edge::destination).thenComparing(ChannelGraph.Edge::id));
            for (ChannelGraph.Edge edge : edges) {
                rows.add(new GraphDisplayRow(
                        source,
                        edge.destination(),
                        edge.id(),
                        formatAmountNumber(edge.capacity()),
                        formatPolicy(edge.policy())
                ));
            }
        }
        return rows;
    }

    private void printNetworkStatsDashboard() {
        var nodes = networkManager.getSortedNodeListByPubkey();
        System.out.println(ui.separator());
        System.out.println(ui.title(" Network Stats "));
        printNodeCollectionSummary(nodes);

        var rows = buildNetworkStatRows(nodes);
        int labelWidth = maxWidth("Metric", rows.stream().map(NetworkStatRow::label).toList());
        int minWidth = maxWidth("Min", rows.stream().map(NetworkStatRow::min).toList());
        int maxWidth = maxWidth("Max", rows.stream().map(NetworkStatRow::max).toList());
        int avgWidth = maxWidth("Average", rows.stream().map(NetworkStatRow::average).toList());
        int stdWidth = maxWidth("Std dev", rows.stream().map(NetworkStatRow::stdDev).toList());
        int q1Width = maxWidth("Q1", rows.stream().map(NetworkStatRow::firstQuartile).toList());
        int medianWidth = maxWidth("Median", rows.stream().map(NetworkStatRow::median).toList());
        int q3Width = maxWidth("Q3", rows.stream().map(NetworkStatRow::thirdQuartile).toList());

        System.out.println(
                "  " + ui.hint(
                        padRight("Metric", labelWidth) + "  "
                                + padLeft("Min", minWidth) + "  "
                                + padLeft("Max", maxWidth) + "  "
                                + padLeft("Average", avgWidth) + "  "
                                + padLeft("Std dev", stdWidth) + "  "
                                + padLeft("Q1", q1Width) + "  "
                                + padLeft("Median", medianWidth) + "  "
                                + padLeft("Q3", q3Width)
                )
        );
        for (NetworkStatRow row : rows) {
            System.out.println(
                    "  "
                            + ui.section(padRight(row.label(), labelWidth)) + "  "
                            + ui.value(padLeft(row.min(), minWidth)) + "  "
                            + ui.value(padLeft(row.max(), maxWidth)) + "  "
                            + ui.value(padLeft(row.average(), avgWidth)) + "  "
                            + ui.detail(padLeft(row.stdDev(), stdWidth)) + "  "
                            + ui.accent(padLeft(row.firstQuartile(), q1Width)) + "  "
                            + ui.accent(padLeft(row.median(), medianWidth)) + "  "
                            + ui.accent(padLeft(row.thirdQuartile(), q3Width))
            );
        }
        System.out.println(ui.separator());
    }

    private ArrayList<NetworkStatRow> buildNetworkStatRows(List<UVNode> nodes) {
        ArrayList<NetworkStatRow> rows = new ArrayList<>();
        rows.add(buildNetworkStatRow("Graph nodes", nodes.stream().mapToDouble(n -> n.getChannelGraph().getNodeCount()), this::formatWhole));
        rows.add(buildNetworkStatRow("Graph edges", nodes.stream().mapToDouble(n -> n.getChannelGraph().getChannelCount()), this::formatWhole));
        rows.add(buildNetworkStatRow("Node channels", nodes.stream().mapToDouble(n -> n.getChannels().size()), this::formatWhole));
        rows.add(buildNetworkStatRow("Node capacity", nodes.stream().mapToDouble(UVNode::getNodeCapacity), this::formatWhole));
        rows.add(buildNetworkStatRow("Local balance", nodes.stream().mapToDouble(UVNode::getLocalBalance), this::formatWhole));
        rows.add(buildNetworkStatRow("Invoices", nodes.stream().mapToDouble(n -> n.getGeneratedInvoices().size()), this::formatWhole));
        rows.add(buildNetworkStatRow("Outbound %", nodes.stream().mapToDouble(this::safeOverallOutboundFraction), this::formatPercent));
        return rows;
    }

    private NetworkStatRow buildNetworkStatRow(String label, DoubleStream stream, DoubleFunction<String> formatter) {
        GlobalStats.StatFunctions stats = new GlobalStats.StatFunctions(stream);
        return new NetworkStatRow(
                label,
                formatter.apply(stats.calculateMin()),
                formatter.apply(stats.calculateMax()),
                formatter.apply(stats.calculateAverage()),
                formatter.apply(stats.calculateStandardDeviation()),
                formatter.apply(stats.calculateFirstQuartile()),
                formatter.apply(stats.calculateMedian()),
                formatter.apply(stats.calculateThirdQuartile())
        );
    }

    private ArrayList<ChannelDisplayRow> buildChannelDisplayRows(UVNode node) {
        ArrayList<ChannelDisplayRow> rows = new ArrayList<>();
        var channels = node.getChannels().values().stream().sorted().toList();
        for (UVChannel channel : channels) {
            String localId = node.getPubKey();
            String peerId = channel.getNode1PubKey().equals(localId)
                    ? channel.getNode2PubKey()
                    : channel.getNode1PubKey();
            rows.add(new ChannelDisplayRow(
                    channel.getChannelId(),
                    peerId,
                    formatAmountNumber(channel.getCapacity()),
                    formatAmountNumber(Math.max(0, channel.getLiquidity(localId))),
                    formatAmountNumber(Math.max(0, channel.getLiquidity(peerId))),
                    formatPolicy(channel.getPolicy(localId)),
                    formatPolicy(channel.getPolicy(peerId))
            ));
        }
        return rows;
    }

    private int computeNodeAmountWidth(List<UVNode> nodes) {
        int width = 1;
        for (UVNode node : nodes) {
            width = Math.max(width, formatAmountNumber(node.getNodeCapacity()).length());
            width = Math.max(width, formatAmountNumber(node.getOnChainBalance()).length());
            width = Math.max(width, formatAmountNumber(node.getOnchainPending()).length());
            width = Math.max(width, formatAmountNumber(node.getLocalBalance()).length());
            width = Math.max(width, formatAmountNumber(node.getRemoteBalance()).length());
        }
        return width;
    }

    private int countUniqueChannels(List<UVNode> nodes) {
        return nodes.stream().mapToInt(node -> node.getChannels().size()).sum() / 2;
    }

    private long computeTotalNetworkCapacity(List<UVNode> nodes) {
        return nodes.stream().mapToLong(UVNode::getNodeCapacity).sum() / 2L;
    }

    private int maxWidth(String header, List<String> values) {
        int width = header.length();
        for (String value : values) {
            width = Math.max(width, value.length());
        }
        return width;
    }

    private String formatAmountWithUnit(int amount, int width) {
        return padLeft(formatAmountNumber(amount), width) + " sats";
    }

    private String formatAmountWithUnit(int amount) {
        return formatAmountWithUnit((long) amount);
    }

    private String formatAmountWithUnit(long amount) {
        return formatAmountNumber(amount) + " sats";
    }

    private String formatAmountNumber(int amount) {
        return formatAmountNumber((long) amount);
    }

    private String formatAmountNumber(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    private String formatWhole(double value) {
        if (!Double.isFinite(value)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%,.0f", value);
    }

    private String formatPercent(double fraction) {
        if (!Double.isFinite(fraction)) {
            return "-";
        }
        return String.format(Locale.ROOT, "%.1f%%", fraction * 100.0);
    }

    private String formatPolicy(LNChannel.Policy policy) {
        if (policy == null) {
            return "-";
        }
        return String.format(Locale.ROOT, "%4d %4d", policy.getBaseFee(), policy.getFeePpm());
    }

    private double safeOverallOutboundFraction(UVNode node) {
        int capacity = node.getNodeCapacity();
        if (capacity == 0) {
            return 0.0;
        }
        return (double) node.getLocalBalance() / capacity;
    }

    private String abbreviateMiddle(String value, int maxWidth) {
        if (value == null || value.length() <= maxWidth || maxWidth < 5) {
            return value;
        }
        int prefix = (maxWidth - 3) / 2;
        int suffix = maxWidth - 3 - prefix;
        return value.substring(0, prefix) + "..." + value.substring(value.length() - suffix);
    }

    private String formatBootstrapProgressLine(int totalNodes) {
        int completed = networkManager.getBootstrapsEnded();
        int running = networkManager.getBootstrapsRunning();
        double percent = totalNodes == 0 ? 100.0 : 100.0 * completed / totalNodes;
        return String.format(
                Locale.ROOT,
                "Bootstrapping (%5.1f%%) (Completed %d of %d, running:%d)",
                percent,
                completed,
                totalNodes,
                running
        );
    }

    private void resetBootstrapProgressOutput() {
        synchronized (consoleOutputLock) {
            lastBootstrapProgressLine = null;
            bootstrapProgressWidth = 0;
            bootstrapProgressVisible = false;
        }
    }

    private void renderBootstrapProgressLine(String progressLine) {
        synchronized (consoleOutputLock) {
            if (Objects.equals(lastBootstrapProgressLine, progressLine)) {
                return;
            }
            bootstrapProgressWidth = Math.max(bootstrapProgressWidth, progressLine.length());
            System.out.print("\r" + padRight(progressLine, bootstrapProgressWidth));
            System.out.flush();
            lastBootstrapProgressLine = progressLine;
            bootstrapProgressVisible = true;
        }
    }

    private void finishBootstrapProgressOutput() {
        synchronized (consoleOutputLock) {
            if (!bootstrapProgressVisible) {
                return;
            }
            System.out.println();
            bootstrapProgressVisible = false;
        }
    }

    private void printNetworkLogLine(String message) {
        synchronized (consoleOutputLock) {
            if (bootstrapProgressVisible) {
                System.out.print("\r" + padRight("", bootstrapProgressWidth) + "\r");
                System.out.flush();
                bootstrapProgressVisible = false;
            }
            System.out.println(message);
        }
    }

    private static String padRight(String value, int width) {
        StringBuilder s = new StringBuilder(value);
        while (s.length() < width) {
            s.append(' ');
        }
        return s.toString();
    }

    private static String padLeft(String value, int width) {
        StringBuilder s = new StringBuilder(value);
        while (s.length() < width) {
            s.insert(0, ' ');
        }
        return s.toString();
    }


}
