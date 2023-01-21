import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.Serializable;
import java.util.Properties;

public class ConfigManager implements Serializable {

    private static final String DEFAULT_BOOTSTRAP_WARMUP = "10";
    private static final String DEFAULT_TOTAL_NODES = "10";
    private static final String DEFAULT_MIN_FUNDING = "10000000"; // 10M
    private static final String DEFAULT_MAX_FUNDING = "100000000";  // 100M
    private static final String DEFAULT_LOGFILE = "uvm.log";
    private static final String DEFAULT_SERVERPORT = "7777";
    private static final String DEFAULT_MIN_CHANNELS = "3";
    private static final String DEFAULT_MAX_CHANNELS = "5";
    private static final String DEFAULT_MIN_CHANNEL_SIZE = "1000000"; //1M
    private static final String DEFAULT_MAX_CHANNEL_SIZE = "10000000"; //10M
    private static final String DEFAULT_SEED = "0";
    private static final String DEFAULT_BLOCK_TIMING = "1000";
    private static final String DEFAULT_MAX_GOSSIP_HOPS = "2";

    public static int bootstrap_warmup;
    public static int total_nodes;
    public static int min_funding;
    public static int max_funding;
    public static int min_channels;
    public static int max_channels;
    public static int min_channel_size;
    public static int max_channel_size;
    public static int server_port;
    public static String logfile;
    public static int seed;
    public static boolean verbose = false;
    public static int blocktiming;
    public static int max_gossip_hops;


    public static void setDefaults() {
        // default values when not config file is provided
        bootstrap_warmup = Integer.parseInt(DEFAULT_BOOTSTRAP_WARMUP);
        total_nodes = Integer.parseInt(DEFAULT_TOTAL_NODES);
        min_funding = Integer.parseInt(DEFAULT_MIN_FUNDING);
        max_funding = Integer.parseInt(DEFAULT_MAX_FUNDING);

        min_channels = Integer.parseInt(DEFAULT_MIN_CHANNELS);
        max_channels = Integer.parseInt(DEFAULT_MAX_CHANNELS);

        min_channel_size = Integer.parseInt(DEFAULT_MIN_CHANNEL_SIZE);
        max_channel_size = Integer.parseInt(DEFAULT_MAX_CHANNEL_SIZE);
        server_port = Integer.parseInt(DEFAULT_SERVERPORT);

        logfile = DEFAULT_LOGFILE;

        seed = Integer.parseInt(DEFAULT_SEED);
        blocktiming = Integer.parseInt(DEFAULT_BLOCK_TIMING);
        max_gossip_hops = Integer.parseInt(DEFAULT_MAX_GOSSIP_HOPS);
    }


    public static void loadConfig(String config_file) {
        try {
            var config_file_reader = new FileReader(config_file);
            var config = new Properties();
            config.load(config_file_reader);
            ///String profile = config.getProperty("profile")
            bootstrap_warmup = Integer.parseInt(config.getProperty("bootstrap_warmup", DEFAULT_BOOTSTRAP_WARMUP));
            total_nodes = Integer.parseInt(config.getProperty("total_nodes", DEFAULT_TOTAL_NODES));
            min_funding = Integer.parseInt(config.getProperty("min_funding", DEFAULT_MIN_FUNDING));
            max_funding = Integer.parseInt(config.getProperty("max_funding", DEFAULT_MAX_FUNDING));
            min_channels = Integer.parseInt(config.getProperty("min_channels", DEFAULT_MIN_CHANNELS));
            max_channels = Integer.parseInt(config.getProperty("max_channels", DEFAULT_MAX_CHANNELS));
            min_channel_size = Integer.parseInt(config.getProperty("min_channel_size", DEFAULT_MIN_CHANNEL_SIZE));
            max_channel_size = Integer.parseInt(config.getProperty("max_channel_size", DEFAULT_MAX_CHANNEL_SIZE));
            server_port = Integer.parseInt(config.getProperty("server_port", DEFAULT_SERVERPORT));
            logfile = config.getProperty("logfile", DEFAULT_LOGFILE);
            seed = Integer.parseInt(config.getProperty("seed",DEFAULT_SEED));
            blocktiming = Integer.parseInt(config.getProperty("blocktiming", DEFAULT_BLOCK_TIMING));
            max_gossip_hops = Integer.parseInt(config.getProperty("max_gossip_hops", DEFAULT_MAX_GOSSIP_HOPS));
            config_file_reader.close();
            UVManager.log.print("Configuration loaded");
            //config.list(System.out);

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

    public static String printConfig() {
        return
        ", tot_nodes:"+ total_nodes+
        ", min_funding:"+min_funding+
        ", max_funding:"+max_funding+
        ", min_channels:"+min_channels+
        ", max_channels:"+max_channels+
        ", min_channel_size:"+min_channel_size+
        ", max_channel_size:"+max_channel_size+
        ", server_port:"+server_port+
        ", logfile:"+logfile+
        ", seed:"+seed;
    }
}
