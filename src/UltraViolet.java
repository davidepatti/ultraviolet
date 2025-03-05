import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;


public class UltraViolet {

    private final UVNetwork networkManager;
    boolean quit = false;
    private String imported_graph_root;
    private static final int LOOP_SLEEP_TIME = 500;
    private final Scanner menuInputScanner;


    private static class MenuItem {
        public final String key, description;
        private final String entry;

        public final Consumer<Void> func;

        public MenuItem() {
            key = "";
            description = null;
            func = __ -> {};
            entry = "__________________________________________________";
        }

        public MenuItem(String key, String desc, Consumer<Void> func) {
            this.key = key;
            this.description = desc;
            this.func = func;
            StringBuilder s = new StringBuilder();
            s.append(key);
            while (s.toString().length() < 8) s.append(" ");
            s.append("- ").append(desc);

            entry = s.toString();
        }
        @Override
        public String toString() {
            return entry;
        }
    }


    public UltraViolet(UVConfig config) {
        this.networkManager = new UVNetwork(config);

        ArrayList<MenuItem> menuItems = new ArrayList<>();
        menuInputScanner = new Scanner(System.in);

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
        menuItems.add(new MenuItem("qu", "Show Node Queues", this::showNodeQueues));
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

        while (!quit) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println("__________________________________________________");
            System.out.println(" U l t r a v i o l e t ");
            System.out.println("__________________________________________________");
            menuItems.forEach(System.out::println);
            System.out.println("__________________________________________________");
            System.out.print("Timechain: "+networkManager.getTimechain().getCurrentBlockHeight());
            if (!networkManager.getTimechain().isRunning()) System.out.println(" (NOT RUNNING)");
            else System.out.println(" Running...");

            System.out.print("\n -> ");
            var ch = menuInputScanner.nextLine();

            for (MenuItem item : menuItems) {
                if (item.key.equals(ch)) {
                    item.func.accept(null);
                    break;
                }
            }
            System.out.println("\n[ Press ENTER to continue... ]");
            menuInputScanner.nextLine();
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
        var bootstrap_exec= Executors.newSingleThreadExecutor();
        Future<?> bootstrapOutcome = bootstrap_exec.submit(networkManager::bootstrapNetwork);
        System.out.println("waiting bootstrap to finish...");
        int loopCount = 0;
        while (!bootstrapOutcome.isDone()) {
            loopCount++;
            System.out.println("Bootstrapping (" + 100 * networkManager.getBootstrapsEnded() / (double) config.bootstrap_nodes + "%)(Completed " + networkManager.getBootstrapsEnded() + " of " + config.bootstrap_nodes + ", running:" + networkManager.getBootstrapsRunning() + ")");
            if (loopCount % 20 == 0) System.out.println();
            try {
                Thread.sleep(LOOP_SLEEP_TIME);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Done!");
    }
    private void myMethod(Object x) {
        System.out.println("A graph topology will be imported using the json output of 'lncli describegraph' command on some root node");
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
        if (!networkManager.isTimechainRunning()) {
            System.out.println("Starting Timechain, check " + networkManager.getConfig().logfile);
            networkManager.setTimechainRunning(true);
            networkManager.startP2PNetwork();
        } else {
            System.out.print("Waiting for Timechain services to stop...");
            networkManager.setTimechainRunning(false);
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

    private void showNodeQueues(Object x) {
        if (!networkManager.isBootstrapCompleted()) return;
        System.out.print("Insert node public key:");
        String node_id = menuInputScanner.nextLine();
        var node = networkManager.searchNode(node_id);
        showQueueCommand(node);
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
            r.append(n.nodeStats.generateStatsCSV(n)).append("\n");
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
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    ;

    // Method for "Generate Invoice Events"
    public void generateInvoiceEventsMethod(Object x) {
        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("Bootstrap not completed, cannot generate events!");
            return;
        }
        if (!networkManager.isTimechainRunning()) {
            System.out.println("Timechain not running, please start the timechain");
            return;
        }
        System.out.print("Injection Rate (node events per block):");
        double node_events_per_block = Double.parseDouble(menuInputScanner.nextLine());
        System.out.print("Timechain duration (blocks): ");
        int n_blocks = Integer.parseInt(menuInputScanner.nextLine());
        System.out.println("Min amount");
        int amt_min = Integer.parseInt(menuInputScanner.nextLine());
        System.out.println("Max amount");
        int amt_max = Integer.parseInt(menuInputScanner.nextLine());
        System.out.println("Max fees");
        int fees = Integer.parseInt(menuInputScanner.nextLine());
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
        System.out.print("Load from:");
        String file_to_load = menuInputScanner.nextLine();
        if (networkManager.loadStatus(file_to_load))
            System.out.println("UVM LOADED");
        else System.out.println("ERROR LOADING UVM from " + file_to_load);
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
        UVNode.generateNodeLabelString();
        System.out.println(node);

        System.out.println("---------------------------------------------------------------------");
        System.out.println("Channel ID      n1     n2     balances              base/ppm fees             outbound/inbound");
        System.out.println("---------------------------------------------------------------------");

        for (var channel: node.getChannels().values()) {
            int outbound = (int)(node.getOutboundFraction(channel.getChannel_id())*100);
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

        if (args.length!=1) {
            System.out.println("No config file specified , exiting...");
            System.exit(-1);
        }

        var uvm_client = new UltraViolet(new UVConfig(args[0]));
    }
    private void testInvoiceRoutingCmd() {

        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("ERROR: must execute bootstrap or load/import a network!");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.print("Starting node public key:");
        String start_id = scanner.nextLine();
        var sender = networkManager.searchNode(start_id);
        System.out.print("Destination node public key:");
        String end_id = scanner.nextLine();
        var dest = networkManager.searchNode(end_id);
        System.out.print("Invoice amount:");
        int amount = Integer.parseInt(scanner.nextLine());
        System.out.print("Max fees:");
        int fees = Integer.parseInt(scanner.nextLine());

        var invoice = dest.generateInvoice(amount,"test");
        System.out.println("Generated Invoice: "+invoice);

        new Thread(()->sender.processInvoice(invoice, fees,true)).start();
    }

    /**
     *
     */
    private void findPathsCmd() {
        Scanner scanner = new Scanner(System.in);
        String start;
        if (imported_graph_root != null) {
            System.out.println("Using imported graph root node " + imported_graph_root + " as starting point");
            start = imported_graph_root;
        } else {
            if (networkManager.isBootstrapCompleted()) {
                System.out.print("Starting node public key:");
                start = scanner.nextLine();
            }
            else  {
                System.out.println("Bootstrap Non completed!");
                return;
            }
        }

        System.out.print("Destination node public key:");
        String destination = scanner.nextLine();
        System.out.print("Single[1] or All [any key] paths?");
        String choice = scanner.nextLine();
        boolean stopfirst = choice.equals("1");

        var paths = networkManager.searchNode(start).getChannelGraph().findPath(start,destination,stopfirst);

        if (!paths.isEmpty()) {
            for (ArrayList<ChannelGraph.Edge> path: paths) {
                System.out.println(ChannelGraph.pathString(path));
            }
        }
        else System.out.println("NO PATH FOUND");
    }


    private void showQueueCommand(UVNode node) {
        System.out.println("P2P message queue:");
        System.out.println("-------------------------------------------------------------");
        node.getGossipMessageQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending channels to accept:");
        node.getChannelsToAcceptQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending channel accepted:");
        node.getChannelsAcceptedQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending updateAddHTLC:");
        node.getUpdateAddHTLCQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending updateFulfilHTLC:");
        node.getUpdateFulFillHTLCQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending updateFailHTLC:");
        node.getUpdateFailHTLCQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending invoices:");
        node.getPendingInvoices().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending received HTLC:");
        node.getReceivedHTLC().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending HTLC:");
        node.getPendingHTLC().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending sent channel openings:");
        node.getSentChannelOpenings().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending accepted channel peers:");
        node.getPendingAcceptedChannelPeers().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Generated Invoices:");
        node.getGeneratedInvoices().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Payed Invoices:");
        node.getPayedInvoices().values().forEach(System.out::println);
    }

    private void showGraphCommand(String node_id) {

        if (!networkManager.isBootstrapCompleted()) return;
        var n = networkManager.searchNode(node_id);
        System.out.println(n.getChannelGraph());
        System.out.println("Graph null policies: "+n.getChannelGraph().countNullPolicies());
    }


}







