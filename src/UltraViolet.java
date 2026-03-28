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
import java.util.function.Supplier;


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
        private static final String CYAN = "\033[36m";
        private static final String GREEN = "\033[32m";
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
        System.out.println(UVNode.generateNodeLabelString());
        System.out.println("---------------------------------------------------------------------");
        networkManager.getUVNodeList().values().stream().sorted().forEach(System.out::println);
    }

    public void showNodesAndChannels(Object x) {
        if (networkManager.getUVNodeList().isEmpty()) {
            System.out.println("EMPTY NODE LIST");
            return;
        }
        var ln = networkManager.getUVNodeList().values().stream().sorted().toList();
        for (UVNode n : ln) {
            System.out.println("--------------------------------------------");
            System.out.println(UVNode.generateNodeLabelString());
            System.out.println(n);
            System.out.println(UVChannel.generateLabels());
            n.getChannels().values().forEach(System.out::println);
        }
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
            System.out.println(networkManager.getStats().generateNetworkReport());
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
        System.out.println("---------------------------------------------------------------------");
        System.out.println(UVNode.generateNodeLabelString());
        System.out.println(node);

        System.out.println("---------------------------------------------------------------------");
        System.out.println("Channel ID      n1     n2     balances              base/ppm fees             outbound/inbound");
        System.out.println("---------------------------------------------------------------------");

        for (var channel: node.getChannels().values()) {
            int outbound = (int)(node.getOutboundFraction(channel.getChannelId())*100);
            System.out.println(channel+" ["+outbound+"/"+(100-outbound)+"]");
        }

        System.out.println("---------------------------------------------------------------------");
        int edges = node.getChannelGraph().getChannelCount();
        int vertex = node.getChannelGraph().getNodeCount();
        System.out.println("Graph nodes: " + vertex + " Graph channels: " + edges + " Graph null policies: " + node.getChannelGraph().countNullPolicies());
        System.out.println("---------------------------------------------------------------------");
        System.out.println("*** HTLC Node Statistics ");
        System.out.println("---------------------------------------------------------------------");
        System.out.println(GlobalStats.NodeStats.generateHTLCStatsHeader());
        System.out.println(node.getNodeStats().generateHTLCStatsCSV());
        System.out.println("---------------------------------------------------------------------");
        System.out.println("*** Invoice Reports ");
        System.out.println("---------------------------------------------------------------------");
        System.out.println(GlobalStats.NodeStats.InvoiceReport.generateInvoiceReportHeader());
        for (var r :node.getNodeStats().getInvoiceReports()) {
            System.out.println(r);
        }
        node.showQueuesStatus();
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
        System.out.println(n.getChannelGraph());
        System.out.println("Graph null policies: "+n.getChannelGraph().countNullPolicies());
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


}
