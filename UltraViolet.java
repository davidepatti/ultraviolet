import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;


public class UltraViolet {

    private final UVNetworkManager networkManager;
    boolean quit = false;
    private String imported_graph_root;

    private void showNodeCommand(String pubkey) {

        if (networkManager.getUVNodeList().size() == 0)
            System.out.println("EMPTY NODE LIST");
        var node = networkManager.getNode(pubkey);
        if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }

        node.getChannels().values().stream().sorted().forEach(System.out::println);

        if (false) {
            System.out.println("Peers:");
            node.getPeers().values().stream().sorted().forEach(System.out::println);
        }

        int edges = node.getChannelGraph().getChannelCount();
        int vertex = node.getChannelGraph().getNodeCount();
        System.out.println("Graph nodes:" + vertex);
        System.out.println("Graph channels:" + edges);
        if (node.getGossipMsgQueue() != null)
            System.out.println("Current p2p message queue:" + node.getGossipMsgQueue().size());
        else
            System.out.println("No p2p queued messages");

        if (false) {
            System.out.println("Channel Graph:");
            System.out.println(node.getChannelGraph().toString());
        }
    }

    private static class MenuItem {
        public final String key, description;
        private final String entry;

        public final Consumer<Void> func;

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


    /**
     *
     */
    private void testInvoiceRoutingCmd() {

        if (!networkManager.isBootstrapCompleted()) {
            System.out.println("ERROR: must execute bootstrap or load/import a network!");
            return;
        }

        Scanner scanner = new Scanner(System.in);
        System.out.print("Starting node public key:");
        String start_id = scanner.nextLine();
        var sender = networkManager.getNode(start_id);
        System.out.print("Destination node public key:");
        String end_id = scanner.nextLine();
        var dest = networkManager.getNode(end_id);
        System.out.print("Invoice amount:");
        int amount = Integer.parseInt(scanner.nextLine());
        System.out.print("Max fees:");
        int fees = Integer.parseInt(scanner.nextLine());

        var invoice = dest.generateInvoice(amount);
        System.out.println("Generated Invoice: "+invoice);

        var paths = sender.findPaths(invoice.getDestination(),false);
        var validPaths = new ArrayList<ArrayList<ChannelGraph.Edge>>();


        System.out.println("Found "+paths.size()+" paths to "+invoice.getDestination());

        for (var path:paths) {
            if (sender.computePathFees(path,invoice.getAmount()) > fees) {
                System.out.println("Discarding path (fees)"+ ChannelGraph.pathString(path));
                continue;
            }
            if (sender.checkPathLiquidity(path, invoice.getAmount()))  {
                System.out.println("Discarding path (liquidity)"+ ChannelGraph.pathString(path));
                continue;
            }
            validPaths.add(path);
        }
        boolean success = false;
        if (validPaths.size()>0) {
            int n = 0;
            for (var path: validPaths) {
                n++;
                System.out.println("Trying path: "+ChannelGraph.pathString(path));
                sender.routeInvoiceOnPath(invoice,path);

                if (sender.waitForInvoiceCleared(invoice.getHash())) {
                    success = true;
                    break;
                }
            }
            if (success) {
                System.out.println("Successfull processed invoice "+invoice.getHash());
            }
            else System.out.println("Failed routing for invoice "+invoice.getHash());
        }

        else
            System.out.println("No suitable path for destination "+ invoice.getDestination());
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
            System.out.print("Starting node public key:");
            start = scanner.nextLine();
        }

        System.out.print("Destination node public key:");
        String destination = scanner.nextLine();
        System.out.print("Single[1] or All [any key] paths?");
        String choice = scanner.nextLine();
        boolean stopfirst = choice.equals("1");

        var paths = networkManager.getNode(start).findPaths(destination,stopfirst);

        if (paths.size()>0) {
            for (ArrayList<ChannelGraph.Edge> path: paths) {
                System.out.println(ChannelGraph.pathString(path));
            }
        }
        else System.out.println("NO PATH FOUND");
    }


    /**
     *
     * @param f
     */
    private void _waitForFuture(Future f) {
        int i =0;
        while (!f.isDone()) {
            i++;
            System.out.print(".");
            if (i%20 ==0 ) System.out.println("");
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Done!");
    }


    private void showQueueCommand(String node_id) {
        if (!networkManager.isBootstrapCompleted()) return;
        var node = networkManager.getNode(node_id);
        if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }
        System.out.println("P2P message queue:");
        System.out.println("-------------------------------------------------------------");
        node.getGossipMessageQueue().forEach(System.out::println);

        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending HTLC:");
        node.getReceivedHTLC().values().forEach(System.out::println);

        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending opening:");

        node.getSentChannelOpenings().values().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending accepted:");
        node.getChannelsAcceptedQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending to accept:");
        node.getChannelsToAcceptQueue().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending Invoices:");
        node.getGeneratedInvoices().values().forEach(System.out::println);
    }

    private void showGraphCommand(String node_id) {

        if (!networkManager.isBootstrapCompleted()) return;
        var g = networkManager.getNode(node_id).getChannelGraph();
        System.out.println(g);
    }

    /**
     *
     * @param nm Network Manager instance controlled by the dashboard
     */
    public UltraViolet(UVNetworkManager nm) {
        networkManager = nm;
        ArrayList<MenuItem> menuItems = new ArrayList<>();
        var scanner = new Scanner(System.in);

        menuItems.add(new MenuItem("boot", "Bootstrap a Lightning Network from scratch", (x) -> {
            if (networkManager.isBootstrapStarted() || networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: network already bootstrapped!");
            } else {
                System.out.println("Bootstrap Started, check " + Config.get("logfile"));
                var bootstrap_exec= Executors.newSingleThreadExecutor();
                Future bootstrap = bootstrap_exec.submit(networkManager::bootstrapNetwork);
                System.out.println("waiting bootstrap to finish...");
                _waitForFuture(bootstrap);
            }
        }));
        menuItems.add(new MenuItem("import", "Import a Network Topology", x -> {
            if (!networkManager.resetUVM()) {
                System.out.println("Cannot reset UVM");
            }
            System.out.println("A graph topology will be imported using the json output of 'lncli describegraph' command on some root node");
            System.out.print("Enter a JSON file: ");
            String json = scanner.nextLine();
            System.out.print("Enter root node pubkey:");
            String root = scanner.nextLine();
            imported_graph_root = root;
            new Thread(()-> networkManager.importTopology(json,root)).start();
        }));
        menuItems.add(new MenuItem("t", "Start/Stop Timechain and P2P", (x) -> {
            if (!networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: must execute bootstrap or load/import a network!");
                return;
            }
            if (!networkManager.isTimechainRunning()) {
                System.out.println("Starting Timechain, check " + Config.get("logfile"));
                networkManager.setTimechainRunning(true);
            }
            else {
                System.out.print("Waiting for Timechain services to stop...");
                networkManager.setTimechainRunning(false);
            }
        }));

        menuItems.add(new MenuItem("all", "Show Nodes and Channels", x -> {
            if (networkManager.getUVNodeList().size()== 0) {
                System.out.println("EMPTY NODE LIST");
                return;
            }
            var ln = networkManager.getUVNodeList().values().stream().sorted().toList();

            for (UVNode n : ln) {
                System.out.println(n);
                n.getChannels().values().forEach(System.out::println);
            }
        }));

        menuItems.add(new MenuItem("nodes", "Show Nodes ", x -> networkManager.getUVNodeList().values().stream().sorted().forEach(System.out::println)));
        menuItems.add(new MenuItem("node", "Show a single Node ", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showNodeCommand(node);
        }));
        menuItems.add(new MenuItem("graph", "Show a Node Graph", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showGraphCommand(node);
        }));
        menuItems.add(new MenuItem("p2pq", "Show a Node Message Queue", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showQueueCommand(node);
        }));
        menuItems.add(new MenuItem("test", "TEST", x -> {
            System.out.println("HI!");
        }));

        menuItems.add(new MenuItem("conf", "Show Configuration ", x -> {
            System.out.println("-----------------------------------");
            Config.print();
            System.out.println("-----------------------------------");
        }));
        menuItems.add(new MenuItem("inv", "Generate Invoice Events ", x -> {
            System.out.print("Number of invoice events:");
            int n = Integer.parseInt(scanner.nextLine());
            System.out.println("Max amount");
            int amt = Integer.parseInt(scanner.nextLine());
            System.out.println("Max fees");
            int fees = Integer.parseInt(scanner.nextLine());

            networkManager.generateInvoiceEvents(n,amt,fees);

        }));


        menuItems.add(new MenuItem("rand", "Generate Random Events ", x -> {
            System.out.print("Number of events:");
            String n = scanner.nextLine();
            if (networkManager.isBootstrapCompleted()) {
                System.out.println("Generating events, check " + Config.get("logfile"));
                networkManager.generateRandomEvents(Integer.parseInt(n));
            } else {
                System.out.println("Bootstrap not completed, cannot generate events!");
            }
        }));

        menuItems.add(new MenuItem("stats", "Show Global Stats", x -> {
            if (networkManager.isBootstrapCompleted())  {
                networkManager.getStats().writeReport(new Date()+"_report.txt");
                System.out.println(networkManager.getStats().generateReport());
            }
            else System.out.println("Bootstrap not completed!");
        } ));
        menuItems.add(new MenuItem("path", "Get routing paths between nodes", x -> findPathsCmd()));
        menuItems.add(new MenuItem("route", "Route Payment", x -> testInvoiceRoutingCmd()));
        menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> { networkManager.resetUVM(); }));
        menuItems.add(new MenuItem("free", "Try to free memory", x -> { System.gc(); }));
        menuItems.add(new MenuItem("save", "Save UV Network Status", x -> {
            System.out.print("Save to:");
            String file_to_save = scanner.nextLine();
            networkManager.saveStatus(file_to_save);
        }));
        menuItems.add(new MenuItem("load", "Load UV Network Status", x -> {
            System.out.print("Load from:");
            String file_to_load = scanner.nextLine();
            if (networkManager.loadStatus(file_to_load))
                System.out.println("UVM LOADED");
            else System.out.println("ERROR LOADING UVM from " + file_to_load);
        }));
        menuItems.add(new MenuItem("q", "Disconnect Client ", x -> quit = true));


        while (!quit) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println(" Ultraviolet client ");
            System.out.println("__________________________________________________");
            menuItems.forEach(System.out::println);
            System.out.println("__________________________________________________");
            System.out.print("Timechain: "+networkManager.getTimechain().getCurrentBlock());
            if (!networkManager.getTimechain().isRunning()) System.out.println(" (Not running)");
            else System.out.println(" Running...");
            System.out.println("__________________________________________________");

            //networkManager.getUVNodes().values().stream().forEach(e->e.isP2PRunning());


            System.out.print("\n -> ");
            var ch = scanner.nextLine();

            for (MenuItem item : menuItems) {
                if (item.key.equals(ch)) {
                    item.func.accept(null);
                    break;
                }
            }
            System.out.println("\n[PRESS ENTER TO CONTINUE...]");
            scanner.nextLine();
        }
        System.out.println("Disconnecting client");
        System.exit(0);
    }

    public static void main(String[] args) {

        if (args.length==1) {
            System.out.println("Using configuration "+args[0]);
            Config.loadConfig(args[0]);
        }
        else {
            System.out.println("No config, using default...");
            Config.setDefaults();
        }

        var uvm_client = new UltraViolet(new UVNetworkManager());
    }

}







