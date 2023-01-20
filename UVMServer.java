import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.Scanner;
import java.util.function.Consumer;

// decouples the usage of UVManager services from actual client, that interacts via socket
public class UVMServer implements Runnable {

    private final UVManager uvm;
    private final int port;

    Scanner is;
    PrintWriter os;

    Consumer<String> send_cmd = x-> { os.println(x); os.flush();};

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
                    System.out.println("UVM Server: Received command " + command);

                    switch (command) {
                        case "BOOTSTRAP_NETWORK":
                            if (!uvm.bootstrapStarted()) {
                                send_cmd.accept("Bootstrap Started, check "+ConfigManager.logfile);
                                //noinspection Convert2MethodRef
                                new Thread(()->uvm.bootstrapNetwork()).start();
                            }
                            else {
                                send_cmd.accept("ERROR: network already bootstrapped!");
                            }
                            send_cmd.accept("END_DATA");
                            break;
                        case "DISCONNECT":
                            disconnect = true;
                            break;
                        case "SHOW_NETWORK":
                            showNetwork();
                            break;
                        case "TEST":
                            send_cmd.accept("END_DATA");
                            break;
                        case "SAVE":
                            String file_to_save = is.nextLine();
                            uvm.save(file_to_save);
                            send_cmd.accept("END_DATA");
                            break;
                        case "LOAD":
                            String file_to_load = is.nextLine();
                            if (uvm.load(file_to_load))
                                send_cmd.accept("UVM LOADED");
                            else send_cmd.accept("ERROR LOADING UVM from "+file_to_load);
                            send_cmd.accept("END_DATA");
                            break;
                        case "MSG_RANDOM_EVENTS":
                            String n = is.nextLine();
                            if (uvm.bootstrapCompleted()) {
                                send_cmd.accept("Generating events, check "+ConfigManager.logfile);
                                uvm.generateRandomEvents(Integer.parseInt(n));
                            }
                            else{
                                send_cmd.accept("Bootstrap not completed, cannot generate events!");
                            }
                            send_cmd.accept("END_DATA");
                            break;
                        case "STATUS":
                            getStatus();
                            break;
                        case "SHOW_NODE":
                            String node = is.nextLine();
                            showNode(node);
                            break;
                        case "SHOW_NODES":
                            showNodes();
                            break;
                        case "RESET":
                            uvm.resetUVM();
                            break;
                        default:
                            send_cmd.accept("Unknown command "+command);
                            send_cmd.accept("END_DATA");
                            break;
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void getStatus() {
        send_cmd.accept(uvm.toString());
        send_cmd.accept("END_DATA");
    }

    public void showNetwork() {
        if (uvm.getUVnodes().size()==0)
            send_cmd.accept("EMPTY NODE LIST");
        for (UVNode n: uvm.getUVnodes().values()) {
            send_cmd.accept(n.toString());
            for (UVChannel c:n.getUVChannels().values()) {
                send_cmd.accept("\t"+c);
            }
        }
        send_cmd.accept("END_DATA");
    }

    public void showNodes() {
        if (uvm.getUVnodes().size()==0)
            send_cmd.accept("EMPTY NODE LIST");
        for (UVNode n: uvm.getUVnodes().values()) {
            send_cmd.accept(n.toString());
        }
        send_cmd.accept("END_DATA");

    }
    public void showNode(String pubkey) {

        if (uvm.getUVnodes().size()==0) 
            send_cmd.accept("EMPTY NODE LIST");
        var node = uvm.getUVnodes().get(pubkey);
        if (node==null) {
            send_cmd.accept("ERROR: NODE NOT FOUND");
            send_cmd.accept("END_DATA");
            return;
        }
        send_cmd.accept(node.toString());
        for (UVChannel c:node.getUVChannels().values()) {
            send_cmd.accept(c.toString());
        }
        send_cmd.accept("Peers:");
        for (P2PNode n:node.getPeers().values()) {
            send_cmd.accept(n.toString());
        }
        if (ConfigManager.verbose) {
            send_cmd.accept("Channel Graph:");
            send_cmd.accept(node.getChannelGraph().toString());
        }

        int edges = node.getChannelGraph().getChannelCount();
        int vertex = node.getChannelGraph().getNodeCount();
        os.println("Graph nodes:"+vertex);
        os.println("Graph channels:"+edges);
        send_cmd.accept("END_DATA");
    }
}
