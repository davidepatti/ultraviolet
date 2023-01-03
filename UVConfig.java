import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.util.Properties;

public class UVConfig {
    private static final String DEFAULT_TOTAL_NODES = "10";
    private static final String DEFAULT_MIN_FUNDING = "10000000";
    private static final String DEFAULT_MAX_FUNDING = "100000000";
    private static final String DEFAULT_LOGFILE = "uvm.log";
    private static final String DEFAULT_SERVERPORT = "7777";
    private static final String DEFAULT_MIN_CHANNELS = "3";
    private static final String DEFAULT_MAX_CHANNELS = "5";
    // as fraction of the total initial funding
    private static final String DEFAULT_MIN_CHANNEL_SIZE = "0.05";
    private static final String DEFAULT_MAX_CHANNEL_SIZE = "0.2";

    public static int total_nodes;
    public static int min_funding;
    public static int max_funding;
    public static int min_channels;
    public static int max_channels;
    public static double min_channel_size;
    public static double max_channel_size;
    public static int server_port;
    public static String logfile;


    public static void setDefaults() {
        // default values when not config file is provided
        total_nodes = Integer.parseInt(DEFAULT_TOTAL_NODES);
        min_funding = Integer.parseInt(DEFAULT_MIN_FUNDING);
        max_funding = Integer.parseInt(DEFAULT_MAX_FUNDING);

        min_channels = Integer.parseInt(DEFAULT_MIN_CHANNELS);
        max_channels = Integer.parseInt(DEFAULT_MAX_CHANNELS);

        min_channel_size = Double.parseDouble(DEFAULT_MIN_CHANNEL_SIZE);
        max_channel_size = Double.parseDouble(DEFAULT_MAX_CHANNEL_SIZE);
        server_port = Integer.parseInt(DEFAULT_SERVERPORT);

        logfile = DEFAULT_LOGFILE;

    }

    public static void loadConfig(String config_file) {
        try {
            var config_file_reader = new FileReader(config_file);
            var config = new Properties();
            config.load(config_file_reader);
            total_nodes = Integer.parseInt(config.getProperty("total_nodes", DEFAULT_TOTAL_NODES));
            min_funding = Integer.parseInt(config.getProperty("min_funding", DEFAULT_MIN_FUNDING));
            max_funding = Integer.parseInt(config.getProperty("max_funding", DEFAULT_MAX_FUNDING));
            min_channels = Integer.parseInt(config.getProperty("max_funding", DEFAULT_MIN_CHANNELS));
            max_channels = Integer.parseInt(config.getProperty("max_funding", DEFAULT_MAX_CHANNELS));
            min_channel_size = Double.parseDouble(config.getProperty("max_funding", DEFAULT_MIN_CHANNEL_SIZE));
            max_channel_size = Double.parseDouble(config.getProperty("max_funding", DEFAULT_MAX_CHANNEL_SIZE));
            server_port = Integer.parseInt(config.getProperty("server_port", DEFAULT_SERVERPORT));
            logfile = config.getProperty("logfile", DEFAULT_LOGFILE);

            System.out.println("loaded config:");
            config_file_reader.close();

        } catch (
                FileNotFoundException e) {
            throw new RuntimeException(e);
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }
    }

}
