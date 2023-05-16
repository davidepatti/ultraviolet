import java.io.*;
import java.util.*;

public class UVConfig implements Serializable {

    private Map<String,Map<String,String>> profiles = new HashMap<>();

    private Properties properties;

    @Serial
    private static final long serialVersionUID = 120678L;

    private boolean initialized = false;

    public boolean isInitialized() {
        return initialized;
    }

    public Map<String, Map<String, String>> getProfiles() {
        return profiles;
    }

    public void setDefaults() {

        if (isInitialized()) {
            System.out.println("WARNING: setting defaults to an already initialized configuration");
            System.out.println("PRESS ENTER");
            new Scanner(System.in).nextLine();
        }

        if (properties==null) properties = new Properties();

        properties.setProperty("debug","true");
        properties.setProperty("blocktime","1000");
        properties.setProperty("logfile","default.log");
        properties.setProperty("seed","1");
        // bootstrap
        properties.setProperty("bootstrap_duration","1");
        properties.setProperty("bootstrap_nodes","10");
        properties.setProperty("profile.default.min_funding","10000000");
        properties.setProperty("profile.default.min_funding","100000000");
        properties.setProperty("profile.default.min_channels","3");
        properties.setProperty("profile.default.max_channels","5");
        properties.setProperty("profile.default.min_channel_size","500000");
        properties.setProperty("profile.default.max_channel_size","1000000");

        // p2p
        properties.setProperty("gossip_flush_size","500");
        properties.setProperty("debug","true");
        properties.setProperty("p2p_max_hops","2");
        properties.setProperty("p2p_max_age","3");
        properties.setProperty("p2p_period","100");
        properties.setProperty("debug","false");
        initialized = true;
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
        // this is needed so that parameters not set in config file can be assumed as default
        setDefaults();

        try {
            properties.load(new FileReader(config_file));
            initialized = true;

            for (String propertyName : properties.stringPropertyNames()) {
                if (propertyName.startsWith("profile.")) {
                    // Parse the property name into profile name and attribute
                    String[] parts = propertyName.split("\\.");
                    String profileName = parts[1];
                    String attribute = parts[2];

                    profiles.putIfAbsent(profileName,new HashMap<>());
                    profiles.get(profileName).put(attribute,properties.getProperty(propertyName));
                }
            }

            // put the name of each profile as attribute of the profile itself
            for (String name:profiles.keySet()) {
                profiles.get(name).put("name",name);
            }

        } catch (FileNotFoundException e) {
            System.out.println("Config file not found:"+config_file);
            System.out.println("Setting defaults...");
            setDefaults();
            System.out.println("\n[PRESS ENTER TO CONTINUE...]");
            new Scanner(System.in).nextLine();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }
    }

    public Map<String,String> getRandomProfile() {
        double p = new Random().nextDouble();
        double cumulativeProbability = 0.0;
        for (String profileName: profiles.keySet()) {

            if (!profileName.equals("default")) {
                try {
                    cumulativeProbability += Double.parseDouble(profiles.get(profileName).get("prob"));
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
        return profiles.get(profile).get(attribute);
    }

    public void print() {
        var l = properties.stringPropertyNames();

        for (String s:l)
            System.out.println("name: "+s+" val: "+properties.get(s));
    }

    public String getStringAttribute(String parameter) {

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

    public int getIntAttribute(String parameter) {

        String attribute = getStringAttribute(parameter);

        return Integer.parseInt(attribute.toString());
    }

    @Override
    public String toString() {
        return properties.toString();
    }

}
