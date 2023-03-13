import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;


public class UVDashboard {

    private final UVNetworkManager networkManager;
    boolean quit = false;
    private String imported_graph_root;

    private void showNodeCommand(String pubkey) {

        if (networkManager.getUVNodes().size() == 0)
            System.out.println("EMPTY NODE LIST");
        var node = networkManager.getUVNodes().get(pubkey);
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
        if (node.getP2PMsgQueue() != null)
            System.out.println("Current p2p message queue:" + node.getP2PMsgQueue().size());
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
    private void routeCmd() {

        Scanner scanner = new Scanner(System.in);
        System.out.print("Starting node public key:");
        String start_id = scanner.nextLine();
        System.out.print("Destination node public key:");
        String end_id = scanner.nextLine();

        var dest = networkManager.getUVNodes().get(end_id);
        var sender = networkManager.getUVNodes().get(start_id);
        var invoice = dest.generateInvoice(2000);
        System.out.println("Generated Invoice: "+invoice);
        sender.payInvoice(invoice);
    }

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
        String end = scanner.nextLine();
        System.out.print("Single[1] or All [any key] paths?");
        String choice = scanner.nextLine();
        boolean stopfirst = choice.equals("1");

        var paths = networkManager.getUVNodes().get(start).findPath(start,end,stopfirst);

        if (paths.size()>0) {
            for (ArrayList<ChannelGraph.Edge> p: paths) {
                System.out.println("PATH------------------------------------- ");
                p.forEach(System.out::println);
            }
        }
        else System.out.println("NO PATH FOUND");
    }


    /**
     *
     * @param f
     */
    private void _waitForFuture(Future f) {
        while (!f.isDone()) {
            System.out.print(".");
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Done!");
    }

    /**
     *
     * @param nm Network Manager instance controlled by the dashboard
     */
    public UVDashboard(UVNetworkManager nm) {
        networkManager = nm;
        ArrayList<MenuItem> menuItems = new ArrayList<>();
        var scanner = new Scanner(System.in);

        menuItems.add(new MenuItem("boot", "Bootstrap Lightning Network from scratch", (x) -> {
            if (networkManager.isBootstrapStarted() || networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: network already bootstrapped!");
            } else {
                System.out.println("Bootstrap Started, check " + Config.get("logfile"));
                ExecutorService bootstrap_exec= Executors.newSingleThreadExecutor();
                Future bootstrap = bootstrap_exec.submit(networkManager::bootstrapNetwork);
                System.out.println("waiting bootstrap to finish...");
                _waitForFuture(bootstrap);
            }
        }));
        menuItems.add(new MenuItem("t", "Start/Stop Timechain", (x) -> {
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

        menuItems.add(new MenuItem("all", "Show All newtork Nodes and Channels", x -> {
            if (networkManager.getUVNodes().size()== 0) {
                System.out.println("EMPTY NODE LIST");
                return;
            }
            var ln = networkManager.getUVNodes().values().stream().sorted().toList();

            for (UVNode n : ln) {
                System.out.println(n);
                n.getChannels().values().forEach(System.out::println);
            }
        }));

        menuItems.add(new MenuItem("nodes", "Show Nodes ", x -> networkManager.getUVNodes().values().stream().sorted().forEach(System.out::println)));
        menuItems.add(new MenuItem("node", "Show a single Node ", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showNodeCommand(node);
        }));
        menuItems.add(new MenuItem("graph", "Show a node graph", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showGraphCommand(node);
        }));
        menuItems.add(new MenuItem("p2pq", "Show node queue", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showQueueCommand(node);
        }));

        menuItems.add(new MenuItem("conf", "Show configuration ", x -> {
            System.out.println("-----------------------------------");
            Config.print();
            System.out.println("-----------------------------------");
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

        menuItems.add(new MenuItem("import", "Import Network Topology", x -> {
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
        menuItems.add(new MenuItem("stats", "Show Global stats", x -> {
            if (networkManager.isBootstrapCompleted())  {
                var max = networkManager.getStats().getMaxGraphSizeNode();
                var min = networkManager.getStats().getMinGraphSizeNode();
                String s = "Max Graph size:" + max + " (node/channels) " +
                        max.getChannelGraph().getNodeCount() + "/" + max.getChannelGraph().getChannelCount() +
                        "\nMin Graph size:" + min + " (node/channels) " +
                        min.getChannelGraph().getNodeCount() + "/" + min.getChannelGraph().getChannelCount() +
                        "Average graph size (nodes): " + networkManager.getStats().getAverageGraphSize();
                System.out.println(s);
            }
            else System.out.println("Bootstrap not completed!");
        } ));
        menuItems.add(new MenuItem("path", "Get routing paths between nodes", x -> findPathsCmd()));
        menuItems.add(new MenuItem("route", "Route Payment", x -> routeCmd()));
        menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> { networkManager.resetUVM(); }));
        menuItems.add(new MenuItem("free", "Try to free memory", x -> { System.gc(); }));
        menuItems.add(new MenuItem("save", "Save UVM status", x -> {
            System.out.print("Save to:");
            String file_to_save = scanner.nextLine();
            networkManager.saveStatus(file_to_save);
        }));
        menuItems.add(new MenuItem("load", "Load UVM status", x -> {
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

    private void showQueueCommand(String node_id) {
        if (!networkManager.isBootstrapCompleted()) return;
        var node = networkManager.getUVNodes().get(node_id);
        if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }
        System.out.println("P2P message queue:");
        System.out.println("-------------------------------------------------------------");
        node.getP2PMessageQueue().forEach(System.out::println);

        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending HTLC:");
        node.getReceivedHTLC().values().stream().forEach(System.out::println);

        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending opening:");

        node.getSentChannelOpenings().values().stream().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending accepted:");
        node.getChannelsAcceptedQueue().stream().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending to accept:");
        node.getChannelsToAcceptQueue().stream().forEach(System.out::println);
        System.out.println("-------------------------------------------------------------");
        System.out.println("Pending Invoices:");
        node.getGeneratedInvoices().values().stream().forEach(System.out::println);
    }

    private void showGraphCommand(String node) {

        if (!networkManager.isBootstrapCompleted()) return;
        var g = networkManager.getUVNodes().get(node).getChannelGraph();
        System.out.println(g);
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

        var uvm_client = new UVDashboard(new UVNetworkManager());
    }

}







