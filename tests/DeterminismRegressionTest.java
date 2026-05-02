import misc.UVConfig;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;
import topology.PathFinder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

public class DeterminismRegressionTest {
    private static final String TOPOLOGY_JSON = """
            {
              "nodes": [
                {"pub_key": "R", "alias": "root"},
                {"pub_key": "A", "alias": "alice"},
                {"pub_key": "B", "alias": "bob"},
                {"pub_key": "C", "alias": "carol"},
                {"pub_key": "D", "alias": "dina"},
                {"pub_key": "E", "alias": "erin"},
                {"pub_key": "F", "alias": "frank"}
              ],
              "edges": [
                {"channel_id": "ab", "node1_pub": "A", "node2_pub": "B", "capacity": 100000},
                {"channel_id": "bc", "node1_pub": "B", "node2_pub": "C", "capacity": 110000},
                {"channel_id": "cd", "node1_pub": "C", "node2_pub": "D", "capacity": 120000},
                {"channel_id": "de", "node1_pub": "D", "node2_pub": "E", "capacity": 130000},
                {"channel_id": "ef", "node1_pub": "E", "node2_pub": "F", "capacity": 140000}
              ]
            }
            """;

    public static void main(String[] args) throws Exception {
        UVNetwork.Log = ignored -> { };

        Path workDir = Files.createTempDirectory("uv-determinism-regression-");
        try {
            Path topologyPath = workDir.resolve("linear-topology.json");
            Files.writeString(topologyPath, TOPOLOGY_JSON);

            String first = runImportSnapshot(workDir, topologyPath, 7, "same-seed-a");
            String second = runImportSnapshot(workDir, topologyPath, 7, "same-seed-b");
            assertEquals(first, second, "same seed must reproduce the imported topology snapshot");

            boolean foundDifferentSeed = false;
            for (int seed = 0; seed < 64; seed++) {
                if (seed == 7) {
                    continue;
                }
                String candidate = runImportSnapshot(workDir, topologyPath, seed, "seed-" + seed);
                if (!first.equals(candidate)) {
                    foundDifferentSeed = true;
                    break;
                }
            }
            if (!foundDifferentSeed) {
                throw new AssertionError("expected at least one alternate seed to alter seeded import choices");
            }

            System.out.println("Determinism regression passed");
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static String runImportSnapshot(Path workDir, Path topologyPath, int seed, String runName) throws IOException {
        Path configPath = writeConfig(workDir, seed, runName);
        UVNetwork network = new UVNetwork(new UVConfig(configPath.toString()));
        try {
            network.importTopology(topologyPath.toString(), "R");
            return canonicalSnapshot(network);
        } finally {
            network.shutdown();
        }
    }

    private static Path writeConfig(Path workDir, int seed, String runName) throws IOException {
        Path templateConfig = Path.of("uv_configs", "template.properties").toAbsolutePath().normalize();
        Path logfile = workDir.resolve(runName + ".log");
        Path configPath = workDir.resolve(runName + ".properties");

        String config = String.join(System.lineSeparator(),
                "@include=" + templateConfig,
                "seed=" + seed,
                "logfile=" + logfile,
                "bootstrap_nodes=7",
                "max_threads=16",
                ""
        );
        Files.writeString(configPath, config);
        return configPath;
    }

    private static String canonicalSnapshot(UVNetwork network) {
        StringBuilder snapshot = new StringBuilder();

        network.getUVNodeList().values().stream()
                .sorted(Comparator.comparing(UVNode::getPubKey))
                .forEach(node -> appendNode(snapshot, node));

        UVNode root = network.getUVNode("R");
        PathFinder.SearchResult route = root.findPaths("A", "F", 1);
        if (route.paths().size() != 1) {
            throw new AssertionError("expected one deterministic path from A to F, got " + route.paths().size());
        }
        snapshot.append("path=").append(route.paths().getFirst().path()).append(System.lineSeparator());
        snapshot.append("pathStats=").append(route.stats()).append(System.lineSeparator());

        return snapshot.toString();
    }

    private static void appendNode(StringBuilder snapshot, UVNode node) {
        snapshot.append("node ")
                .append(node.getPubKey())
                .append(" alias=").append(node.getAlias())
                .append(System.lineSeparator());

        node.getLNChannelList().stream()
                .sorted(Comparator.comparing(LNChannel::getChannelId))
                .forEach(channel -> appendChannel(snapshot, (UVChannel) channel));
    }

    private static void appendChannel(StringBuilder snapshot, UVChannel channel) {
        snapshot.append("  channel ")
                .append(channel.getChannelId())
                .append(" ")
                .append(channel.getNode1PubKey())
                .append("->")
                .append(channel.getNode2PubKey())
                .append(" capacity=").append(channel.getCapacity())
                .append(" balance1=").append(channel.getBalance(channel.getNode1PubKey()))
                .append(" balance2=").append(channel.getBalance(channel.getNode2PubKey()))
                .append(System.lineSeparator());
    }

    private static void assertEquals(String expected, String actual, String message) {
        if (!expected.equals(actual)) {
            throw new AssertionError(message + System.lineSeparator()
                    + "--- expected ---" + System.lineSeparator() + expected
                    + "--- actual ---" + System.lineSeparator() + actual);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException("cannot delete temporary path " + path, e);
                        }
                    });
        }
    }
}
