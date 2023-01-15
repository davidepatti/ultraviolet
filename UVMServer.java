import java.io.IOException;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.util.Scanner;

// decouples the usage of UVManager services from actual client, that interacts via socket
public class UVMServer implements Runnable {

    private final UVManager uvm;
    private final int port;

    Scanner is;
    PrintWriter os;

    public UVMServer(UVManager uvm, int port) {
        System.out.println("Creating UVMServer attached to UVM "+uvm);
        this.uvm = uvm;
        this.port = port;
    }

    @Override
    public void run() {
        System.out.println("Starting UVManager socket at port " + port);
        try (var server = new ServerSocket(port);) {
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
                            os.println("BEGIN DATA");
                            os.flush();
                            if (!uvm.isBoostrapped()) {
                                new Thread(()->uvm.bootstrapNetwork()).start();
                            }
                            else System.out.println("Error: network already bootstrapped!");
                            os.println("END DATA");
                            os.flush();
                            break;
                        case "DISCONNECT":
                            System.out.println("Disconnecting client");
                            disconnect = true;
                            break;
                        case "SHOW_NETWORK":
                            showNetwork();
                            break;
                        case "TEST":
                            break;
                        case "MSG_RANDOM_EVENTS":
                            String n = is.nextLine();
                            uvm.generateRandomEvents(Integer.parseInt(n));
                            break;
                        case "STATUS":
                            getStatus();
                            break;
                        case "SHOW_NODE":
                            String node = is.nextLine();
                            System.out.println("Showing node "+node);
                            showNode(node);
                            break;
                        case "SHOW_NODES":
                            showNodes();
                            break;
                        default:
                            System.out.println("Unknown command "+command);
                            break;
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void getStatus() {
        os.println("BEGIN DATA");
        os.flush();
        os.println("UVManager Status:");
        os.println(uvm);
        os.flush();
        os.println("Node Bootstrap status: "+(UVConfig.total_nodes-uvm.bootstrap_latch.getCount())+"/"+UVConfig.total_nodes);
        os.flush();
        os.println("END DATA");
        os.flush();
    }

    public void showNetwork() {

        os.println("BEGIN DATA");
        os.flush();
        for (UVNode n: uvm.getNodeMap().values()) {
            os.println(n);
            os.flush();
            for (UVChannel c:n.getUVChannels().values()) {
                os.println("\t"+c);
                os.flush();
            }
        }
        os.println("END DATA");
        os.flush();
    }

    public void showNodes() {
        os.println("BEGIN DATA");
        os.flush();
        for (UVNode n: uvm.getNodeMap().values()) {
            os.println(n);
            os.flush();
        }
        os.println("END DATA");
        os.flush();

    }
    public void showNode(String pubkey) {

        os.println("BEGIN DATA");
        os.flush();
        var node = uvm.getNodeMap().get(pubkey);
        os.println(node);
        os.flush();
        for (UVChannel c:node.getUVChannels().values()) {
            os.println(c);
            os.flush();
        }
        os.println("Peers:");
        os.flush();
        for (UVNode n:node.getPeers().values()) {
            os.println(n);
            os.flush();
        }
        os.println("Channel Graph:");
        os.println(node.getP2PNode().getChannel_graph().toString());
        os.flush();
        os.println("END DATA");
        os.flush();
    }
}
