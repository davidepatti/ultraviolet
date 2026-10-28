package tests;

import misc.UVConfig;
import network.UVNetwork;
import java.nio.file.Files;
import java.nio.file.Path;

/** Compiled against the original revision by compatibility_regression.sh. */
public final class LegacySnapshotWriter {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        Path config = directory.resolve("legacy.properties"), topology = directory.resolve("legacy.json");
        Files.writeString(config, "@include=" + Path.of("uv_configs/template.properties").toAbsolutePath()
                + "\nseed=7\nbootstrap_nodes=2\nmax_threads=4\nlogfile=" + directory.resolve("legacy.log") + "\n");
        Files.writeString(topology, "{\"nodes\":[{\"pub_key\":\"a\",\"alias\":\"a\"},{\"pub_key\":\"b\",\"alias\":\"b\"}],\"edges\":[{\"channel_id\":\"ab\",\"node1_pub\":\"a\",\"node2_pub\":\"b\",\"capacity\":100000}]}");
        UVNetwork.Log = ignored -> {};
        var network = new UVNetwork(new UVConfig(config.toString()));
        try {
            network.importTopology(topology.toString(), "a");
            network.getUVNode("b").generateInvoice(1000, "default", true);
            network.saveStatus(directory.resolve("legacy.dat").toString());
        } finally { network.shutdown(); }
    }
}
