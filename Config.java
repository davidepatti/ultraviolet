import java.io.*;
import java.util.Properties;
import java.util.Scanner;

public class Config implements Serializable {

    private static Properties properties;

    @Serial
    private static final long serialVersionUID = 120678L;

    private static boolean initialized = false;

    public static boolean isInitialized() {
        return initialized;
    }

    public static void setDefaults() {
        properties.setProperty("debug","true");
        properties.setProperty("blocktime","1000");
        properties.setProperty("logfile","default.log");
        properties.setProperty("seed","1");
        // bootstrap
        properties.setProperty("bootstrap_warmup","1");
        properties.setProperty("bootstrap_nodes","10");
        properties.setProperty("bootstrap_min_funding","10000000");
        properties.setProperty("bootstrap_min_funding","100000000");
        properties.setProperty("bootstrap_min_channels","3");
        properties.setProperty("bootstrap_max_channels","5");
        properties.setProperty("bootstrap_min_channel_size","500000");
        properties.setProperty("bootstrap_max_channel_size","1000000");

        // p2p
        properties.setProperty("p2p_flush_size","500");
        properties.setProperty("debug","true");
        properties.setProperty("p2p_max_hops","2");
        properties.setProperty("p2p_max_age","3");
        properties.setProperty("p2p_period","100");
        properties.setProperty("debug","false");
        initialized = true;
    }

    public static void setConfig (Properties newconfig) {

        for (String k: newconfig.stringPropertyNames()) {
           properties.setProperty(k,newconfig.getProperty(k));
        }

        for (String k: properties.stringPropertyNames()) {
            if (!newconfig.stringPropertyNames().contains(k)) {
                System.out.println("Warning: config parameter '"+k+"' missing in new loaded config, leaving old value "+properties.getProperty(k));
            }
        }
    }

    public static void loadConfig(String config_file) {
        properties = new Properties();
        // this is needed so that parameters not set in config file can be assumed as default
        setDefaults();

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

        if (!properties.containsKey(parameter)) {
            System.out.println("Missing parameter "+parameter);
            System.out.print("Please enter value or enter 'q' to exit:");
            var input = new Scanner(System.in);
            var val = input.nextLine();
            if (val.equals("q")) System.exit(-1);
            properties.setProperty(parameter,val);
        }

        return properties.get(parameter).toString();
    }

    public static int getVal(String parameter) {

        if (!properties.containsKey(parameter)) {
            System.out.println("Missing parameter "+parameter);
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
