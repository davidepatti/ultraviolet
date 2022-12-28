import java.io.IOException;
import java.io.PrintWriter;
import java.net.Socket;
import java.util.Scanner;

public class UVMClient {

    UVManager uvm;

    private final String uvm_server_host = "127.0.0.1";
    private int uvm_server_port = 7777;


    private Scanner is;
    private PrintWriter os;
    private Socket client;


    public UVMClient(int port) {

        this.uvm_server_port = port;
        //uvm = new UVManager(10,(int)1e6,100*(int)1e6);

        try {
            client = new Socket(uvm_server_host,uvm_server_port);
            is = new Scanner(client.getInputStream());
            os = new PrintWriter(client.getOutputStream());
            System.out.println("Connected to UVM Server");
        } catch (IOException e) {
            System.out.println("Cannot connect to UVMServer "+uvm_server_host+":"+uvm_server_port);
            throw new RuntimeException(e);
        }
        boolean quit = false;
        boolean uvm_started = false;
        var scanner = new Scanner(System.in);
        while (!quit)  {
            System.out.println("-------------------------------------------------");
            System.out.println(" Ultraviolet Client ");
            System.out.println("-------------------------------------------------");
            System.out.println(" (1) Bootstrap Lightning Network nodes");
            System.out.println(" (2) Show network");
            System.out.println(" (3) Disconnect client");
            System.out.println(" (4) Shutdown UV manager");
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

                    String s;

                    while (is.hasNextLine()) {
                        s = is.nextLine();
                        System.out.println(s);
                        if (s.equals("END DATA")) break;
                    }

                    break;
                case "3":
                    os.println("DISCONNECT");
                    os.flush();
                    quit = true;
                    System.out.println("Disconnecting client");
                    break;
                case "4":
                    os.println("SHUTDOWN");
                    os.flush();
                    System.out.println("Shutting down UVM manager");
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

