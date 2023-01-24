import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.*;
import java.util.function.Consumer;

public class UVMClient {

    private final String UVM_SERVER_HOST = "127.0.0.1";
    private final int PORT = 7777;
    PrintWriter os;
    Scanner is;
    boolean quit = false;

    Consumer<String> send_cmd = x-> { os.println(x); os.flush();};
    Consumer<String> wait_msg = (x)-> {
        String s;
        while (is.hasNextLine() && !((s=is.nextLine()).equals(x))) {
            System.out.println(s);
            //System.out.println(s = is.nextLine());
            //if (s.equals(x)) break;
        }
    };

    private class MenuItem {
        public final String key, description;
        private String entry;

        public Consumer<Void> func;

        public MenuItem(String key, String desc, Consumer<Void> func) {
            this.key = key;
            this.description = desc;
            this.func = func;
            StringBuilder s = new StringBuilder();
            s.append(key);
            while (s.toString().length()<8) s.append(" ");
            s.append("- "+ desc);

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
        initConnection(UVM_SERVER_HOST, PORT);

        menuItems.add(new MenuItem("boot","Bootstrap Lightning Network from scratch",(x)-> {
            send_cmd.accept("BOOTSTRAP_NETWORK");
            wait_msg.accept("END_DATA");
        }));

        menuItems.add(new MenuItem("all","Show All newtork Nodes and Channels",x-> {
            send_cmd.accept("SHOW_NETWORK");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("nodes","Show Nodes ",x-> {
            send_cmd.accept("SHOW_NODES");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("q","Disconnect Client ",x-> {
            send_cmd.accept("DISCONNECT");
            quit = true;
        }));
        menuItems.add(new MenuItem("status","UVM Status ",x-> {
            send_cmd.accept("STATUS");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("node","Show a single Node ",x-> {
            System.out.print("insert node public key:");
            send_cmd.accept("SHOW_NODE\n"+scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("rand","Generate Random Events ",x-> {
            System.out.print("Number of events:");
            send_cmd.accept("MSG_RANDOM_EVENTS\n"+scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("x","Test", x-> {
            send_cmd.accept("TEST");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("route","Get routing paths between nodes",x-> {
            System.out.print("Starting node public key:");
            String start = scanner.nextLine();
            System.out.print("End node public key:");
            String end = scanner.nextLine();
            send_cmd.accept("ROUTE\n"+start+"\n"+end);
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("save","Save UVM status", x-> {
            System.out.print("Save to:");
            send_cmd.accept("SAVE\n"+scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("load","Load UVM status", x-> {
            System.out.print("Load from:");
            send_cmd.accept("LOAD\n"+scanner.nextLine());
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("reset","Reset the UVM (experimental)", x-> {
            send_cmd.accept("RESET");
            wait_msg.accept("END_DATA");
        }));
        menuItems.add(new MenuItem("free","Try to free memory", x-> {
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
        System.out.println("Disconnecting client");


    }

    private void initConnection(String uvm_server_host, int port) {
        System.out.println("Connecting to server at "+uvm_server_host+" port "+port);
        try {
            Socket client = new Socket(uvm_server_host, port);
            is = new Scanner(client.getInputStream());
            os = new PrintWriter(client.getOutputStream());
            System.out.println("Connected to UVM Server");
        } catch (IOException e) {
            System.out.println("Cannot connect to UVMServer "+ uvm_server_host +":"+ port);
            System.exit(-1);
            throw new RuntimeException(e);
        }
    }

    public static void main(String[] args) {

        System.out.println("Connecting client to UVMServer ");
        var uvm_client = new UVMClient();
    }
}







