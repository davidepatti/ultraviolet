import java.io.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
/* a .properties file is used to initialize this class with a set of key/value pair, readable from other classes
While some of them a hardcode into UV, user can add and define new keys/value pairs for new purposes, as they
will be automatically loaded into the properties object and retrieved with:
getStringProperty/getIntProperty(String key)

Properties that begin with "profile.", e.g., profile.PROFILENAME.SOME_KEY= value will be also copied into a "profiles"
map, so that attribute can be grouped in different namespaces.

Finally, when properties are in the particular form: key=a,b,c,d,e... they will be considered as multiple possible
values of that property, accessible with:
ArrayList<String> getMultivalProperty(String key)
String getRandomMultivalProperty(String key)
 */

public class UVConfig implements Serializable {

    private final Map<String,NodeProfile> profiles = new HashMap<>();
    private final Properties properties;

    final public int bootstrap_nodes;
    final public int bootstrap_blocks;
    final public double bootstrap_time_median;
    final public double bootstrap_time_mean;
    final public int p2p_max_hops;
    final public int p2p_max_age;
    final public int gossip_flush_size;
    final public int to_self_delay;
    final public int minimum_depth;
    final public int max_threads;
    final public int seed;
    final public int blocktime_ms;
    final public int node_services_tick_ms;
    final public int gossip_flush_period_ms;
    final public String logfile;
    final public boolean debug;


    private final Random random;
    @Serial
    private static final long serialVersionUID = 120678L;

    public static class NodeProfile implements Serializable {

        private final Map<String,String> attributes = new HashMap<>();
        private final Map<String,int[]> distributions = new HashMap<>();
        private final String name;

        public NodeProfile(String profileName) {
            this.name = profileName;
        }

        private String getAttribute(String key) {
            try {
                if (!attributes.containsKey(key)){
                    throw new IllegalArgumentException("The provided key does not exist in the attributes map:"+key);
                }
            } catch (IllegalArgumentException e) {
                System.err.println(e.getMessage());
                return null; // return null or a default value
            }
            return attributes.get(key);
        }

        public int getIntAttribute(String key) {
            return Integer.parseInt(Objects.requireNonNull(getAttribute(key)));
        }
        public double getDoubleAttribute(String key) {
            return Double.parseDouble(Objects.requireNonNull(getAttribute(key)));
        }

        public void addAttribute(String key, String value) {
            attributes.putIfAbsent(key, value);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NodeProfile that = (NodeProfile) o;
            return Objects.equals(name, that.name);
        }

        @Override
        public int hashCode() {
            return Objects.hash(name);
        }

        public int getRandomSample(String key) {
            try {
                if (distributions.containsKey(key)) {
                    var samples = distributions.get(key);
                    return samples[ThreadLocalRandom.current().nextInt(0, samples.length)];
                }
                else  throw new RuntimeException("Missing distribution key: " + key);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return 0;
        }

        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return name;
        }
    }

    public Map<String, NodeProfile> getNodeProfiles() {
        return profiles;
    }

    public NodeProfile getNodeProfile(String profile_name) {
        return profiles.get(profile_name);
    }


