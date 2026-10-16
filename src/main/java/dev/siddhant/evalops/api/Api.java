package dev.siddhant.evalops.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.metrics.Metrics;
import dev.siddhant.evalops.runner.Judge;
import dev.siddhant.evalops.runner.Reports;
import dev.siddhant.evalops.runner.RunService;
import dev.siddhant.evalops.store.Store;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class Api {
    private final Store store;
    private final RunService runs;
    private final Reports reports;

    public Api(Store store, RunService runs, Reports reports) {
        this.store = store;
        this.runs = runs;
        this.reports = reports;
    }

    @ExceptionHandler({Reports.BadRequest.class, IllegalArgumentException.class})
    public ResponseEntity<Map<String, String>> bad(RuntimeException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    static ResponseEntity<?> notFound(String what) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", what + " not found"));
    }

    // ------------------------------------------------------------ datasets

    /** {"name": "...", "items": [{"input": "...", "reference": "...", "meta": {...}}]} */
    @PostMapping("/datasets")
    public ResponseEntity<?> addDataset(@RequestBody JsonNode body) {
        String name = body.path("name").asText("");
        if (!name.matches("[A-Za-z0-9_.-]{1,100}")) throw new IllegalArgumentException("name must match [A-Za-z0-9_.-]{1,100}");
        if (!body.path("items").isArray() || body.path("items").isEmpty()) throw new IllegalArgumentException("items must be a non-empty array");
        List<Store.Item> items = new ArrayList<>();
        int i = 0;
        for (JsonNode it : body.get("items")) {
            if (!it.path("input").isTextual()) throw new IllegalArgumentException("item " + i + ": input must be a string");
            items.add(new Store.Item(i++, it.get("input").asText(), it.path("reference").isMissingNode() ? null : it.path("reference").asText(),
                    it.has("meta") ? it.get("meta").toString() : null));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(store.addDataset(name, items));
    }

    @GetMapping("/datasets")
    public List<Store.Dataset> datasets() {
        return store.datasets();
    }

    @GetMapping("/datasets/{name}/{version}")
    public ResponseEntity<?> dataset(@PathVariable String name, @PathVariable int version) {
        return store.dataset(name, version).<ResponseEntity<?>>map(d -> ResponseEntity.ok(Map.of("dataset", d, "items", store.items(d.id()))))
                .orElseGet(() -> notFound("dataset"));
    }

    // ------------------------------------------------------------ targets

    /** {"url": ".../v1/completions", "model": "...", "params": {"max_tokens": 64, "temperature": 0, "stop": ["\n"]}} */
    @PutMapping("/targets/{name}")
    public Store.Target putTarget(@PathVariable String name, @RequestBody JsonNode body) {
        String url = body.path("url").asText("");
        if (!url.startsWith("http://") && !url.startsWith("https://")) throw new IllegalArgumentException("url must be http(s)");
        JsonNode params = body.path("params").isObject() ? body.get("params") : Store.JSON.createObjectNode();
        return store.putTarget(name, url, body.hasNonNull("model") ? body.get("model").asText() : null, Store.canonical(params));
    }

    @GetMapping("/targets")
    public List<Store.Target> targets() {
        return store.targets();
    }

    // ------------------------------------------------------------ runs

    /**
     * {"dataset": "name", "version": 2 (default latest), "target": "name", "metrics": [...], "concurrency": 8, "cache": true,
     * "executor": "local" | "worker", "judge": "target-name" (a /v1/chat/completions target, needed for llm_judge),
     * "github": {"owner": "o", "repo": "r", "head_sha": "...", "gate": {"baseline": 1, "rules": [...]}}}
     *
     * <p>An {@code Idempotency-Key} header makes the request safe to retry: the same key with the same body returns the
     * run created the first time (200, {@code Idempotent-Replayed: true}); the same key with a different body is 422.
     */
    @PostMapping("/runs")
    public ResponseEntity<?> createRun(@RequestBody JsonNode body, @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        String requestSha = Store.sha256(Store.canonical(body));
        if (idemKey != null) {
            if (idemKey.isBlank() || idemKey.length() > 200) throw new IllegalArgumentException("Idempotency-Key must be 1..200 characters");
            var replay = replay(idemKey, requestSha);
            if (replay != null) return replay;
        }
        String dsName = body.path("dataset").asText();
        var ds = body.has("version") ? store.dataset(dsName, body.get("version").asInt()) : store.latest(dsName);
        if (ds.isEmpty()) return notFound("dataset");
        var target = store.target(body.path("target").asText());
        if (target.isEmpty()) return notFound("target");
        List<String> metrics = new ArrayList<>();
        body.path("metrics").forEach(m -> metrics.add(m.asText()));
        if (metrics.isEmpty()) throw new IllegalArgumentException("metrics required; available: " + metricNames());
        for (String m : metrics) {
            if (!Metrics.ALL.containsKey(m) && !m.equals(Judge.METRIC)) throw new IllegalArgumentException("unknown metric " + m + "; available: " + metricNames());
        }
        String judgeSnap = null;
        if (body.hasNonNull("judge")) {
            var j = store.target(body.get("judge").asText());
            if (j.isEmpty()) return notFound("judge target");
            judgeSnap = Store.canonical(Judge.snapshot(j.get().url(), j.get().model()));
        }
        if (metrics.contains(Judge.METRIC) && judgeSnap == null) throw new IllegalArgumentException("llm_judge needs \"judge\": a chat target");
        int conc = body.path("concurrency").asInt(4);
        if (conc < 1 || conc > 256) throw new IllegalArgumentException("concurrency must be 1..256");
        String executor = body.path("executor").asText("local");
        if (!executor.equals("local") && !executor.equals("worker")) throw new IllegalArgumentException("executor must be local or worker");
        String github = null;
        if (body.hasNonNull("github")) {
            JsonNode gh = body.get("github");
            for (String f : List.of("owner", "repo", "head_sha")) {
                if (!gh.path(f).isTextual() || gh.path(f).asText().isBlank()) throw new IllegalArgumentException("github." + f + " required");
            }
            if (!gh.path("head_sha").asText().matches("[0-9a-f]{40}")) throw new IllegalArgumentException("github.head_sha must be a 40-character hex SHA");
            github = Store.canonical(gh);
        }
        var t = target.get();
        ObjectNode snap = Store.JSON.createObjectNode().put("url", t.url()).put("model", t.model());
        try {
            snap.set("params", Store.JSON.readTree(t.params()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        long id;
        try {
            id = store.createRun(new Store.NewRun(ds.get().id(), t.id(), Store.canonical(snap), metrics, conc, body.path("cache").asBoolean(true), executor,
                    judgeSnap, github, idemKey, requestSha));
        } catch (DataIntegrityViolationException | CannotAcquireLockException e) {
            // a concurrent request with the same key committed first; nothing of ours was written
            for (int i = 0; i < 50; i++) {
                var replay = replay(idemKey, requestSha);
                if (replay != null) return replay;
                sleep(20);
            }
            throw e;
        }
        if (!executor.equals("worker")) runs.start(id);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("run", id, "dataset_version", ds.get().version()));
    }

    private ResponseEntity<?> replay(String idemKey, String requestSha) {
        if (idemKey == null) return null;
        var k = store.idempotencyKey(idemKey);
        if (k.isEmpty()) return null;
        if (!k.get().requestSha().equals(requestSha)) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(Map.of("error", "Idempotency-Key reused with a different request"));
        }
        var run = store.run(k.get().runId()).orElseThrow();
        int version = store.dataset(run.datasetId()).orElseThrow().version();
        return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(Map.of("run", run.id(), "dataset_version", version));
    }

    static List<String> metricNames() {
        List<String> n = new ArrayList<>(Metrics.names());
        n.add(Judge.METRIC);
        return n.stream().sorted().toList();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @GetMapping("/runs")
    public List<Store.Run> runs() {
        return store.runs();
    }

    @GetMapping("/runs/{id}")
    public ResponseEntity<?> run(@PathVariable long id) {
        return store.run(id).<ResponseEntity<?>>map(r -> ResponseEntity.ok(reports.summary(r))).orElseGet(() -> notFound("run"));
    }

    @GetMapping("/runs/{id}/results")
    public ResponseEntity<?> results(@PathVariable long id) {
        return store.run(id).<ResponseEntity<?>>map(r -> ResponseEntity.ok(store.results(id))).orElseGet(() -> notFound("run"));
    }

    @GetMapping("/compare")
    public ResponseEntity<?> compare(@RequestParam long baseline, @RequestParam long candidate, @RequestParam(defaultValue = "10") int regressions) {
        var b = store.run(baseline);
        var c = store.run(candidate);
        if (b.isEmpty() || c.isEmpty()) return notFound("run");
        return ResponseEntity.ok(reports.compare(b.get(), c.get(), regressions));
    }

    /** {"baseline": 1, "candidate": 2, "rules": [{"metric": "exact_match", "maxDrop": 0.01, "alpha": 0.05, "minValue": 0.5}]} */
    @PostMapping("/gates/check")
    public ResponseEntity<?> gate(@RequestBody JsonNode body) {
        var b = store.run(body.path("baseline").asLong());
        var c = store.run(body.path("candidate").asLong());
        if (b.isEmpty() || c.isEmpty()) return notFound("run");
        List<Reports.Rule> rules = new ArrayList<>();
        for (JsonNode r : body.path("rules")) {
            rules.add(new Reports.Rule(r.path("metric").asText(), r.hasNonNull("maxDrop") ? r.get("maxDrop").asDouble() : null,
                    r.hasNonNull("alpha") ? r.get("alpha").asDouble() : null, r.hasNonNull("minValue") ? r.get("minValue").asDouble() : null));
        }
        if (rules.isEmpty()) throw new IllegalArgumentException("rules required");
        ObjectNode res = reports.gate(b.get(), c.get(), rules);
        // 200 either way; CI reads "pass" (or uses scripts/gate.sh, which sets the exit code)
        return ResponseEntity.ok(res);
    }
}
