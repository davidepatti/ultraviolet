package stats;

import misc.json.JsonArray;
import misc.json.JsonObject;
import protocol.LNInvoice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static stats.JournalCaptureExporter.object;

/** Private journal accounting. One request row exists before its task is submitted. */
public final class JournalInvoiceBatch {
    private final JsonObject metadata;
    private final Map<Long, JsonObject> requests = new LinkedHashMap<>();
    private final Set<String> hashes = new HashSet<>();
    private boolean injectionStopped;
    private boolean settled;
    private String infrastructureFailure;

    public JournalInvoiceBatch(String id, double rate, int blocks, int min, int max, int fees, int threads, int height) {
        metadata = object("schema", "journal-ot-invoices-v1", "batch_id", id,
                "node_events_per_block", rate, "generator_iterations", blocks,
                "min_amount_sat", min, "max_amount_sat", max, "max_fees_sat", fees,
                "invoice_pool_size", threads, "start_height", height, "started_at_utc", Instant.now().toString());
    }

    public synchronized void request(long id, String sender, LNInvoice invoice, int height) {
        if (injectionStopped || requests.containsKey(id) || !hashes.add(invoice.getHash())) {
            throw new IllegalStateException("Duplicate or late journal invoice request");
        }
        requests.put(id, object("request_id", id, "invoice_hash", invoice.getHash(), "sender", sender,
                "destination", invoice.getDestination(), "amount_sat", invoice.getAmount(),
                "submitted_height", height, "submitted_at_utc", Instant.now().toString(), "status", "unfinished"));
    }

    private JsonObject unfinished(long id) {
        var row = requests.get(id);
        if (row == null || !"unfinished".equals(row.get("status"))) throw new IllegalStateException("Missing or duplicate invoice outcome");
        return row;
    }

    public synchronized void complete(long id, GlobalStats.NodeStats.InvoiceReport report, int height) {
        var row = unfinished(id);
        if (report == null || !row.get("sender").equals(report.sender())
                || !row.get("destination").equals(report.dest()) || !row.get("amount_sat").equals(report.amt())) {
            throw new IllegalStateException("Invoice report identity mismatch");
        }
        JsonObject fields = new JsonObject();
        // Preserve all existing search/filter/attempt counters, without joining on the short display hash.
        try {
            for (var component : GlobalStats.NodeStats.InvoiceReport.class.getRecordComponents()) {
                fields.put(component.getName(), component.getAccessor().invoke(report));
            }
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        row.put("report", fields);
        row.put("completed_height", height);
        row.put("completed_at_utc", Instant.now().toString());
        row.put("status", report.success() ? "success" : "failure");
    }

    public synchronized void exception(long id, Throwable error, int height) {
        var row = unfinished(id);
        row.put("status", "exception");
        row.put("exception", error.toString());
        row.put("completed_height", height);
        row.put("completed_at_utc", Instant.now().toString());
    }

    public synchronized void injectionStopped(int height) {
        injectionStopped = true;
        metadata.put("injection_end_height", height);
        metadata.put("injection_ended_at_utc", Instant.now().toString());
    }

    public synchronized void requireCompleted() {
        if (!injectionStopped) throw new IllegalStateException("Invoice injection is not finished");
        for (var row : requests.values()) {
            if (!Set.of("success", "failure").contains(row.get("status"))) {
                throw new IllegalStateException("Unfinished or exceptional journal invoice");
            }
            var report = (JsonObject) row.get("report");
            if (((Number) report.get("attempt_failed_timeout")).intValue() != 0) {
                throw new IllegalStateException("Native attempt timeout is not a safe end-to-end settlement boundary");
            }
        }
    }

    public synchronized void markSettled(int height, long pauseMillis) {
        requireCompleted();
        settled = true;
        metadata.put("settled_height", height);
        metadata.put("pause_drain_ms", pauseMillis);
        metadata.put("settled_at_utc", Instant.now().toString());
    }

    public synchronized void fail(Throwable error) { infrastructureFailure = error.toString(); }

    public synchronized JsonObject snapshot() {
        JsonObject result = new JsonObject();
        result.putAll(metadata);
        JsonArray rows = new JsonArray();
        long requestedAmount = 0, deliveredAmount = 0;
        int successful = 0, failed = 0, exceptional = 0, unfinished = 0;
        for (var row : requests.values()) {
            JsonObject copy = new JsonObject(); copy.putAll(row); rows.add(copy);
            long amount = ((Number) row.get("amount_sat")).longValue();
            requestedAmount += amount;
            switch ((String) row.get("status")) {
                case "success" -> { successful++; deliveredAmount += amount; }
                case "failure" -> failed++;
                case "exception" -> exceptional++;
                default -> unfinished++;
            }
        }
        result.put("requests", rows);
        result.put("summary", object("requested", rows.size(), "completed", successful + failed + exceptional,
                "successful", successful, "failed", failed, "exceptional", exceptional, "unfinished", unfinished,
                "requested_amount_sat", requestedAmount, "delivered_amount_sat", deliveredAmount));
        result.put("settled", settled);
        result.put("infrastructure_failure", infrastructureFailure);
        return result;
    }
}
