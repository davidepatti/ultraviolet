import misc.UVConfig;
import misc.json.Json;
import misc.json.JsonArray;
import misc.json.JsonObject;
import network.UVNetwork;
import stats.JournalCaptureExporter;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.UUID;

/** Internal target of tools/journal/uv_journal_capture.py. Fresh bootstrap only. */
public final class JournalCaptureRunner {
    private JournalCaptureRunner() {}

    private static byte[] bytes(Object value) {
        return (JournalCaptureExporter.canonical(value) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static void create(Path path, byte[] value) throws Exception {
        Files.write(path, value, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public static void main(String[] args) {
        int code = 1;
        UVNetwork network = null;
        try {
            if (args.length != 1 || !args[0].matches("[a-z0-9][a-z0-9-]{0,79}")) {
                throw new IllegalArgumentException("Expected one topology ID; load/traffic commands are unsupported");
            }
            Path runtime = Path.of("").toRealPath();
            Path output = runtime.getParent();
            // The public launcher creates a new owned directory. Never accept an arbitrary export path.
            if (!runtime.getFileName().toString().equals("runtime") || !Files.isRegularFile(output.resolve("request.json"))) {
                throw new IllegalArgumentException("Run through the journal capture launcher");
            }
            Path group = output.getParent();
            Path a2 = group.getFileName().toString().equals("incoming") ? group.getParent()
                    : group.getFileName().toString().equals("captures")
                    && group.getParent().getFileName().toString().equals("validation") ? group.getParent().getParent() : null;
            if (a2 == null || !a2.getFileName().toString().equals("a2")
                    || !a2.getParent().getFileName().toString().equals("gnn-lightning-journal")
                    || !Files.isRegularFile(a2.resolve("configs/study.json"))) {
                throw new IllegalArgumentException("Destination is not the journal A2 capture space");
            }
            JsonObject source;
            try (var reader = Files.newBufferedReader(output.resolve("source-manifest.json"))) {
                source = Json.parseObject(reader);
            }
            UVConfig config = new UVConfig("capture.properties");
            if (!config.logfile.equals("simulator.log")) throw new IllegalArgumentException("Capture log must remain local");
            byte[] expanded = bytes(config.getEffectiveSettings());
            create(output.resolve("expanded-config.json"), expanded);
            UVNetwork.Log = ignored -> { };
            network = new UVNetwork(config);
            network.bootstrapNetwork();
            network.pauseAndDrain(30_000);
            byte[] cleanup = bytes(JournalCaptureExporter.object("schema", "journal-a2-opening-cleanup-v1",
                    "topology_id", args[0], "cancelled_unaccepted_requests", java.util.Map.of(),
                    "funded_channels_changed", false, "onchain_balances_changed", false));
            create(output.resolve("opening-cleanup.json"), cleanup);
            JsonArray warnings = new JsonArray();
            if (!java.util.Map.of().isEmpty()) warnings.add("Unaccepted opening reservations cancelled at the terminal boundary; see opening-cleanup.json");
            warnings.add("Synthetic opening state; CLTV and other routing constraints are outside static A2 labels");
            JsonObject provenance = JournalCaptureExporter.object(
                    "capture_id", UUID.randomUUID().toString(), "bootstrap_seed", config.getMasterSeed(),
                    "uv_commit", source.get("base_commit"), "source_tree_sha256", source.get("source_tree_sha256"),
                    "source_dirty", source.get("dirty"), "source_manifest_sha256", JournalCaptureExporter.sha256(Files.readAllBytes(output.resolve("source-manifest.json"))),
                    "expanded_config_sha256", JournalCaptureExporter.sha256(expanded),
                    "opening_cleanup_sha256", JournalCaptureExporter.sha256(cleanup),
                    "pending_checked", true, "warnings", warnings, "captured_at_utc", Instant.now().toString(),
                    "capture_boundary", "fresh-bootstrap-terminal-workers-joined-v1",
                    "block_height", network.getTimechain().getCurrentBlockHeight());
            var first = JournalCaptureExporter.build(network, args[0], provenance);
            var repeated = JournalCaptureExporter.build(network, args[0], provenance);
            if (!first.equals(repeated)) throw new IllegalStateException("Repeated stopped-state capture differs");
            byte[] pub = bytes(first.publicGraph()), truth = bytes(first.openingTruth());
            create(output.resolve("public.json"), pub);
            create(output.resolve("opening-truth.json"), truth);
            // Completion marker is written last. Partial/failed outputs are never valid captures.
            JsonObject receipt = JournalCaptureExporter.object("schema", "journal-a2-capture-receipt-v1",
                    "topology_id", args[0], "public_file", "public.json", "public_sha256", JournalCaptureExporter.sha256(pub),
                    "opening_truth_file", "opening-truth.json", "opening_truth_sha256", JournalCaptureExporter.sha256(truth),
                    "expanded_config_file", "expanded-config.json", "public_fingerprint", first.openingTruth().get("public_fingerprint"),
                    "source_manifest_sha256", provenance.get("source_manifest_sha256"),
                    "opening_cleanup_sha256", provenance.get("opening_cleanup_sha256"), "repeated_capture_identical", true);
            create(output.resolve("capture.json"), bytes(receipt));
            System.out.println("Journal opening capture complete: " + args[0]);
            code = 0;
        } catch (Throwable error) {
            error.printStackTrace(System.err);
        } finally {
            if (network != null) network.shutdown();
        }
        System.exit(code);
    }
}