    public UVConfig(String config_file) {

        properties = new Properties();

        try {
            properties.load(new FileReader(config_file));

            // Setting node profiles
            for (String propertyName : properties.stringPropertyNames()) {
                if (propertyName.startsWith("profile.")) {
                    // Parse the property name into profile name and attribute
                    String[] parts = propertyName.split("\\.");
                    String profileName = parts[1];
                    String attribute = parts[2];

                    profiles.putIfAbsent(profileName,new NodeProfile(profileName));
                    var value = properties.getProperty(propertyName);
                    var profile = profiles.get(profileName);
                    profile.addAttribute(attribute,value);
                }
            }


            // parse profiles to create samples distributions
            for (var profile: profiles.values()) {
                final int max_ch_size = profile.getIntAttribute("max_channel_size");
                final int min_ch_size = profile.getIntAttribute("min_channel_size");
                final int median_ch_size = profile.getIntAttribute("median_channel_size");
                final int mean_ch_size = profile.getIntAttribute("mean_channel_size");
                final int max_ppm_fee = profile.getIntAttribute("max_ppm_fee");
                final int min_ppm_fee = profile.getIntAttribute("min_ppm_fee");
                final int median_ppm_fee = profile.getIntAttribute("median_ppm_fee");
                final int mean_ppm_fee = profile.getIntAttribute("mean_ppm_fee");

                profile.distributions.put("channel_sizes",DistributionGenerator.generateIntSamples(100,min_ch_size,max_ch_size,median_ch_size,mean_ch_size));
                profile.distributions.put("ppm_fees",DistributionGenerator.generateIntSamples(100,min_ppm_fee,max_ppm_fee,median_ppm_fee,mean_ppm_fee));
            }

        } catch (FileNotFoundException e) {
            System.out.println("Config file not found:"+config_file);
            System.out.println("Current directory:" + System.getProperty("user.dir"));
            System.out.println("\n[PRESS ENTER TO EXIT...]");

            new Scanner(System.in).nextLine();
            System.exit(-1);
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        p2p_max_age = Integer.parseInt(properties.getProperty("p2p_max_age"));
        p2p_max_hops = Integer.parseInt(properties.getProperty("p2p_max_hops"));
        gossip_flush_size = Integer.parseInt(properties.getProperty("gossip_flush_size"));
        bootstrap_nodes = Integer.parseInt(properties.getProperty("bootstrap_nodes"));
        max_threads = Integer.parseInt(properties.getProperty("max_threads"));
        blocktime_ms = Integer.parseInt(properties.getProperty("blocktime_ms"));
        node_services_tick_ms = Integer.parseInt(properties.getProperty("node_services_tick_ms"));
        gossip_flush_period_ms = Integer.parseInt(properties.getProperty("gossip_flush_period_ms"));
        to_self_delay = Integer.parseInt(properties.getProperty("to_self_delay"));
        minimum_depth = Integer.parseInt(properties.getProperty("minimum_depth"));
        bootstrap_blocks = Integer.parseInt(properties.getProperty("bootstrap_blocks"));
        bootstrap_time_median = Double.parseDouble(properties.getProperty("bootstrap_time_median"));
        bootstrap_time_mean = Double.parseDouble(properties.getProperty("bootstrap_time_mean"));
        logfile = properties.getProperty("logfile");
        debug = properties.getProperty("debug").equals("true");
        seed = Integer.parseInt(properties.getProperty("seed"));

        random = new Random(seed);
    }


    private ArrayList<String> getMultivalProperty(String key) {

        String value = "";
        try {
            if (!properties.containsKey(key))
                throw new RuntimeException("Parameter " + key + " not found!");

            value =  properties.getProperty(key);
            if (value.contains(","))
                return new ArrayList<>(Arrays.asList(value.split(",")));
            else
                throw new RuntimeException("No multival key:"+key);

        } catch (RuntimeException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    public ArrayList<Integer> getMultivalIntProperty(String key) {
        var values = new ArrayList<Integer>();
        for (String s: getMultivalProperty(key)) {
            values.add(Integer.parseInt(s));
        }
        return values;
    }

    public synchronized String getMultivalRandomItem(String key) {
        int index = random.nextInt(getMultivalProperty(key).size()-1);
        return getMultivalProperty(key).get(index);
    }
    public int getMultivalPropertyRandomIntItem(String key) {
        return Integer.parseInt(getMultivalRandomItem(key));
    }

    // selects a profile according to some probabilistic criteria
    public synchronized NodeProfile selectProfileBy(String attribute) {
        double p = random.nextDouble();
        double cumulativeProbability = 0.0;
        for (String profileName: profiles.keySet()) {

            if (!profileName.equals("default"))
                cumulativeProbability += Double.parseDouble(getProfileAttribute(profileName,attribute));

            if (p <= cumulativeProbability)
                return profiles.get(profileName);
        }
        return profiles.get("default");
    }

    public String getProfileAttribute(String profile, String attribute) {
        return profiles.get(profile).getAttribute(attribute);
    }

    @Override
    public String toString() {
        return "UVConfig{" +
                "profiles=" + profiles +
                ", properties=" + properties +
                ", p2p_max_hops=" + p2p_max_hops +
                ", p2p_max_age=" + p2p_max_age +
                ", bootstrap_nodes=" + bootstrap_nodes +
                ", max_threads=" + max_threads +
                ", seed=" + seed +
                ", blocktime_ms=" + blocktime_ms +
                ", node_services_tick_ms=" + node_services_tick_ms +
                ", gossip_flush_period_ms=" + gossip_flush_period_ms +
                ", logfile='" + logfile + '\'' +
                ", random=" + random +
                '}';
    }
}
