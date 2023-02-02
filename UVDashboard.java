import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.stream.Collectors;


public class UVDashboard {

    private final UVNetworkManager networkManager;
    boolean quit = false;
    private String imported_graph_root;
    /**
     *
     */
    /**
     *
     * @param pubkey
     */
    private void showNodeCommand(String pubkey) {

        if (networkManager.getUVNodes().size() == 0)
            System.out.println("EMPTY NODE LIST");
        var node = networkManager.getUVNodes().get(pubkey);
        if (node == null) { System.out.println("ERROR: NODE NOT FOUND"); return; }

        node.getUVChannels().values().stream().sorted().forEach(System.out::println);

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

    /**
     *
     */
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
        String start = scanner.nextLine();
        System.out.print("Destination node public key:");
        String end = scanner.nextLine();

        var paths = findPathList(start,end,true);

        var dest = networkManager.getUVNodes().get(end);
        var invoice = dest.generateInvoice(700);






    }

    /**
     *
     * @param start
     * @param end
     * @param stopfirst
     * @return
     */
    private ArrayList<ArrayList<String>> findPathList(String start, String end, boolean stopfirst) {

        UVNode start_node = networkManager.getUVNodes().get(start);
        UVNode end_node = networkManager.getUVNodes().get(end);

        if (start_node==null || end_node==null) {
            System.out.println("NOT FOUND");
            return null;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<ArrayList<ArrayList<String>>> arrayListFuture = executor.submit(()->{
            return start_node.getChannelGraph().findPath(start,end,stopfirst);
        });

        System.out.print("Waiting for path finding...");

        wait(arrayListFuture);

        ArrayList<ArrayList<String>> paths = null;
        try {
            paths = arrayListFuture.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }

        return paths;
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
        String end = scanner.nextLine();
        System.out.print("Single[1] or All [any key] paths?");
        String choice = scanner.nextLine();
        boolean stopfirst = choice.equals("1");

        var paths = findPathList(start,end,stopfirst);

        if (paths.size()!=0) {
            for (ArrayList<String> p: paths) {
                System.out.println("PATH: ");
                for (String n:p) {
                    System.out.print(n+" ");
                }
            }
        }
        else System.out.println("NO PATH FOUND");
    }


    /**
     *
     * @param f
     */
    private void wait(Future f) {
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
                System.out.println("Bootstrap Started, check " + ConfigManager.logfile);
                ExecutorService bootstrap_exec= Executors.newSingleThreadExecutor();
                Future bootstrap = bootstrap_exec.submit(()->networkManager.bootstrapNetwork());
                System.out.println("waiting bootstrap to finish...");
                wait(bootstrap);
            }
        }));
        menuItems.add(new MenuItem("p2p", "Start P2P Network", (x) -> {
            if (!networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: must bootstrap network or load/import a network!");
                return;
            }
            if (networkManager.isP2pRunning()) {
                System.out.println("P2P already running...");
                return;
            }
            new Thread(networkManager.getTimechain()).start();
            System.out.println("Starting P2P, check " + ConfigManager.logfile);
            new Thread(()->networkManager.startP2PNetwork()).start();
        }));
        menuItems.add(new MenuItem("p2ps", "Stop P2P Network", (x) -> {
            if (!networkManager.isBootstrapCompleted()) {
                System.out.println("ERROR: must bootstrap network or load/import a network!");
                return;
            }
            if (!networkManager.isP2pRunning()) {
                System.out.println("P2P not running...");
                return;
            }
            System.out.print("Waiting for P2P services to stop...");
            networkManager.getTimechain().stop();
            networkManager.stopP2PNetwork();
        }));

        menuItems.add(new MenuItem("all", "Show All newtork Nodes and Channels", x -> {
            if (networkManager.getUVNodes().size()== 0) {
                System.out.println("EMPTY NODE LIST");
                return;
            }
            var ln = networkManager.getUVNodes().values().stream().sorted().collect(Collectors.toList());

            for (UVNode n : ln) {
                System.out.println(n);
                n.getUVChannels().values().stream().forEach(System.out::println);
            }
        }));

        menuItems.add(new MenuItem("nodes", "Show Nodes ", x -> {
            networkManager.getUVNodes().values().stream().sorted().forEach(System.out::println);
        }));

        menuItems.add(new MenuItem("status", "UVM Status ", x -> {
            System.out.println(networkManager.getStatusString());
        }));

        menuItems.add(new MenuItem("node", "Show a single Node ", x -> {
            System.out.print("insert node public key:");
            String node = scanner.nextLine();
            showNodeCommand(node);
        }));

        menuItems.add(new MenuItem("rand", "Generate Random Events ", x -> {
            System.out.print("Number of events:");
            String n = scanner.nextLine();
            if (networkManager.isBootstrapCompleted()) {
                System.out.println("Generating events, check " + ConfigManager.logfile);
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
        menuItems.add(new MenuItem("path", "Get routing paths between nodes", x -> {
            findPathsCmd();
        }));
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
        menuItems.add(new MenuItem("stats", "Show Global stats", x -> {
            if (networkManager.isBootstrapCompleted())  {
                var max = networkManager.getStats().getMaxGraphSizeNode();
                var min = networkManager.getStats().getMinGraphSizeNode();
                StringBuilder s = new StringBuilder();
                s.append("Max Graph size:").append(max).append(" (node/channels) ");
                s.append(max.getChannelGraph().getNodeCount()).append("/").append(max.getChannelGraph().getChannelCount());
                s.append("\nMin Graph size:").append(min).append(" (node/channels) ");
                s.append(min.getChannelGraph().getNodeCount()).append("/").append(min.getChannelGraph().getChannelCount());
                s.append("Average graph size (nodes): "+ networkManager.getStats().getAverageGraphSize());
                System.out.println(s);
            }
            else System.out.println("Bootstrap not completed!");
        } ));
        menuItems.add(new MenuItem("route", "Route Payment", x -> {
            routeCmd();
        }));

        menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> {
            networkManager.resetUVM();
        }));
        menuItems.add(new MenuItem("free", "Try to free memory", x -> {
            System.gc();
        }));
        menuItems.add(new MenuItem("q", "Disconnect Client ", x -> {
            quit = true;
        }));


        while (!quit) {
            System.out.print("\033[H\033[2J");
            System.out.flush();
            System.out.println("-------------------------------------------------");
            System.out.println(" Ultraviolet Dashboard ");
            System.out.println("-------------------------------------------------");
            menuItems.stream().forEach(System.out::println);
            System.out.println("-------------------------------------------------");
            System.out.print("Timechain :"+networkManager.getTimechain().getCurrent_block());
            if (!networkManager.getTimechain().isRunning()) System.out.println("[Not running]");
            System.out.println("-------------------------------------------------");

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
            ConfigManager.loadConfig(args[0]);
        }
        else {
            System.out.println("No config, using default...");
            ConfigManager.setDefaults();
        }

        var uvm_client = new UVDashboard(new UVNetworkManager());
    }

}







