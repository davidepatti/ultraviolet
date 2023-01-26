import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

public class UVMClient {

    PrintWriter os;
    Scanner is;
    boolean quit = false;
    Socket client;
    final String UVM_SERVER_HOST = "127.0.0.1";
    final int PORT = 7777;

    final Consumer<String> send_cmd = x-> { os.println(x); os.flush();};
    final Consumer<String> wait_msg = (x)-> {
        String s;
        while (is.hasNextLine() && !((s=is.nextLine()).equals(x))) {
            System.out.println(s);
        }
    };

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
            while (s.toString().length()<8) s.append(" ");
            s.append("- ").append(desc);

            entry = s.toString();
        }

        @Override
        public String toString() {
            return entry;
        }
    }
    /**
     * Client
     */
    public UVMClient() {
        ArrayList<MenuItem> menuItems = new ArrayList<>();
        var scanner = new Scanner(System.in);

        //var executor = Executors.newSingleThreadScheduledExecutor();
        //executor.scheduleAtFixedRate(()->initConnection(UVM_SERVER_HOST,PORT),0,2, TimeUnit.SECONDS);
        initConnection(UVM_SERVER_HOST,PORT);

        menuItems.add(new MenuItem("boot", "Bootstrap Lightning Network from scratch", (x) -> {
            send_cmd.accept("BOOTSTRAP_NETWORK");
            wait_msg.accept("END_DATA");
        }));

        menuItems.add(new MenuItem("all", "Show All newtork Nodes and Channels", x -> {
            send_cmd.accept("SHOW_NETWORK");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("nodes", "Show Nodes ", x -> {
            send_cmd.accept("SHOW_NODES");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("q", "Disconnect Client ", x -> {
            send_cmd.accept("DISCONNECT");
            quit = true;
        }));
        menuItems.add(new MenuItem("status", "UVM Status ", x -> {
            send_cmd.accept("STATUS");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("node", "Show a single Node ", x -> {
            System.out.print("insert node public key:");
            send_cmd.accept("SHOW_NODE\n" + scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("rand", "Generate Random Events ", x -> {
            System.out.print("Number of events:");
            send_cmd.accept("MSG_RANDOM_EVENTS\n" + scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("r", "Reconnect to Server", x -> {
            initConnection(UVM_SERVER_HOST,PORT);
        }));
        menuItems.add(new MenuItem("route", "Get routing paths between nodes", x -> {
            System.out.print("Starting node public key:");
            String start = scanner.nextLine();
            System.out.print("End node public key:");
            String end = scanner.nextLine();
            send_cmd.accept("ROUTE\n" + start + "\n" + end);
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("save", "Save UVM status", x -> {
            System.out.print("Save to:");
            send_cmd.accept("SAVE\n" + scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("load", "Load UVM status", x -> {
            System.out.print("Load from:");
            send_cmd.accept("LOAD\n" + scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("stats", "Show Global stats", x -> {
            send_cmd.accept("STATS");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("reset", "Reset the UVM (experimental)", x -> {
            send_cmd.accept("RESET");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("free", "Try to free memory", x -> {
            send_cmd.accept("FREE");
            wait_msg.accept("END_DATA");
        }));



        while (!quit)  {
            System.out.print("\033[H\033[2J");  
            System.out.flush();
            System.out.println("-------------------------------------------------");
            System.out.println(" Ultraviolet Client ");
            System.out.println("-------------------------------------------------");
            menuItems.stream().forEach(System.out::println);
            System.out.println("-------------------------------------------------");
            if (isConnected(client)) {
                System.out.println("Connected to "+client.getRemoteSocketAddress().toString());
                System.out.print(" -> ");
                var ch = scanner.nextLine();

                for (MenuItem item:menuItems) {
                    if (item.key.equals(ch)) {
                        item.func.accept(null);
                        break;
                    }
                }
                System.out.println("\n[PRESS ENTER TO CONTINUE...]");
                scanner.nextLine();
            }
            else {
                System.out.println("NOT CONNECTED! (start server and press enter to rety)");
                scanner.nextLine();
                initConnection(UVM_SERVER_HOST,PORT);
            }
        }
        System.out.println("Disconnecting client");


    }

    public static boolean isConnected(Socket socket) {
        if (socket==null) return false;
        try {
            socket.setSoTimeout(1000);
            socket.getOutputStream().write(0);
            return true;
        } catch (SocketTimeoutException ste) {
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    private void initConnection(String uvm_server_host, int port) {
        if (isConnected(client)) return;

        try {
            client = new Socket(uvm_server_host, port);
            is = new Scanner(client.getInputStream());
            os = new PrintWriter(client.getOutputStream());
            System.out.println("Connected to UVM Server "+client.getRemoteSocketAddress().toString());
        } catch (IOException e) {
            System.out.println("Cannot connect to UVMServer "+ uvm_server_host +":"+ port);

            //System.exit(-1);
            //throw new RuntimeException(e);
        }
    }



    public static void main(String[] args) {

        System.out.println("Connecting client to UVMServer ");
        var uvm_client = new UVMClient();
    }
}







