import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class UVMClient {

    UVManager uvm;


    public UVMClient(int port) {

        //uvm = new UVManager(10,(int)1e6,100*(int)1e6);

        PrintWriter os;
        Scanner is;
        String uvm_server_host = "127.0.0.1";
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
        boolean quit = false;
        boolean uvm_started = false;
        var scanner = new Scanner(System.in);
        while (!quit)  {

            String s;
            System.out.println("-------------------------------------------------");
            System.out.println(" Ultraviolet Client ");
            System.out.println("-------------------------------------------------");
            System.out.println(" (1) Bootstrap Lightning Network nodes");
            System.out.println(" (2) Show network");
            System.out.println(" (4) Show UVManager Status");
            System.out.println(" (5) Show Node Status");
            System.out.println(" (6) Generate Randome events");
            System.out.println(" (T) test");
            System.out.println(" (q) Disconnect client");
            System.out.println("-------------------------------------------------");
            System.out.print(" -> ");

            var ch = scanner.nextLine();

            switch (ch) {
                case "1":
                    if (!uvm_started) {
                        System.out.println("Bootstrapping Network");
                        uvm_started = true;
                        os.println("BOOTSTRAP_NETWORK");
                        os.flush();
                    }
                    else
                        System.out.println("UVM already started!");
                    break;
                case "2":

                    os.println("SHOW_NETWORK");
                    os.flush();

                    while (is.hasNextLine()) {
                        s = is.nextLine();
                        System.out.println(s);
                        if (s.equals("END DATA")) break;
                    }

                    break;
                case "q":
                    os.println("DISCONNECT");
                    os.flush();
                    quit = true;
                    System.out.println("Disconnecting client");
                    break;

                case "4":
                    os.println("STATUS");
                    os.flush();

                    while (is.hasNextLine()) {
                        s = is.nextLine();
                        System.out.println(s);
                        if (s.equals("END DATA")) break;
                    }
                    break;
                case "5":
                    System.out.print("insert node public key:");
                    String node = scanner.nextLine();

                    os.println("SHOW_NODE");
                    os.flush();
                    os.println(node);
                    os.flush();
                    while (is.hasNextLine()) {
                        s = is.nextLine();
                        System.out.println(s);
                        if (s.equals("END DATA")) break;
                    }

                    break;
                case "6":
                    System.out.print("Number of events to generate:");
                    String n = scanner.nextLine();
                    os.println("MSG_RANDOM_EVENTS");
                    os.flush();
                    os.println(n);
                    os.flush();
                    break;
                case "T":
                    os.println("TEST");
                    os.flush();
                    System.out.println("Testing..");
                    break;
            }
        }
    }
    public static void main(String[] args) {

        int port = 7777;
        System.out.println("Connecting client to UVMServer port "+port);
        var uvm_client = new UVMClient(port);
    }

}

