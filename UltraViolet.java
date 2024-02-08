import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;


public class UltraViolet {

    private final UVConfig configuration;
    private final UVNetworkManager networkManager;
    boolean quit = false;
    private String imported_graph_root;


    private void showNodeCommand(String pubkey) {

        if (networkManager.getUVNodeList().isEmpty()) {
            System.out.println("EMPTY NODE LIST");
            return;
        }
        var node = networkManager.getNode(pubkey);
        if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }

        node.getChannels().values().stream().sorted().forEach(System.out::println);

        int edges = node.getChannelGraph().getChannelCount();
        int vertex = node.getChannelGraph().getNodeCount();
        System.out.println("Graph nodes:" + vertex);
        System.out.println("Graph channels:" + edges);
        node.showQueuesStatus();
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

        var paths = networkManager.getNode(start).getPaths(destination,stopfirst);

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
            if (i%20 ==0 ) System.out.println();
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        System.out.println("Done!");
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
        var g = networkManager.getNode(node_id).getChannelGraph();
        System.out.println(g);
    }

    public UltraViolet(UVConfig config) {
        this.configuration = config;
        this.networkManager = new UVNetworkManager(config);

        ArrayList<MenuItem> menuItems = new ArrayList<>();
        var scanner = new Scanner(System.in);

        menuItems.add(new MenuItem("boot", "Bootstrap Lightning Network from scratch", (x) -> {
            if (networkManager.isBootstrapStarted() || networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: network already bootstrapped!");
            } else {
                System.out.println("Bootstrap Started, check " + configuration.getStringProperty("logfile"));
                var bootstrap_exec= Executors.newSingleThreadExecutor();
                Future bootstrap = bootstrap_exec.submit(networkManager::bootstrapNetwork);
                System.out.println("waiting bootstrap to finish...");
                _waitForFuture(bootstrap);
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
        menuItems.add(new MenuItem("t", "Start/Stop Timechain and P2P messages", (x) -> {
            if (!networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: must execute bootstrap or load/import a network!");
                return;
            }
            if (!networkManager.isTimechainRunning()) {
                System.out.println("Starting Timechain, check " + configuration.getStringProperty("logfile"));
                networkManager.setTimechainRunning(true);
                networkManager.startP2PNetwork();
            }
            else {
                System.out.print("Waiting for Timechain services to stop...");
                networkManager.setTimechainRunning(false);
                networkManager.stopP2PNetwork();
            }
        }));

        menuItems.add(new MenuItem("all", "Show Nodes and Channels", x -> {
            if (networkManager.getUVNodeList().isEmpty()) {
                System.out.println("EMPTY NODE LIST");
                return;
            }
            var ln = networkManager.getUVNodeList().values().stream().sorted().toList();

            for (UVNode n : ln) {
                System.out.println(n);
                n.getChannels().values().forEach(System.out::println);
            }
        }));

        menuItems.add(new MenuItem("nodes", "Show All Nodes ", x -> networkManager.getUVNodeList().values().stream().sorted().forEach(System.out::println)));
        menuItems.add(new MenuItem("node", "Show Node ", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showNodeCommand(node);
        }));
        menuItems.add(new MenuItem("graph", "Show Node Graph", x -> {
            System.out.print("Insert node public key:");
            String node = scanner.nextLine();
            showGraphCommand(node);
        }));
        menuItems.add(new MenuItem("p2pq", "Show Node Queues", x -> {
            if (!networkManager.isBootstrapCompleted()) return;
            System.out.print("Insert node public key:");
            String node_id = scanner.nextLine();
            var node = networkManager.getNode(node_id);
            if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }
            showQueueCommand(node);
        }));
        menuItems.add(new MenuItem("qs", "Show Queues Status", x -> {
            if (!networkManager.isBootstrapCompleted()) return;
            System.out.println("Showing not empty queues...");

            for (UVNode n: networkManager.getUVNodeList().values()) {
                n.showQueuesStatus();
            }
        }));
        /*
        menuItems.add(new MenuItem("test", "TEST", x -> {
            System.out.println("HI!");
        }));

         */

        menuItems.add(new MenuItem("conf", "Show Configuration ", x -> {
            System.out.println("-----------------------------------");
            System.out.println(configuration);
            System.out.println("-----------------------------------");
        }));
        menuItems.add(new MenuItem("inv", "Generate Invoice Events ", x -> {

            if (!networkManager.isBootstrapCompleted()) {
                System.out.println("Bootstrap not completed, cannot generate events!");
                return;
            }

            if (!networkManager.isTimechainRunning()) {
                System.out.println("Timechain not running, please start the timechain");
                return;
            }

            System.out.print("Injection Rate (node events per block):");
            double node_events_per_block = Double.parseDouble(scanner.nextLine());
            System.out.print("Timechain duration (blocks): ");
            int n_blocks = Integer.parseInt(scanner.nextLine());
            System.out.println("Min amount");
            int amt_min = Integer.parseInt(scanner.nextLine());
            System.out.println("Max amount");
            int amt_max = Integer.parseInt(scanner.nextLine());
            System.out.println("Max fees");
            int fees = Integer.parseInt(scanner.nextLine());

            networkManager.generateInvoiceEvents(node_events_per_block,n_blocks,amt_min,amt_max,fees);

        }));


        menuItems.add(new MenuItem("rand", "Generate Random Events ", x -> {
            System.out.print("Number of events:");
            String n = scanner.nextLine();
            if (networkManager.isBootstrapCompleted()) {
                System.out.println("Generating events, check " + configuration.getStringProperty("logfile"));
                networkManager.generateRandomEvents(Integer.parseInt(n));
            } else {
                System.out.println("Bootstrap not completed, cannot generate events!");
            }
        }));

        menuItems.add(new MenuItem("rep", "Invoice Reports", x -> {
            if (networkManager.isBootstrapCompleted())  {
                System.out.println(networkManager.getStats().generateInvoiceReport());
            }
            else System.out.println("Bootstrap not completed!");
        } ));

        menuItems.add(new MenuItem("nets", "Show Network Stats", x -> {
            if (networkManager.isBootstrapCompleted())  {
                System.out.println(networkManager.getStats().generateNetworkReport());
            }
            else System.out.println("Bootstrap not completed!");
        } ));

        menuItems.add(new MenuItem("wr", "Write Reports", x -> {

            if (networkManager.isBootstrapCompleted())  {
                System.out.print("Enter description prefix:");
                var prefix = new Scanner(System.in).nextLine();
                var s = new StringBuilder(prefix).append(".");
                SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmm");
                s.append(sdf.format(new Date())).append(".csv");
                var rep = networkManager.getStats().generateNetworkReport();
                var rep2 = networkManager.getStats().generateInvoiceReport();

                try {
                    var fw = new FileWriter(s.toString());
                    fw.write(rep);
                    fw.write(rep2);
                    fw.close();
                    System.out.println("Written "+s);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }

            }
            else System.out.println("Bootstrap not completed!");
        } ));

        menuItems.add(new MenuItem("path", "Get routing paths between nodes", x -> findPathsCmd()));
        menuItems.add(new MenuItem("route", "Route Payment", x -> testInvoiceRoutingCmd()));
        //menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> { networkManager.resetUVM(); }));
        //menuItems.add(new MenuItem("free", "Try to free memory", x -> { System.gc(); }));
        menuItems.add(new MenuItem("save", "Save UV Network Status", x -> {
            System.out.print("Save to:");
            String file_to_save = scanner.nextLine();
            System.out.println("Start saving status, please wait... ");
            networkManager.saveStatus(file_to_save);
        }));
        menuItems.add(new MenuItem("load", "Load UV Network Status", x -> {
            System.out.print("Load from:");
            String file_to_load = scanner.nextLine();
            if (networkManager.loadStatus(file_to_load))
                System.out.println("UVM LOADED");
            else System.out.println("ERROR LOADING UVM from " + file_to_load);
        }));
        menuItems.add(new MenuItem("q", "Quit ", x -> quit = true));


        while (!quit) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println("__________________________________________________");
            System.out.println(" U l t r a v i o l e t ");
            System.out.println("__________________________________________________");
            menuItems.forEach(System.out::println);
            System.out.println("__________________________________________________");
            System.out.print("Timechain: "+networkManager.getTimechain().getCurrentBlock());
            if (!networkManager.getTimechain().isRunning()) System.out.println(" (NOT RUNNING)");
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
            System.out.println("\n[ Press ENTER to continue... ]");
            scanner.nextLine();
        }
        System.out.println("Exiting...");
        System.exit(0);
    }

    public static void main(String[] args) {

        var configuration = new UVConfig();

        if (args.length==1) {
            configuration.loadConfig(args[0]);
        }
        else {
            System.out.println("No config, using default...");
            configuration.setDefaults();
        }

        var uvm_client = new UltraViolet(configuration);
    }

}







