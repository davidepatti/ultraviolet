import java.io.*;
import java.util.Properties;
import java.util.Scanner;

public class ConfigManager implements Serializable {

    private static Properties properties;

    @Serial
    private static final long serialVersionUID = 120678L;

    private static boolean initialized = false;

    public static boolean isInitialized() {
        return initialized;
    }

    public static void setDefaults() {
        properties.setProperty("blocktime","1000");
        properties.setProperty("logfile","default.log");
        properties.setProperty("seed","1");
        // bootstrap
        properties.setProperty("bootstrap_warmup","1");
        properties.setProperty("total_nodes","10");
        properties.setProperty("min_funding","10000000");
        properties.setProperty("min_funding","100000000");
        properties.setProperty("min_channels","3");
        properties.setProperty("max_channels","5");
        properties.setProperty("min_channel_size","500000");
        properties.setProperty("max_channel_size","1000000");

        // p2p
        properties.setProperty("max_p2p_hops","2");
        properties.setProperty("max_p2p_age","3");
        properties.setProperty("p2p_period","100");
        properties.setProperty("debug","false");
        initialized = true;
    }

    public static void setConfig (Properties properties) {
        ConfigManager.properties = properties;
    }

    public static void loadConfig(String config_file) {
        properties = new Properties();
        try {
            properties.load(new FileReader(config_file));
            initialized = true;

        } catch (FileNotFoundException e) {
            System.out.println("Config file not found:"+config_file);
            System.out.println("Setting defaults...");
            setDefaults();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static void print() {
        var l = properties.stringPropertyNames();

        for (String s:l)
            System.out.println("name: "+s+" val: "+properties.get(s));
    }

    public static String get(String parameter) {

        try {
            if (!properties.containsKey(parameter)) {
                throw new RuntimeException("Missing parameter "+parameter);
            }
        }
        catch (Exception e) {
            System.out.print("Please enter value or enter 'q' to exit:");
            var input = new Scanner(System.in);
            var val = input.nextLine();
            if (val.equals("q")) System.exit(-1);
            properties.setProperty(parameter,val);
        }

        return properties.get(parameter).toString();
    }

    public static int getVal(String parameter) {

        try {
            if (!properties.containsKey(parameter)) {
                throw new RuntimeException("Missing parameter "+parameter);
            }
        }
        catch (Exception e) {
            System.out.print("Please enter value or enter 'q' to exit:");
            var input = new Scanner(System.in);
            var val = input.nextLine();
            if (val.equals("q")) System.exit(-1);
            properties.setProperty(parameter,val);
        }

        return Integer.parseInt(properties.get(parameter).toString());
    }

    @Override
    public String toString() {
        return properties.toString();
    }

    public static Properties getConfig() {
        return properties;
    }

}
