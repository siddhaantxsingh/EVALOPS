package dev.siddhant.evalops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import dev.siddhant.evalops.runner.RunService;
import dev.siddhant.evalops.store.Store;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * EvalOps end to end against a fake OpenAI-compatible endpoint whose answer
 * quality is a request parameter ("quality": probability of answering
 * correctly, decided by a hash of the prompt so it is deterministic), and
 * which injects transient 503s and permanent 400s.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EvalOpsIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static HttpServer fake;
    static final AtomicInteger requests = new AtomicInteger();
    static final Map<String, AtomicInteger> perPrompt = new ConcurrentHashMap<>();

    @LocalServerPort
    int port;
    @Autowired
    Store store;
    @Autowired
    RunService runService;
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:evalops" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        r.add("evalops.bootstrap-resamples", () -> "999");
    }

    @BeforeAll
    static void startFake() throws Exception {
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        fake.createContext("/v1/completions", ex -> {
            requests.incrementAndGet();
            JsonNode req = JSON.readTree(ex.getRequestBody());
            String prompt = req.path("prompt").asText();
            double quality = req.path("quality").asDouble(1.0);
            int n = perPrompt.computeIfAbsent(prompt + "|" + quality, k -> new AtomicInteger()).incrementAndGet();
            int code = 200;
            String text;
            if (prompt.contains("FLAKY") && n == 1) {
                code = 503; // first attempt fails, the retry succeeds
                text = "";
            } else if (prompt.contains("BROKEN")) {
                code = 400;
                text = "";
            } else {
                String answer = prompt.substring(prompt.lastIndexOf(' ') + 1).toUpperCase();
                double u = (Math.floorMod(prompt.hashCode() * 31 + 7, 1000)) / 1000.0;
                text = u < quality ? answer : answer.toLowerCase() + "?";
            }
            ObjectNode resp = JSON.createObjectNode();
            resp.putArray("choices").addObject().put("text", text).put("index", 0);
            byte[] b = (code == 200 ? resp.toString() : "{\"error\":\"x\"}").getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        fake.start();
    }

    @AfterAll
    static void stopFake() {
        fake.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + port;
    }

    JsonNode send(String method, String path, Object body, int expect) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(base() + path)).header("Content-Type", "application/json");
        var req = switch (method) {
            case "GET" -> b.GET();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            default -> b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        };
        var resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(resp.statusCode()).as(method + " " + path + ": " + resp.body()).isEqualTo(expect);
        return JSON.readTree(resp.body());
    }

    ObjectNode dataset(String name, int n, String extra) {
        ObjectNode d = JSON.createObjectNode().put("name", name);
        ArrayNode items = d.putArray("items");
        for (int i = 0; i < n; i++) items.addObject().put("input", "question " + i + extra + " answer w" + i).put("reference", "W" + i);
        return d;
    }

    void target(String name, double quality) throws Exception {
        send("PUT", "/targets/" + name, Map.of("url", "http://127.0.0.1:" + fake.getAddress().getPort() + "/v1/completions", "model", "fake",
                "params", Map.of("quality", quality, "max_tokens", 8)), 200);
    }

    JsonNode runAndWait(Map<String, Object> body) throws Exception {
        long id = send("POST", "/runs", body, 202).path("run").asLong();
        for (int i = 0; i < 600; i++) {
            JsonNode s = send("GET", "/runs/" + id, null, 200);
            if (s.path("status").asText().matches("done|failed")) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("run " + id + " did not finish");
    }

    @Test
    void datasetsAreVersionedByContent() throws Exception {
        JsonNode v1 = send("POST", "/datasets", dataset("ver", 5, ""), 201);
        JsonNode again = send("POST", "/datasets", dataset("ver", 5, ""), 201);
        JsonNode v2 = send("POST", "/datasets", dataset("ver", 6, ""), 201);
        assertThat(v1.path("version").asInt()).isEqualTo(1);
        assertThat(again.path("id").asLong()).isEqualTo(v1.path("id").asLong());
        assertThat(v2.path("version").asInt()).isEqualTo(2);
        assertThat(send("GET", "/datasets/ver/1", null, 200).path("items")).hasSize(5);
        send("GET", "/datasets/ver/9", null, 404);
        send("POST", "/datasets", Map.of("name", "bad name!", "items", java.util.List.of(Map.of("input", "x"))), 400);
        send("POST", "/datasets", Map.of("name", "empty", "items", java.util.List.of()), 400);
    }

    @Test
    void runsRetryCacheAndReport() throws Exception {
        send("POST", "/datasets", dataset("qa", 60, " FLAKY"), 201);
        target("perfect", 1.0);
        int before = requests.get();
        JsonNode s = runAndWait(Map.of("dataset", "qa", "target", "perfect", "metrics", java.util.List.of("exact_match", "edit_similarity"),
                "concurrency", 8));
        assertThat(s.path("status").asText()).isEqualTo("done");
        assertThat(s.path("completed").asInt()).isEqualTo(60);
        assertThat(s.path("errors").asInt()).isZero();
        assertThat(s.path("metrics").path("exact_match").path("mean").asDouble()).isEqualTo(1.0);
        assertThat(requests.get() - before).as("one 503 and one retry per item").isEqualTo(120);
        long run1 = s.path("run").asLong();
        assertThat(store.results(run1)).allMatch(r -> r.attempts() == 2);

        // identical configuration: served from the cache, nothing sent
        int mid = requests.get();
        JsonNode s2 = runAndWait(Map.of("dataset", "qa", "target", "perfect", "metrics", java.util.List.of("exact_match")));
        assertThat(requests.get()).isEqualTo(mid);
        assertThat(s2.path("cached").asInt()).isEqualTo(60);
        // cache disabled: sent again
        runAndWait(Map.of("dataset", "qa", "target", "perfect", "metrics", java.util.List.of("exact_match"), "cache", false));
        assertThat(requests.get() - mid).isEqualTo(60);

        // permanent errors are recorded per item, scored 0, not retried
        send("POST", "/datasets", dataset("broken", 4, " BROKEN"), 201);
        int b0 = requests.get();
        JsonNode sb = runAndWait(Map.of("dataset", "broken", "target", "perfect", "metrics", java.util.List.of("exact_match")));
        assertThat(sb.path("status").asText()).isEqualTo("failed");
        assertThat(sb.path("errors").asInt()).isEqualTo(4);
        assertThat(requests.get() - b0).isEqualTo(4);

        // validation
        send("POST", "/runs", Map.of("dataset", "qa", "target", "perfect", "metrics", java.util.List.of("nope")), 400);
        send("POST", "/runs", Map.of("dataset", "qa", "target", "ghost", "metrics", java.util.List.of("exact_match")), 404);
        send("POST", "/runs", Map.of("dataset", "ghost", "target", "perfect", "metrics", java.util.List.of("exact_match")), 404);
    }

    @Test
    void comparisonsAndGatesSeparateRealRegressionsFromNoise() throws Exception {
        send("POST", "/datasets", dataset("gate", 300, ""), 201);
        target("strong", 0.85);
        target("strong-again", 0.85000001); // a different configuration (no cache sharing) of equal quality
        target("weak", 0.65);
        var metrics = java.util.List.of("exact_match", "edit_similarity");
        long strong = runAndWait(Map.of("dataset", "gate", "target", "strong", "metrics", metrics, "concurrency", 16)).path("run").asLong();
        long again = runAndWait(Map.of("dataset", "gate", "target", "strong-again", "metrics", metrics, "concurrency", 16)).path("run").asLong();
        long weak = runAndWait(Map.of("dataset", "gate", "target", "weak", "metrics", metrics, "concurrency", 16)).path("run").asLong();

        JsonNode cmp = send("GET", "/compare?baseline=" + strong + "&candidate=" + weak, null, 200);
        JsonNode em = cmp.path("metrics").path("exact_match");
        assertThat(em.path("delta").asDouble()).isBetween(-0.3, -0.1);
        assertThat(em.path("ci95_hi").asDouble()).isNegative();
        assertThat(em.path("p_value").asDouble()).isLessThan(0.01);
        assertThat(cmp.path("regressions").get(0).path("items")).isNotEmpty();
        JsonNode reg = cmp.path("regressions").get(0).path("items").get(0);
        assertThat(reg.path("baseline_score").asDouble()).isGreaterThan(reg.path("candidate_score").asDouble());

        var rule = java.util.List.of(Map.of("metric", "exact_match", "maxDrop", 0.02, "alpha", 0.05));
        assertThat(send("POST", "/gates/check", Map.of("baseline", strong, "candidate", weak, "rules", rule), 200).path("pass").asBoolean()).isFalse();
        assertThat(send("POST", "/gates/check", Map.of("baseline", weak, "candidate", strong, "rules", rule), 200).path("pass").asBoolean()).isTrue();
        // same quality, different configuration: no false alarm
        JsonNode same = send("POST", "/gates/check", Map.of("baseline", strong, "candidate", again, "rules", rule), 200);
        assertThat(same.path("pass").asBoolean()).as(same.toString()).isTrue();
        // absolute floor
        var floor = java.util.List.of(Map.of("metric", "exact_match", "minValue", 0.8));
        assertThat(send("POST", "/gates/check", Map.of("baseline", strong, "candidate", weak, "rules", floor), 200).path("checks").get(0)
                .path("reason").asText()).contains("below the minimum");
        // runs over different dataset versions cannot be paired
        send("POST", "/datasets", dataset("gate", 301, ""), 201);
        long other = runAndWait(Map.of("dataset", "gate", "target", "strong", "metrics", metrics)).path("run").asLong();
        send("GET", "/compare?baseline=" + strong + "&candidate=" + other, null, 400);
    }

    @Test
    void interruptedRunsResumeWithoutRedoingFinishedItems() throws Exception {
        JsonNode ds = send("POST", "/datasets", dataset("resume", 20, ""), 201);
        target("resumer", 1.0);
        var t = store.target("resumer").orElseThrow();
        String snap = Store.canonical(JSON.createObjectNode().put("url", t.url()).put("model", t.model()).set("params", JSON.readTree(t.params())));
        long id = store.createRun(ds.path("id").asLong(), t.id(), snap, java.util.List.of("exact_match"), 4, false);
        // as if the service died after 7 items
        for (int i = 0; i < 7; i++) store.putResult(id, new Store.Result(i, "W" + i, 1.0, null, Map.of("exact_match", 1.0), false, 1));
        store.setStatus(id, "running", null);
        int before = requests.get();
        runService.resumeInterrupted();
        for (int i = 0; i < 200 && !store.run(id).orElseThrow().status().equals("done"); i++) Thread.sleep(50);
        assertThat(store.run(id).orElseThrow().status()).isEqualTo("done");
        assertThat(requests.get() - before).isEqualTo(13);
        assertThat(store.results(id)).hasSize(20);
    }
}
