package dev.siddhant.evalops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.runner.RunService;
import dev.siddhant.evalops.store.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Task leasing for out-of-process workers (runs created with
 * {@code "executor": "worker"}). A worker leases one item, calls the target
 * itself, and posts the output back; this service scores it. Each lease
 * carries a fencing token; a lease that is not renewed by heartbeat expires
 * and the item can be leased again with a higher token. Results and
 * heartbeats with a superseded token are rejected with 409, so a worker that
 * was paused or partitioned cannot overwrite the result of the worker that
 * took over.
 */
@RestController
public class WorkerApi {
    private final Store store;
    private final RunService runs;
    private final MeterRegistry metrics;

    public WorkerApi(Store store, RunService runs, MeterRegistry metrics) {
        this.store = store;
        this.runs = runs;
        this.metrics = metrics;
    }

    static Duration leaseFor(JsonNode body) {
        long ms = body.path("lease_ms").asLong(30_000);
        if (ms < 100 || ms > 600_000) throw new IllegalArgumentException("lease_ms must be 100..600000");
        return Duration.ofMillis(ms);
    }

    /** {"worker": "name", "lease_ms": 30000} → 200 task, or 204 when there is nothing to do. */
    @PostMapping("/worker/lease")
    public ResponseEntity<?> lease(@RequestBody JsonNode body) {
        String worker = body.path("worker").asText("");
        if (worker.isBlank()) throw new IllegalArgumentException("worker required");
        Duration d = leaseFor(body);
        var t = store.lease(worker, d);
        if (t.isEmpty()) return ResponseEntity.noContent().build();
        var task = t.get();
        var run = store.run(task.runId()).orElseThrow();
        if (run.status().equals("queued")) store.setStatus(run.id(), "running", null);
        var item = store.items(run.datasetId()).get(task.idx());
        metrics.counter("evalops.leases", "kind", task.leases() > 1 ? "re-lease" : "first").increment();
        ObjectNode o = Store.JSON.createObjectNode().put("run", task.runId()).put("idx", task.idx()).put("token", task.token())
                .put("lease_ms", d.toMillis()).put("lease_expires", task.leaseExpires().toString()).put("input", item.input());
        o.set("target", Store.readTreePublic(run.targetSnapshot()));
        return ResponseEntity.ok(o);
    }

    /** {"run", "idx", "token", "lease_ms"} → 200, or 409 if the lease was lost. */
    @PostMapping("/worker/heartbeat")
    public ResponseEntity<?> heartbeat(@RequestBody JsonNode body) {
        boolean ok = store.heartbeat(body.path("run").asLong(), body.path("idx").asInt(), body.path("token").asLong(), leaseFor(body));
        return ok ? ResponseEntity.ok(Map.of("ok", true)) : ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "lease lost"));
    }

    /** {"run", "idx", "token", "output" | "error", "latency_ms", "attempts"} → 200, or 409 for a stale token. */
    @PostMapping("/worker/complete")
    public ResponseEntity<?> complete(@RequestBody JsonNode body) {
        long runId = body.path("run").asLong();
        int idx = body.path("idx").asInt();
        long token = body.path("token").asLong();
        var task = store.task(runId, idx);
        if (task.isEmpty()) return Api.notFound("task");
        if (task.get().token() != token || !task.get().state().equals("leased")) return stale();
        var run = store.run(runId).orElseThrow();
        var item = store.items(run.datasetId()).get(idx);
        String output = body.path("output").isTextual() ? body.get("output").asText() : null;
        String error = output == null ? body.path("error").asText("worker reported no output") : null;
        var scores = runs.score(run, item, output);
        Double latency = body.hasNonNull("latency_ms") ? body.get("latency_ms").asDouble() : null;
        var result = new Store.Result(idx, output, latency, error, scores, false, Math.max(1, body.path("attempts").asInt(1)));
        if (!store.completeTask(runId, idx, token, result)) return stale(); // lost the lease while scoring
        metrics.counter("evalops.items", "outcome", output == null ? "error" : "ok").increment();
        store.finishIfComplete(runId);
        return ResponseEntity.ok(Map.of("accepted", true));
    }

    private ResponseEntity<?> stale() {
        metrics.counter("evalops.leases", "kind", "stale-rejected").increment();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", "stale lease: the task was leased again or is finished"));
    }
}
