package tests;

import misc.UVConfig;
import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.LNChannel;
import network.UVNetwork;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Runs the real DSE entry point with temporary snapshots, configs and reports. */
public final class DseSnapshotTest {
    private DseSnapshotTest() {}
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static JsonObject object(Object... pairs) {
        var value = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
    private static long number(Map<?, ?> value, String key) { return ((Number) value.get(key)).longValue(); }
    private static Path config(Path directory, String name, Path base, String overrides) throws Exception {
        Path file = directory.resolve(name + ".properties");
        Files.writeString(file, "@include=" + base.toAbsolutePath() + "\nlogfile=" + directory.resolve(name + ".log") + "\n" + overrides);
        return file;
    }
    private static JsonObject run(Path directory, String name, Path config, List<JsonObject> commands, boolean success) throws Exception {
        Path output = directory.resolve(name), spec = directory.resolve(name + ".json"), log = directory.resolve(name + ".stdout");
        var steps = new JsonArray(); steps.addAll(commands);
        var outputs = new JsonArray(); outputs.addAll(List.of("network", "invoice"));
        Files.writeString(spec, object("name", name, "commands", steps, "outputs", outputs).toJsonString());
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "DseExperimentRunner", config.toString(), spec.toString(), output.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new AssertionError("DSE did not terminate: " + name); }
        check((process.exitValue() == 0) == success, "Unexpected DSE exit: " + Files.readString(log));
        JsonObject report;
        try (var reader = Files.newBufferedReader(output.resolve("run.json"))) { report = Json.parseObject(reader); }
        check((success ? "success" : "failed").equals(report.get("status")), "Wrong run status");
        if (success) {
            var reports = (JsonArray) report.get("reports");
            check(reports.size() == 2, "Missing network/invoice report");
            for (Object row : reports) check(Files.size(output.resolve((String) ((JsonObject) row).get("path"))) > 0, "Empty report");
        }
        return report;
    }
    private static JsonObject boot(Path snapshot) {
        return object("command", "boot", "mode", "load", "file", snapshot.getFileName().toString());
    }

    public static void settings(Path tmp) throws Exception {
        Path directory = Files.createDirectory(tmp.resolve("dse-snapshot-settings"));
        var source = JournalTrafficTest.fixture(directory, "source");
        Path snapshot = directory.resolve("base.dat"), base = Path.of(source.getConfig().getSourcePath());
        try { source.saveStatus(snapshot.toString()); } finally { source.shutdown(); }
        byte[] originalSnapshot = Files.readAllBytes(snapshot);
        for (int hops : List.of(1, 6)) {
            Path requested = config(directory, "hops-" + hops, base,
                    "pathfinding_max_hops=" + hops + "\nmax_threads=4\nnode_services_tick_ms=7\nhtlc_stall_timeout_ms=3210\n");
            var report = run(directory, "sweep-" + hops, requested, List.of(boot(snapshot),
                    object("command", "route", "sender", "a", "destination", "d", "amount", 1000, "path_finder", "lnd"),
                    object("command", "inv", "node_events_per_block", 0.25, "blocks", 1, "min_amt", 1000, "max_amt", 1000)), true);
            var effective = (JsonObject) report.get("effective_settings");
            check(number(effective, "pathfinding_max_hops") == hops && number(effective, "max_threads") == 4
                    && number(effective, "node_services_tick_ms") == 7 && number(effective, "htlc_stall_timeout_ms") == 3210
                    && number(effective, "seed") == 71, "Snapshot silently replaced runtime settings");
            check(effective.equals(report.get("requested_settings")), "Reported requested/effective config mismatch");
            var commands = (JsonArray) report.get("commands");
            var loaded = (JsonObject) commands.get(0);
            check("restore_saved_state".equals(loaded.get("rng_policy")) && loaded.get("effective_settings").equals(effective), "Missing restore policy");
            check(Boolean.valueOf(hops > 1).equals(((JsonObject) commands.get(1)).get("success")), "Hop sweep did not affect routing");
            var batch = (JsonObject) ((JsonObject) commands.get(2)).get("invoice_batch");
            check(number(batch, "invoice_pool_size") == 2 && "per_node_per_block".equals(batch.get("rate_unit")), "Old worker budget or wrong traffic units");
        }
        for (String conflict : List.of("seed=999", "bootstrap_nodes=5", "blocktime_ms=200", "minimum_depth=99",
                "profile.default.min_channels=99", "unknown_snapshot_option=1")) {
            Path requested = config(directory, "conflict", base, conflict + "\n");
            var network = new UVNetwork(new UVConfig(requested.toString()));
            try {
                var initialConfig = network.getConfig();
                check(!network.loadStatus(snapshot.toString(), UVNetwork.SnapshotConfigPolicy.APPLY_RUNTIME), "Accepted conflict: " + conflict);
                check(network.getLastLoadStatusError().contains(conflict.split("=")[0]) && network.isHealthy()
                        && network.getUVNodeList().isEmpty() && network.getConfig() == initialConfig, "Rejected restore changed live state");
            } finally { network.shutdown(); }
        }
        Path rejectedConfig = config(directory, "bad-seed", base, "seed=999\n");
        var rejected = run(directory, "rejected-sweep", rejectedConfig, List.of(boot(snapshot)), false);
        check(rejected.get("error").toString().contains("Snapshot-owned settings") && rejected.get("error").toString().contains("seed"), "Missing conflict diagnostic");
        // Ordinary terminal/script restores keep the historical restore-saved policy.
        var ordinary = new UVNetwork(new UVConfig(rejectedConfig.toString()));
        try {
            check(ordinary.loadStatus(snapshot.toString()) && ordinary.getConfig().getMasterSeed() == 71, "Default restore policy changed");
        } finally { ordinary.shutdown(); }
        check(java.util.Arrays.equals(originalSnapshot, Files.readAllBytes(snapshot)), "Snapshot was rewritten");
    }

