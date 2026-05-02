import misc.UVConfig;
import network.LNChannel;
import network.UVChannel;
import network.UVNetwork;
import network.UVNode;
import stats.DistributionGenerator;
import topology.PathFinder;
import topology.PathFinderFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

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

            assertConfigIncludesAreDeterministic(workDir);
            assertDistributionGenerationIsDeterministic();
            assertImportedTopologyIsDeterministic(workDir, topologyPath);
            assertPathFindingIsDeterministic(workDir, topologyPath);
            assertSaveLoadRoundTripIsDeterministic(workDir, topologyPath);
            assertNetworkReportIsDeterministic(workDir, topologyPath);

            System.out.println("Determinism regression passed");
        } finally {
            deleteRecursively(workDir);
        }
    }

    private static void assertConfigIncludesAreDeterministic(Path workDir) throws IOException {
        Path configPath = writeConfig(workDir, 42, "config-include");
        UVConfig first = new UVConfig(configPath.toString());
        UVConfig second = new UVConfig(configPath.toString());

        assertEquals("42", Integer.toString(first.getMasterSeed()), "seed override must be loaded");
        assertEquals("7", Integer.toString(first.bootstrap_nodes), "bootstrap_nodes override must be loaded");
        assertEquals("16", Integer.toString(first.max_threads), "max_threads override must be loaded");
        assertEquals("3000", first.getProfileAttribute("small", "max_ppm_fee"), "included profile attributes must be available");
        assertEquals(profileSequence(first, "prob"), profileSequence(second, "prob"), "profile selection must be stable for equal config and RNG seed");
        assertEquals(profileSequence(first, "hubness"), profileSequence(second, "hubness"), "hubness selection must be stable for equal config and RNG seed");
    }

    private static void assertDistributionGenerationIsDeterministic() {
        int[] first = DistributionGenerator.generateIntSamples(new Random(123456789L), 24, 10, 100, 50, 65);
        int[] second = DistributionGenerator.generateIntSamples(new Random(123456789L), 24, 10, 100, 50, 65);
        assertIntArrayEquals(first, second, "integer distribution samples must be deterministic for equal RNG seed");

        double[] firstDouble = DistributionGenerator.generateDoubleSamples(new Random(987654321L), 16, 1, 25, 12, 17);
        double[] secondDouble = DistributionGenerator.generateDoubleSamples(new Random(987654321L), 16, 1, 25, 12, 17);
        assertDoubleArrayEquals(firstDouble, secondDouble, "double distribution samples must be deterministic for equal RNG seed");

        int[] alternateSeed = DistributionGenerator.generateIntSamples(new Random(123456790L), 24, 10, 100, 50, 65);
        if (Arrays.equals(first, alternateSeed)) {
            throw new AssertionError("alternate RNG seed unexpectedly produced identical integer distribution samples");
        }
    }

    private static void assertImportedTopologyIsDeterministic(Path workDir, Path topologyPath) throws IOException {
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
    }

    private static void assertPathFindingIsDeterministic(Path workDir, Path topologyPath) throws IOException {
        String first = runPathFindingSnapshot(workDir, topologyPath, 7, "pathfinding-a");
        String second = runPathFindingSnapshot(workDir, topologyPath, 7, "pathfinding-b");
        assertEquals(first, second, "pathfinding snapshots must be deterministic on fixed imported topology");
        assertContains(first, "BFS paths=1", "BFS must find the linear route");
        assertContains(first, "SHORTEST_HOP paths=1", "Shortest-hop search must find the linear route");
        assertContains(first, "MINI_DIJKSTRA paths=1", "Mini-Dijkstra must find the linear route");
        assertContains(first, "LND paths=0", "LND search must reject imported null-policy routes");
    }

    private static void assertSaveLoadRoundTripIsDeterministic(Path workDir, Path topologyPath) throws IOException {
        Path snapshotPath = workDir.resolve("roundtrip.dat");
        String before;

        UVNetwork source = importNetwork(workDir, topologyPath, 7, "save-source");
        try {
            before = canonicalSnapshot(source);
            source.saveStatus(snapshotPath.toString());
        } finally {
            source.shutdown();
        }

        Path loaderConfigPath = writeConfig(workDir, 999, "save-loader");
        UVNetwork loaded = new UVNetwork(new UVConfig(loaderConfigPath.toString()));
        try {
            if (!loaded.loadStatus(snapshotPath.toString())) {
                throw new AssertionError("loadStatus failed: " + loaded.getLastLoadStatusError());
            }
            String after = canonicalSnapshot(loaded);
            assertEquals(before, after, "save/load round trip must preserve canonical imported topology state");
        } finally {
            loaded.shutdown();
        }
    }

    private static void assertNetworkReportIsDeterministic(Path workDir, Path topologyPath) throws IOException {
        String first = runNetworkReport(workDir, topologyPath, 7, "report-a");
        String second = runNetworkReport(workDir, topologyPath, 7, "report-b");
        assertEquals(first, second, "network report must be deterministic for frozen imported topology");
        assertContains(first, "Graph Nodes", "network report must include graph node statistics");
        assertContains(first, "Node Capacity", "network report must include capacity statistics");
    }

    private static String runImportSnapshot(Path workDir, Path topologyPath, int seed, String runName) throws IOException {
        UVNetwork network = importNetwork(workDir, topologyPath, seed, runName);
        try {
            return canonicalSnapshot(network);
        } finally {
            network.shutdown();
        }
    }

    private static String runPathFindingSnapshot(Path workDir, Path topologyPath, int seed, String runName) throws IOException {
        UVNetwork network = importNetwork(workDir, topologyPath, seed, runName);
        try {
            StringBuilder snapshot = new StringBuilder();
            UVNode root = network.getUVNode("R");
            for (PathFinderFactory.Strategy strategy : List.of(
                    PathFinderFactory.Strategy.BFS,
                    PathFinderFactory.Strategy.SHORTEST_HOP,
                    PathFinderFactory.Strategy.MINI_DIJKSTRA,
                    PathFinderFactory.Strategy.LND
            )) {
                PathFinder finder = PathFinderFactory.of(strategy, network.getConfig());
                PathFinder.SearchResult result = finder.findPaths(root.getChannelGraph(), "A", "F", 3);
                snapshot.append(strategy)
                        .append(" paths=").append(result.paths().size())
                        .append(" stats=").append(result.stats())
                        .append(System.lineSeparator());
                for (PathFinder.PathDetails details : result.paths()) {
                    snapshot.append("  ")
                            .append(details.path())
                            .append(" cost=").append(details.totalCost())
                            .append(" components=").append(details.components())
                            .append(System.lineSeparator());
                }
            }
            return snapshot.toString();
        } finally {
            network.shutdown();
        }
    }

    private static String runNetworkReport(Path workDir, Path topologyPath, int seed, String runName) throws IOException {
        UVNetwork network = importNetwork(workDir, topologyPath, seed, runName);
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(new ByteArrayOutputStream()));
            return network.getStats().generateNetworkReport();
        } finally {
            System.setOut(originalOut);
            network.shutdown();
        }
    }

    private static UVNetwork importNetwork(Path workDir, Path topologyPath, int seed, String runName) throws IOException {
        Path configPath = writeConfig(workDir, seed, runName);
        UVNetwork network = new UVNetwork(new UVConfig(configPath.toString()));
        network.importTopology(topologyPath.toString(), "R");
        return network;
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

    private static String profileSequence(UVConfig config, String attribute) {
        Random rng = new Random(20260502L);
        StringBuilder sequence = new StringBuilder();
        for (int i = 0; i < 32; i++) {
            if (!sequence.isEmpty()) {
                sequence.append(',');
            }
            sequence.append(config.selectProfileBy(rng, attribute).getName());
        }
        return sequence.toString();
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

    private static void assertContains(String value, String expectedSubstring, String message) {
        if (!value.contains(expectedSubstring)) {
            throw new AssertionError(message + System.lineSeparator()
                    + "Missing substring: " + expectedSubstring + System.lineSeparator()
                    + "Value:" + System.lineSeparator() + value);
        }
    }

    private static void assertIntArrayEquals(int[] expected, int[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + System.lineSeparator()
                    + "--- expected ---" + System.lineSeparator() + Arrays.toString(expected)
                    + "--- actual ---" + System.lineSeparator() + Arrays.toString(actual));
        }
    }

    private static void assertDoubleArrayEquals(double[] expected, double[] actual, String message) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError(message + System.lineSeparator()
                    + "--- expected ---" + System.lineSeparator() + Arrays.toString(expected)
                    + "--- actual ---" + System.lineSeparator() + Arrays.toString(actual));
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
