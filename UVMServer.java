import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Scanner;

// decouples the usage of UVManager services from actual client, that interacts via socket
public class UVMServer implements Runnable {

    private final UVManager uvm;
    private final int port;

    Scanner is;
    PrintWriter os;

    public void send_to_client(String s) {
       os.println(s); 
       os.flush();
    }

    public UVMServer(UVManager uvm, int port) {
        System.out.println("Creating UVMServer attached to UVM "+uvm);
        this.uvm = uvm;
        this.port = port;
    }

    @Override
    public void run() {
        System.out.println("Starting UVManager socket at port " + port);
        try (var server = new ServerSocket(port)) {
            boolean uvm_on = true;

            // TODO: method to gracefully stop/pause/save
            //noinspection LoopConditionNotUpdatedInsideLoop,ConstantValue
            while (uvm_on) {

                var client = server.accept();
                System.out.println("Accepted connection from " + client.getRemoteSocketAddress());

                is = new Scanner(client.getInputStream());
                os = new PrintWriter(client.getOutputStream());

                boolean disconnect = false;

                while (!disconnect) {
                    String command = is.nextLine();
                    // workaround for removing the null bytes that are received from client checking  connection
                    command = command.replace("\0","");
                    System.out.println("UVM Server: Received command " + command);

                    switch (command) {
                        case "BOOTSTRAP_NETWORK" -> {
                            if (uvm.bootstrapStarted() || uvm.bootstrapCompleted()) {
                                send_to_client("ERROR: network already bootstrapped!");
                            } else {
                                send_to_client("Bootstrap Started, check " + ConfigManager.logfile);
                                //noinspection Convert2MethodRef
                                new Thread(() -> uvm.bootstrapNetwork()).start();
                            }
                            send_to_client("END_DATA");
                        }
                        case "DISCONNECT" -> disconnect = true;
                        case "SHOW_NETWORK" -> showNetworkCmd();
                        case "TEST" -> send_to_client("END_DATA");
                        case "SAVE" -> {
                            String file_to_save = is.nextLine();
                            uvm.saveStatus(file_to_save);
                            send_to_client("END_DATA");
                        }
                        case "LOAD" -> {
                            String file_to_load = is.nextLine();
                            if (uvm.loadStatus(file_to_load))
                                send_to_client("UVM LOADED");
                            else send_to_client("ERROR LOADING UVM from " + file_to_load);
                            send_to_client("END_DATA");
                        }
                        case "MSG_RANDOM_EVENTS" -> {
                            String n = is.nextLine();
                            if (uvm.bootstrapCompleted()) {
                                send_to_client("Generating events, check " + ConfigManager.logfile);
                                uvm.generateRandomEvents(Integer.parseInt(n));
                            } else {
                                send_to_client("Bootstrap not completed, cannot generate events!");
                            }
                            send_to_client("END_DATA");
                        }
                        case "STATUS" -> getStatusCommand();
                        case "SHOW_NODE" -> {
                            String node = is.nextLine();
                            showNodeCommand(node);
                        }
                        case "SHOW_NODES" -> showNodesCommand();
                        case "FREE" -> {
                            uvm.free();
                            send_to_client("END_DATA");
                        }
                        case "ROUTE" -> {
                            String start = is.nextLine();
                            String end = is.nextLine();
                            routeCommand(start, end);
                        }
                        case "IMPORT" -> {
                            //String json = is.nextLine();
                            /*
                             */
                            String json = is.nextLine();
                            send_to_client(json);
                            new Thread(()->uvm.importTopology(json)).start();
                            send_to_client("END_DATA");
                            //importCommand(json);

                            //send_to_client("END_DATA");
                        }
                        case "RESET" -> {
                            uvm.resetUVM();
                            send_to_client("END_DATA");
                        }

                        case "STATS" -> {
                            if (uvm.bootstrapCompleted())  {
                                var max = uvm.getStats().getMaxGraphSizeNode();
                                var min = uvm.getStats().getMinGraphSizeNode();
                                StringBuilder s = new StringBuilder();
                                s.append("Max Graph size:").append(max).append(" (node/channels) ");
                                s.append(max.getChannelGraph().getNodeCount()).append("/").append(max.getChannelGraph().getChannelCount());
                                s.append("\nMin Graph size:").append(min).append(" (node/channels) ");
                                s.append(min.getChannelGraph().getNodeCount()).append("/").append(min.getChannelGraph().getChannelCount());
                                s.append("Average graph size (nodes): "+uvm.getStats().getAverageGraphSize());
                                send_to_client(s.toString());
                            }
                            else send_to_client("Bootstrap not completed!");
                            send_to_client("END_DATA");
                        }
                        default -> {
                            send_to_client("Unknown command __" + command+"_____");
                            send_to_client("END_DATA");
                        }
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void importCommand(String json_file) {
        /*
        if (!uvm.resetUVM()) {
            send_to_client("Cannot reset UVM!");
            send_to_client("END_DATA");
            return;
        }

         */
    }

    private void routeCommand(String start, String end) {
        UVNode start_node = uvm.getUvnodes().get(start);
        UVNode end_node = uvm.getUvnodes().get(end);

        if (start_node==null || end_node==null) {
            send_to_client("NOT FOUND");
            send_to_client("END_DATA");
            return;
        }

        var paths = start_node.getChannelGraph().BFS(start,end);
        if (paths.size()!=0) {
            send_to_client("FOUND");
            for (ArrayList<String> p: paths) {
                send_to_client("\nPATH:");
                for (String n:p) {
                    send_to_client(n+" ");
                }
            }
        }
        else send_to_client("NOT FOUND");

        send_to_client("END_DATA");
    }

    private void getStatusCommand() {
        send_to_client(uvm.toString());
        send_to_client("END_DATA");
    }

    private void showNetworkCmd() {
        if (uvm.getUvnodes().size()==0)
            send_to_client("EMPTY NODE LIST");
        for (UVNode n: uvm.getUvnodes().values()) {
            send_to_client(n.toString());
            for (UVChannel c:n.getUVChannels().values()) {
                send_to_client("\t"+c);
            }
        }
        send_to_client("END_DATA");
    }

    private void showNodesCommand() {
        if (uvm.getUvnodes().size()==0)
            send_to_client("EMPTY NODE LIST");

        uvm.getUvnodes().values().stream().sorted().forEach((n)-> send_to_client(n.toString()));
        send_to_client("END_DATA");

    }
    private void showNodeCommand(String pubkey) {

        if (uvm.getUvnodes().size()==0)
            send_to_client("EMPTY NODE LIST");
        var node = uvm.getUvnodes().get(pubkey);
        if (node==null) {
            send_to_client("ERROR: NODE NOT FOUND");
            send_to_client("END_DATA");
            return;
        }
        send_to_client(node.toString());

        node.getUVChannels().values().stream().sorted().forEach((c)-> send_to_client(c.toString()));
        send_to_client("Peers:");
        node.getPeers().values().stream().sorted().forEach((p)-> send_to_client(p.toString()));

        send_to_client("Channel Graph:");
        send_to_client(node.getChannelGraph().toString());

        int edges = node.getChannelGraph().getChannelCount();
        int vertex = node.getChannelGraph().getNodeCount();
        os.println("Graph nodes:"+vertex);
        os.println("Graph channels:"+edges);
        os.println("Current p2p message queue:"+node.getP2PMsgQueue().size());
        send_to_client("END_DATA");
    }
}
