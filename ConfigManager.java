import java.io.*;
import java.util.Properties;

public class ConfigManager implements Serializable {

    private static Properties properties;

    @Serial
    private static final long serialVersionUID = 120678L;
    private static final String DEFAULT_BLOCKTIME = "1000";
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
    public static String logfile;
    private static int seed;

    private static boolean initialized = false;

    // bootstrap
    private static int bootstrap_warmup;
    private static int total_nodes;
    private static int min_funding;
    private static int max_funding;
    private static int min_channels;
    private static int max_channels;
    private static int min_channel_size;
    private static int max_channel_size;


    // p2p
    private static int max_p2p_hops;
    private static int max_p2p_age;
    private static int p2p_period;

    private static final boolean verbose = false;
    private static boolean debug = false;


    public static boolean isInitialized() {
        return initialized;
    }

    public static void setDefaults() {
        // default values when not config file is provided
        blocktime = Integer.parseInt(DEFAULT_BLOCKTIME);
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
        initialized = true;
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
            p2p_period = Integer.parseInt(properties.getProperty("p2p_period", DEFAULT_P2P_PERIOD));
            config_file_reader.close();
            debug = true;
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

    public static int getSeed() {
        return seed;
    }

    public static int getBootstrapWarmup() {
        return bootstrap_warmup;
    }

    public static int getTotalNodes() {
        return total_nodes;
    }

    public static int getMinFunding() {
        return min_funding;
    }

    public static int getMaxFunding() {
        return max_funding;
    }

    public static int getMinChannels() {
        return min_channels;
    }

    public static int getMaxChannels() {
        return max_channels;
    }

    public static int getMinChannelSize() {
        return min_channel_size;
    }

    public static int getMaxChannelSize() {
        return max_channel_size;
    }

    public static int getMaxP2PHops() {
        return max_p2p_hops;
    }

    public static int getMaxP2PAge() {
        return max_p2p_age;
    }

    public static int getP2PPeriod() {
        return p2p_period;
    }

    public static boolean isVerbose() {
        return verbose;
    }

    public static boolean isDebug() {
        return debug;
    }

    @Override
    public String toString() {
        return properties.toString();
    }

    public static Properties getConfig() {
        return properties;
    }

}
