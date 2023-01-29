// Java program to read JSON from a file

import java.io.FileReader;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.*;

import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import java.io.FileReader;

public class JSONReader {

    public static void main(String[] args) {
        JSONParser parser = new JSONParser();
        try {
            Object obj = parser.parse(new FileReader("graph.json"));
            JSONObject jsonObject = (JSONObject) obj;
            JSONArray edges = (JSONArray) jsonObject.get("edges");
            ChannelGraph g = new ChannelGraph();
            for (Object edge : edges) {
                JSONObject edgeObject = (JSONObject) edge;
                String channel_id = (String) edgeObject.get("channel_id");
                System.out.println("channel_id: " + channel_id);
                String node1_pub = (String) edgeObject.get("node1_pub");
                String node2_pub = (String) edgeObject.get("node2_pub");
                int capacity = Integer.parseInt((String) edgeObject.get("capacity"));

                g.addChannel(node1_pub,node2_pub);
                /*
                String chan_point = (String) edgeObject.get("chan_point");
                long last_update = (long) edgeObject.get("last_update");
                System.out.println("channel_id: " + channel_id);
                System.out.println("chan_point: " + chan_point);
                System.out.println("last_update: " + last_update);
                System.out.println("node1_pub: " + node1_pub);
                System.out.println("node2_pub: " + node2_pub);
                System.out.println("capacity: " + capacity);

                 */
            }

            System.out.println("Nodes:"+ g.getNodeCount());
            System.out.println("Channels:"+ g.getChannelCount());


        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
