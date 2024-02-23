import java.awt.image.AreaAveragingScaleFilter;
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
    private Properties properties;
    private Random random;


    public static class NodeProfile implements Serializable {

        private final Map<String,String> attributes = new HashMap<>();
        private final Map<String,int[]> distributions = new HashMap<>();
        private final String name;

        public NodeProfile(String profileName) {
            this.name = profileName;
        }

        public String getAttribute(String key) {
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
            return Integer.parseInt(getAttribute(key));
        }
        public double getDoubleAttribute(String key) {
            return Double.parseDouble(getAttribute(key));
        }

        public void addAttribute(String key, String value) {
            attributes.putIfAbsent(key, value);
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
    }

    @Serial
    private static final long serialVersionUID = 120678L;
    private boolean initialized = false;
    public boolean isInitialized() {
        return initialized;
    }

    public Map<String, NodeProfile> getNodeProfiles() {
        return profiles;
    }

    public NodeProfile getNodeProfile(String profile_name) {
        return profiles.get(profile_name);
    }


    public void setConfig (Properties newconfig) {

        for (String k: newconfig.stringPropertyNames()) {
           properties.setProperty(k,newconfig.getProperty(k));
        }

        for (String k: properties.stringPropertyNames()) {
            if (!newconfig.stringPropertyNames().contains(k)) {
                System.out.println("Warning: config parameter '"+k+"' missing in new loaded config, leaving old value "+properties.getProperty(k));
                System.out.println("\n[PRESS ENTER TO CONTINUE...]");
                new Scanner(System.in).nextLine();
            }
        }
    }

    public void loadConfig(String config_file) {
        properties = new Properties();

        try {
            properties.load(new FileReader(config_file));
            initialized = true;

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
            System.out.println("\n[PRESS ENTER TO EXIT...]");
            new Scanner(System.in).nextLine();
            System.exit(-1);
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        random = new Random(getIntProperty("seed"));
        //System.out.println("Setting seed to "+getIntProperty("seed"));
    }


    private ArrayList<String> getMultivalProperty(String key) {

        String value = getStringProperty(key);
        if (value.contains(",")) {
            return new ArrayList<>(Arrays.asList(value.split(",")));
        }
        else {
            throw new RuntimeException("No multival key:"+key);
        }
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

    public synchronized NodeProfile getRandomProfile() {
        double p = random.nextDouble();
        double cumulativeProbability = 0.0;
        for (String profileName: profiles.keySet()) {

            if (!profileName.equals("default")) {
                try {
                    cumulativeProbability += Double.parseDouble(profiles.get(profileName).getAttribute("prob"));
                }
                catch (RuntimeException e) {
                    System.out.println("Wrong get request!");
                    e.printStackTrace();
                }

            }
            if (p <= cumulativeProbability) {
                return profiles.get(profileName);
            }
        }
        return profiles.get("default");
    }

    public String getProfileAttribute(String profile, String attribute) {
        return profiles.get(profile).getAttribute(attribute);
    }

    public String getStringProperty(String parameter) {
        try {
            if (!properties.containsKey(parameter)) {
                throw new RuntimeException("Parameter " + parameter + " not found!");
            }
            return properties.get(parameter).toString();
        } catch (RuntimeException e) {
            e.printStackTrace();
            System.exit(1);
        }
        return null;
    }

    public int getIntProperty(String parameter) {
        String attribute = getStringProperty(parameter);
        return Integer.parseInt(attribute);
    }

    @Override
    public String toString() {
        return properties.toString();
    }

}