    public static void observerTraffic(Path tmp) throws Exception {
        Path directory = Files.createDirectory(tmp.resolve("dse-observer-traffic"));
        Path base = config(directory, "source", Path.of("uv_configs/template.properties"),
                "seed=71\nbootstrap_nodes=3\nmax_threads=6\nblocktime_ms=100\nnode_services_tick_ms=5\n");
        Path topology = directory.resolve("topology.json"), snapshot = directory.resolve("observer.dat");
        Files.writeString(topology, """
                {"nodes":[{"pub_key":"a","alias":"root"},{"pub_key":"b","alias":"b"},{"pub_key":"c","alias":"c"}],
                 "edges":[{"channel_id":"ab","node1_pub":"a","node2_pub":"b","capacity":100000},
                          {"channel_id":"ac","node1_pub":"a","node2_pub":"c","capacity":100000}]}
                """);
        var network = new UVNetwork(new UVConfig(base.toString()));
        try {
            network.importTopology(topology.toString(), "a");
            for (var channel : network.getUVNode("a").getChannels().values()) {
                for (String peer : List.of(channel.getNode1PubKey(), channel.getNode2PubKey())) {
                    var policy = new LNChannel.Policy(40, 0, 0); channel.setPolicy(peer, policy);
                    for (var node : network.getUVNodeList().values()) {
                        if (node.getChannelGraph().hasChannel(channel.getChannelId())) node.getChannelGraph().updateChannel(peer, policy, channel.getChannelId());
                    }
                }
                channel.newCommitment(50000, 50000);
            }
            network.saveStatus(snapshot.toString());
        } finally { network.shutdown(); }
        byte[] originalSnapshot = Files.readAllBytes(snapshot);
        Path requested = config(directory, "run", base, "max_threads=4\n");
        var report = run(directory, "observer-run", requested, List.of(boot(snapshot),
                object("command", "inv", "events_per_block", 2, "blocks", 2, "min_amt", 1000, "max_amt", 1000),
                object("command", "inv", "node_events_per_block", 1, "blocks", 1, "min_amt", 1000, "max_amt", 1000)), true);
        var commands = (JsonArray) report.get("commands");
        check("IMPORTED_OBSERVER_VIEW".equals(((JsonObject) commands.get(0)).get("topology_mode")), "Lost observer mode");
        for (int index = 1; index <= 2; index++) {
            var command = (JsonObject) commands.get(index);
            var batch = (JsonObject) command.get("invoice_batch");
            var summary = (JsonObject) batch.get("summary");
            int expected = index == 1 ? 4 : 1;
            check(number(summary, "requested") == expected && number(summary, "successful") == expected
                    && Boolean.TRUE.equals(batch.get("settled")), "Wrong root exposure or unresolved outcome");
            check("per_root_per_block".equals(command.get("rate_unit")) && "imported_root".equals(batch.get("sender_scope"))
                    && "a".equals(batch.get("sender")) && !batch.containsKey("node_events_per_block"), "Ambiguous root rate metadata");
            for (Object row : (JsonArray) batch.get("requests")) check("a".equals(((JsonObject) row).get("sender")), "Non-root sender");
        }
        check(number((JsonObject) commands.get(2), "invoice_report_rows") == 5, "Reports lost root invoices");
        var ambiguous = run(directory, "ambiguous-rate", requested, List.of(boot(snapshot), object("command", "inv",
                "events_per_block", 2, "node_events_per_block", 1, "blocks", 1)), false);
        check(ambiguous.get("error").toString().contains("cannot be combined"), "Ambiguous rates accepted");
        check(java.util.Arrays.equals(originalSnapshot, Files.readAllBytes(snapshot)), "Observer snapshot was rewritten");
    }
}
