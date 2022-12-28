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
                            if (!uvm.isBoostrapped())
                                uvm.bootstrapNetwork();
                            else System.out.println("Error: network already bootstrapped!");
                            break;
                        case "DISCONNECT":
                            System.out.println("Disconnecting client");
                            disconnect = true;
                            break;
                        case "SHOW_NETWORK":
                            System.out.println("Showing network");
                            showNetwork();
                            break;
                        case "SHUTDOWN":
                            System.out.println("Shutting down");
                            break;
                    }
                }
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void showNetwork() {

        os.println("BEGIN DATA");
        os.flush();
        for (Node n: uvm.getNodeSet()) {
            os.println(n);
            os.flush();
            for (Channel c:n.getChannels()) {
                os.println(c);
                os.flush();
            }
        }
        os.println("END DATA");
        os.flush();
    }
}
