package dev.siddhant.evalops.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.metrics.Metrics;
import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Item;
import dev.siddhant.evalops.store.Store.Result;
import dev.siddhant.evalops.store.Store.Run;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Executes runs: every dataset item is sent to the target (an
 * OpenAI-compatible /v1/completions endpoint), scored with each metric and
 * stored. Concurrency is bounded per run; transient failures (connection
 * errors, 429, 5xx) are retried with exponential backoff; responses are
 * cached by (target configuration, input) so a re-run is free and
 * deterministic. Results are written per item, so a run interrupted by a
 * restart resumes with the items it has not finished.
 */
@Service
public class RunService {
    private static final Logger log = LoggerFactory.getLogger(RunService.class);
    private final Store store;
    private final MeterRegistry metrics;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();
    private final Duration timeout;
    private final int maxAttempts;
    private final Map<Long, Thread> active = new java.util.concurrent.ConcurrentHashMap<>();
    private final Judge judge;

    public RunService(Store store, MeterRegistry metrics, Judge judge, @Value("${evalops.request-timeout:60s}") Duration timeout,
                      @Value("${evalops.max-attempts:3}") int maxAttempts) {
        this.store = store;
        this.metrics = metrics;
        this.judge = judge;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
    }

    /** Resume runs that were queued or running when the service stopped. */
    @EventListener(ApplicationReadyEvent.class)
    public void resumeInterrupted() {
        for (String st : List.of("running", "queued")) {
            for (Run r : store.runsWithStatus(st)) {
                if (r.worker()) continue; // worker runs live in the task table; workers pick them up again
                log.info("resuming run {}", r.id());
                start(r.id());
            }
        }
    }

    public void start(long runId) {
        active.computeIfAbsent(runId, id -> Thread.ofVirtual().name("run-" + id).start(() -> {
            try {
                execute(id);
            } finally {
                active.remove(id);
            }
        }));
    }

    public boolean isActive(long runId) {
        return active.containsKey(runId);
    }

    void execute(long runId) {
        Run run = store.run(runId).orElseThrow();
        store.setStatus(runId, "running", null);
        Set<Integer> done = store.results(runId).stream().map(Result::idx).collect(Collectors.toSet());
        List<Item> todo = store.items(run.datasetId()).stream().filter(i -> !done.contains(i.idx())).toList();
        Semaphore permits = new Semaphore(Math.max(1, run.concurrency()));
        var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
        for (Item it : todo) {
            futures.add(pool.submit(() -> {
                permits.acquireUninterruptibly();
                try {
                    store.putResult(runId, evaluate(run, it));
                } finally {
                    permits.release();
                }
            }));
        }
        try {
            for (var f : futures) f.get();
            List<Result> res = store.results(runId);
            long errors = res.stream().filter(r -> r.error() != null).count();
            if (!res.isEmpty() && errors == res.size()) store.setStatus(runId, "failed", "every item failed; first error: " + res.get(0).error());
            else store.setStatus(runId, "done", null);
        } catch (Exception e) {
            log.error("run {} failed", runId, e);
            store.setStatus(runId, "failed", e.toString());
        }
    }

    Result evaluate(Run run, Item it) {
        JsonNode snap;
        try {
            snap = Store.JSON.readTree(run.targetSnapshot());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String key = Store.sha256(run.targetSnapshot() + "\n" + it.input());
        String output = null, error = null;
        boolean cached = false;
        int attempts = 0;
        long t0 = System.nanoTime();
        if (run.useCache()) {
            var hit = store.cached(key);
            if (hit.isPresent()) {
                output = hit.get();
                cached = true;
            }
        }
        while (output == null && attempts < maxAttempts) {
            attempts++;
            try {
                output = complete(snap, it.input());
                if (run.useCache()) store.cache(key, output);
            } catch (Transient e) {
                error = e.getMessage();
                sleep(200L << (attempts - 1));
            } catch (Exception e) {
                error = e.getMessage() == null ? e.toString() : e.getMessage();
                break;
            }
        }
        double ms = (System.nanoTime() - t0) / 1e6;
        Map<String, Double> scores = score(run, it, output);
        metrics.counter("evalops.items", "outcome", output == null ? "error" : cached ? "cached" : "ok").increment();
        return new Result(it.idx(), output, cached ? null : ms, output == null ? error : null, scores, cached, attempts);
    }

    /** Score an output with every metric of the run; a missing output (an error) scores 0. */
    public Map<String, Double> score(Run run, Item it, String output) {
        Map<String, Double> scores = new LinkedHashMap<>();
        for (String m : run.metrics()) {
            if (m.equals(Judge.METRIC)) {
                if (output == null) {
                    scores.put(m, 0.0);
                    continue;
                }
                Judge.Verdict v = judge.judge(run.judgeSnapshot(), it.input(), it.reference(), output, run.useCache());
                scores.put(m, v.score());
                scores.put(Judge.METRIC + "_parsed", v.parsed() ? 1.0 : 0.0);
            } else {
                scores.put(m, output == null ? 0.0 : Metrics.ALL.get(m).score(output, it.reference()));
            }
        }
        return scores;
    }

    public static class Transient extends Exception {
        public Transient(String m) {
            super(m);
        }
    }

    String complete(JsonNode snap, String prompt) throws Exception {
        ObjectNode body = snap.path("params").isObject() ? ((ObjectNode) snap.get("params")).deepCopy() : Store.JSON.createObjectNode();
        if (snap.hasNonNull("model")) body.put("model", snap.get("model").asText());
        body.put("prompt", prompt);
        body.put("stream", false);
        var req = HttpRequest.newBuilder(URI.create(snap.path("url").asText())).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new Transient("connection error: " + e.getMessage());
        }
        int sc = resp.statusCode();
        if (sc == 429 || sc >= 500) throw new Transient("HTTP " + sc);
        if (sc != 200) throw new IllegalStateException("HTTP " + sc + ": " + resp.body());
        JsonNode r = Store.JSON.readTree(resp.body());
        JsonNode text = r.path("choices").path(0).path("text");
        if (!text.isTextual()) throw new IllegalStateException("response has no choices[0].text");
        return text.asText();
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
