import java.io.*;
import java.util.Properties;

public class ConfigManager implements Serializable {

    private static Properties properties;

    @Serial
    private static final long serialVersionUID = 120678L;
    private static final String DEFAULT_BLOCKTIME = "1000";
    private static final String DEFAULT_SERVERPORT = "7777";
    private static final String DEFAULT_LOGFILE = "uvm.log";
    private static final String DEFAULT_SEED = "0";

    // bootstrap
    private static final String DEFAULT_BOOTSTRAP_WARMUP = "10";
    private static final String DEFAULT_TOTAL_NODES = "10";
    private static final String DEFAULT_MIN_FUNDING = "10000000"; // 10M
    private static final String DEFAULT_MAX_FUNDING = "100000000";  // 100M
    private static final String DEFAULT_MIN_CHANNELS = "3";
    private static final String DEFAULT_MAX_CHANNELS = "5";
    private static final String DEFAULT_MIN_CHANNEL_SIZE = "1000000"; //1M
    private static final String DEFAULT_MAX_CHANNEL_SIZE = "10000000"; //10M


    // p2p
    private static final String DEFAULT_MAX_P2P_HOPS = "3";
    private static final String DEFAULT_MAX_P2P_AGE = "5";
    private static final String DEFAULT_P2P_PERIOD = "100";

    public static int blocktime;
    public static int server_port;
    public static String logfile;
    public static int seed;

    // bootstrap
    public static int bootstrap_warmup;
    public static int total_nodes;
    public static int min_funding;
    public static int max_funding;
    public static int min_channels;
    public static int max_channels;
    public static int min_channel_size;
    public static int max_channel_size;


    // p2p
    public static int max_p2p_hops;
    public static int max_p2p_age;
    public static int p2p_period;

    public static final boolean verbose = false;
    public static boolean debug = false;

    public static void setDefaults() {
        // default values when not config file is provided
        blocktime = Integer.parseInt(DEFAULT_BLOCKTIME);
        server_port = Integer.parseInt(DEFAULT_SERVERPORT);
        logfile = DEFAULT_LOGFILE;
        seed = Integer.parseInt(DEFAULT_SEED);

        // bootstrap
        bootstrap_warmup = Integer.parseInt(DEFAULT_BOOTSTRAP_WARMUP);
        total_nodes = Integer.parseInt(DEFAULT_TOTAL_NODES);
        min_funding = Integer.parseInt(DEFAULT_MIN_FUNDING);
        max_funding = Integer.parseInt(DEFAULT_MAX_FUNDING);

        min_channels = Integer.parseInt(DEFAULT_MIN_CHANNELS);
        max_channels = Integer.parseInt(DEFAULT_MAX_CHANNELS);

        min_channel_size = Integer.parseInt(DEFAULT_MIN_CHANNEL_SIZE);
        max_channel_size = Integer.parseInt(DEFAULT_MAX_CHANNEL_SIZE);

        // p2p
        max_p2p_hops = Integer.parseInt(DEFAULT_MAX_P2P_HOPS);
        max_p2p_age = Integer.parseInt(DEFAULT_MAX_P2P_AGE);
        p2p_period = Integer.parseInt(DEFAULT_P2P_PERIOD);
        debug = true;
    }


    public static void setConfig (Properties properties) {
        ConfigManager.properties = properties;
    }

    public static void loadConfig(String config_file) {
        properties = new Properties();
        try {
            var config_file_reader = new FileReader(config_file);
            properties.load(config_file_reader);
            blocktime = Integer.parseInt(properties.getProperty("blocktime", DEFAULT_BLOCKTIME));
            server_port = Integer.parseInt(properties.getProperty("server_port", DEFAULT_SERVERPORT));
            logfile = properties.getProperty("logfile", DEFAULT_LOGFILE);
            seed = Integer.parseInt(properties.getProperty("seed",DEFAULT_SEED));

            // bootstrap
            bootstrap_warmup = Integer.parseInt(properties.getProperty("bootstrap_warmup", DEFAULT_BOOTSTRAP_WARMUP));
            total_nodes = Integer.parseInt(properties.getProperty("total_nodes", DEFAULT_TOTAL_NODES));
            min_funding = Integer.parseInt(properties.getProperty("min_funding", DEFAULT_MIN_FUNDING));
            max_funding = Integer.parseInt(properties.getProperty("max_funding", DEFAULT_MAX_FUNDING));
            min_channels = Integer.parseInt(properties.getProperty("min_channels", DEFAULT_MIN_CHANNELS));
            max_channels = Integer.parseInt(properties.getProperty("max_channels", DEFAULT_MAX_CHANNELS));
            min_channel_size = Integer.parseInt(properties.getProperty("min_channel_size", DEFAULT_MIN_CHANNEL_SIZE));
            max_channel_size = Integer.parseInt(properties.getProperty("max_channel_size", DEFAULT_MAX_CHANNEL_SIZE));

            // p2p
            max_p2p_hops = Integer.parseInt(properties.getProperty("max_p2p_hops", DEFAULT_MAX_P2P_HOPS));
            max_p2p_age = Integer.parseInt(properties.getProperty("max_p2p_age", DEFAULT_MAX_P2P_AGE));
            max_p2p_hops = Integer.parseInt(properties.getProperty("p2p_period", DEFAULT_P2P_PERIOD));
            config_file_reader.close();
            debug = true;

        } catch (
                FileNotFoundException e) {
            System.out.println("Config file not found:"+config_file);
            System.out.println("Setting defaults...");
            setDefaults();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

    }

    @Override
    public String toString() {
        return properties.toString();
    }

    public static Properties getConfig() {
        return properties;
    }

}
