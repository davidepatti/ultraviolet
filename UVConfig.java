import java.io.*;
import java.util.*;
/* a .properties file is used to initialize this class with a set of key/value pair, readable from other classes
While some of them a hardcode into UV, user can add and define new keys/value pairs for new purposes, as they
will be automatically loaded into the properties object and retrieved with:
getStringProperty/getIntProperty(String key)

Properties that begin with "profile.", e.g., profile.PROFILENAME.SOME_KEY= value will be also copied into a "profiles"
map, so that attribute can be grouped in different namespaces.
For example:
getProfileAttribute("big_nodes","max_channel_size") will be different from
getProfileAttribute("small_nodes", "max_channel_size")

Finally, when properties are in the particular form: key=a,b,c,d,e... they will be considered as multiple possible
values of that property, accessible with:
ArrayList<String> getMultivalProperty(String key)
String getRandomMultivalProperty(String key)




 */

public class UVConfig implements Serializable {

    private final Map<String,Map<String,String>> profiles = new HashMap<>();
    private final Map<String,ArrayList<String>> multival_properties = new HashMap<>();
    private Properties properties;
    private Random random;

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
                var value = properties.getProperty(propertyName);
                if (propertyName.startsWith("profile.")) {
                    // Parse the property name into profile name and attribute
                    String[] parts = propertyName.split("\\.");
                    String profileName = parts[1];
                    String attribute = parts[2];

                    profiles.putIfAbsent(profileName,new HashMap<>());
                    profiles.get(profileName).put(attribute,value);
                }

                if (value.contains(",")) {
                    var values = new ArrayList<>(Arrays.asList(value.split(",")));
                    multival_properties.put(propertyName,values);
                    //System.out.println("Detected multival: "+propertyName+ ": "+values);
                }
            }

            // put the name of each profile as attribute of the profile itself
            for (String name:profiles.keySet()) {
                profiles.get(name).put("name",name);
                //System.out.println("Loaded profile: "+name);
            }

        } catch (FileNotFoundException e) {
            System.out.println("Config file not found:"+config_file);
            System.out.println("Trying to set defauls. Please notice this is not expected to always work.");
            setDefaults();
            System.out.println("\n[PRESS ENTER TO CONTINUE...]");
            new Scanner(System.in).nextLine();
        } catch (
                IOException e) {
            throw new RuntimeException(e);
        }

        random = new Random(getIntProperty("seed"));
        //System.out.println("Setting seed to "+getIntProperty("seed"));
    }


    public ArrayList<String> getMultivalProperty(String key) {
       return multival_properties.get(key);
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

    public synchronized Map<String,String> getRandomProfile() {
        double p = random.nextDouble();
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

    public String getStringProperty(String parameter) {

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

    public int getIntProperty(String parameter) {

        String attribute = getStringProperty(parameter);

        return Integer.parseInt(attribute);
    }

    @Override
    public String toString() {
        return properties.toString();
    }

}
