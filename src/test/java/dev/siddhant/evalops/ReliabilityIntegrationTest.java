package dev.siddhant.evalops;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.siddhant.evalops.outbox.GitHubChecks;
import dev.siddhant.evalops.outbox.OutboxRelay;
import dev.siddhant.evalops.outbox.RunCounters;
import dev.siddhant.evalops.runner.Judge;
import dev.siddhant.evalops.runner.Reports;
import dev.siddhant.evalops.store.Store;
import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Outbox and idempotency, worker leases with fencing tokens (real Python
 * worker processes, killed and paused), the LLM judge against a fake chat
 * endpoint, and the GitHub check-run publisher against a fake GitHub API.
 * The scheduled relay is off; tests drain the outbox explicitly, which is
 * how a crash between commit and publish is simulated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReliabilityIntegrationTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static HttpServer fake;
    static final AtomicInteger targetCalls = new AtomicInteger();
    static final AtomicInteger judgeCalls = new AtomicInteger();
    static final List<JsonNode> judgeBodies = new CopyOnWriteArrayList<>();
    static final AtomicBoolean webhookDown = new AtomicBoolean();
    static final List<String> webhookIds = new CopyOnWriteArrayList<>();
    static final List<String[]> github = new CopyOnWriteArrayList<>(); // method, path, auth, accept, api-version, body
    static final Map<Long, ObjectNode> checkRuns = new ConcurrentHashMap<>();
    static final AtomicInteger nextCheckId = new AtomicInteger(1000);
    static volatile java.security.PublicKey appPublicKey;

    @LocalServerPort
    int port;
    @Autowired
    Store store;
    @Autowired
    OutboxRelay relay;
    @Autowired
    RunCounters counters;
    @Autowired
    GitHubChecks checks;
    @Autowired
    Reports reports;
    @Autowired
    JdbcClient db;
    @Autowired
    List<dev.siddhant.evalops.outbox.EventConsumer> consumers;
    final HttpClient http = HttpClient.newHttpClient();

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) throws Exception {
        startFake();
        String base = "http://127.0.0.1:" + fake.getAddress().getPort();
        r.add("spring.datasource.url", () -> "jdbc:h2:mem:rel" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        r.add("evalops.bootstrap-resamples", () -> "199");
        r.add("evalops.outbox.relay-enabled", () -> "false");
        r.add("evalops.outbox.webhooks", () -> base + "/hook");
        r.add("evalops.github.api-url", () -> base + "/gh");
        r.add("evalops.github.token", () -> "ghs_test_token");
    }

    static void reply(HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, code == 204 ? -1 : b.length);
        if (code != 204) ex.getResponseBody().write(b);
        ex.close();
    }

    static void startFake() throws Exception {
        if (fake != null) return;
        fake = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fake.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        // completions target: answers the prompt's last word in upper case; "SLOW" prompts take 2 s and number their answers
        fake.createContext("/v1/completions", ex -> {
            int n = targetCalls.incrementAndGet();
            JsonNode req = JSON.readTree(ex.getRequestBody());
            String prompt = req.path("prompt").asText();
            String answer = prompt.substring(prompt.lastIndexOf(' ') + 1).toUpperCase();
            if (prompt.contains("WRONG")) answer = "nonsense";
            if (prompt.contains("SLOW")) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    return;
                }
                answer = answer + "#" + n;
            }
            ObjectNode resp = JSON.createObjectNode();
            resp.putArray("choices").addObject().put("text", answer);
            reply(ex, 200, resp.toString());
        });
        // chat judge: YES when the candidate equals the reference, NO otherwise, an unparsable answer for "UNSURE" questions
        fake.createContext("/v1/chat/completions", ex -> {
            judgeCalls.incrementAndGet();
            JsonNode req = JSON.readTree(ex.getRequestBody());
            judgeBodies.add(req);
            String user = req.path("messages").get(1).path("content").asText();
            String ref = user.replaceAll("(?s).*Reference answer: (.*?)\n.*", "$1");
            String cand = user.replaceAll("(?s).*Candidate answer: (.*?)\n.*", "$1");
            String verdict = user.contains("UNSURE") ? "Maybe." : cand.equalsIgnoreCase(ref) ? "YES" : "No, it is wrong.";
            ObjectNode resp = JSON.createObjectNode();
            resp.putArray("choices").addObject().putObject("message").put("role", "assistant").put("content", verdict);
            reply(ex, 200, resp.toString());
        });
        fake.createContext("/hook", ex -> {
            String id = ex.getRequestHeaders().getFirst("X-EvalOps-Event-Id");
            ex.getRequestBody().readAllBytes();
            if (webhookDown.get()) {
                reply(ex, 503, "{}");
                return;
            }
            webhookIds.add(id);
            reply(ex, 204, "");
        });
        // fake GitHub REST API: check runs, and installation tokens for App auth
        fake.createContext("/gh/", ex -> {
            String method = ex.getRequestMethod();
            String path = ex.getRequestURI().getPath().substring(3);
            String query = ex.getRequestURI().getRawQuery();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            github.add(new String[] {method, path + (query == null ? "" : "?" + query), auth, ex.getRequestHeaders().getFirst("Accept"),
                    ex.getRequestHeaders().getFirst("X-GitHub-Api-Version"), body});
            if (path.matches("/app/installations/\\d+/access_tokens") && method.equals("POST")) {
                if (!validJwt(auth)) {
                    reply(ex, 401, "{\"message\":\"bad jwt\"}");
                    return;
                }
                reply(ex, 201, "{\"token\":\"ghs_from_app\",\"expires_at\":\"" + Instant.now().plusSeconds(3600) + "\"}");
                return;
            }
            if (auth == null || !(auth.equals("Bearer ghs_test_token") || auth.equals("Bearer ghs_from_app"))) {
                reply(ex, 401, "{\"message\":\"Bad credentials\"}");
                return;
            }
            if (method.equals("GET") && path.matches("/repos/[^/]+/[^/]+/commits/[0-9a-f]{40}/check-runs")) {
                String sha = path.split("/")[5];
                ObjectNode out = JSON.createObjectNode();
                ArrayNode arr = out.putArray("check_runs");
                checkRuns.values().stream().filter(c -> c.path("head_sha").asText().equals(sha)).forEach(arr::add);
                out.put("total_count", arr.size());
                reply(ex, 200, out.toString());
            } else if (method.equals("POST") && path.matches("/repos/[^/]+/[^/]+/check-runs")) {
                ObjectNode c = (ObjectNode) JSON.readTree(body);
                if (!c.path("name").isTextual() || !c.path("head_sha").asText().matches("[0-9a-f]{40}")) {
                    reply(ex, 422, "{\"message\":\"Validation Failed\"}");
                    return;
                }
                long id = nextCheckId.incrementAndGet();
                c.put("id", id);
                checkRuns.put(id, c);
                reply(ex, 201, c.toString());
            } else if (method.equals("PATCH") && path.matches("/repos/[^/]+/[^/]+/check-runs/\\d+")) {
                long id = Long.parseLong(path.substring(path.lastIndexOf('/') + 1));
                ObjectNode c = checkRuns.get(id);
                if (c == null) {
                    reply(ex, 404, "{\"message\":\"Not Found\"}");
                    return;
                }
                c.setAll((ObjectNode) JSON.readTree(body));
                reply(ex, 200, c.toString());
            } else {
                reply(ex, 404, "{\"message\":\"Not Found\"}");
            }
        });
        fake.start();
    }

    static boolean validJwt(String auth) {
        try {
            String[] p = auth.substring("Bearer ".length()).split("\\.");
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(appPublicKey);
            s.update((p[0] + "." + p[1]).getBytes(StandardCharsets.UTF_8));
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(p[1]));
            long now = Instant.now().getEpochSecond();
            return s.verify(Base64.getUrlDecoder().decode(p[2])) && claims.path("iss").asText().equals("12345") && claims.path("iat").asLong() <= now
                    && claims.path("exp").asLong() > now && claims.path("exp").asLong() - claims.path("iat").asLong() <= 600;
        } catch (Exception e) {
            return false;
        }
    }

    @AfterAll
    static void stopFake() {
        fake.stop(0);
    }

    String fakeBase() {
        return "http://127.0.0.1:" + fake.getAddress().getPort();
    }

    HttpResponse<String> call(String method, String path, Object body, Map<String, String> headers) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json");
        headers.forEach(b::header);
        var req = switch (method) {
            case "GET" -> b.GET();
            case "PUT" -> b.PUT(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
            default -> b.POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        };
        return http.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode send(String method, String path, Object body, int expect) throws Exception {
        var resp = call(method, path, body, Map.of());
        assertThat(resp.statusCode()).as(method + " " + path + ": " + resp.body()).isEqualTo(expect);
        return resp.body().isBlank() ? null : JSON.readTree(resp.body());
    }

    void dataset(String name, int n, String extra) throws Exception {
        ObjectNode d = JSON.createObjectNode().put("name", name);
        ArrayNode items = d.putArray("items");
        for (int i = 0; i < n; i++) items.addObject().put("input", "question " + i + extra + " answer w" + i).put("reference", "W" + i);
        send("POST", "/datasets", d, 201);
    }

    void target(String name, String path) throws Exception {
        send("PUT", "/targets/" + name, Map.of("url", fakeBase() + path, "model", "fake", "params", Map.of("max_tokens", 8)), 200);
    }

    JsonNode waitRun(long id, int seconds) throws Exception {
        for (int i = 0; i < seconds * 20; i++) {
            JsonNode s = send("GET", "/runs/" + id, null, 200);
            if (s.path("status").asText().matches("done|failed")) return s;
            Thread.sleep(50);
        }
        throw new AssertionError("run " + id + " did not finish: " + send("GET", "/runs/" + id, null, 200));
    }

    long count(String sql, Object... params) {
        return db.sql(sql).params(params).query(Long.class).single();
    }

    // ------------------------------------------------------------------ outbox

    @Test
    void eventsCommitWithStateAndAreNotLostWhenPublishingFails() throws Exception {
        dataset("ob", 5, "");
        target("ob-target", "/v1/completions");
        long id = send("POST", "/runs", Map.of("dataset", "ob", "target", "ob-target", "metrics", List.of("exact_match")), 202).path("run").asLong();
        waitRun(id, 20);
        // the state changes committed, and so did their events; nothing has been published yet ("crash" before the relay ran)
        var events = store.events(id);
        assertThat(events).extracting(Store.Event::type).containsExactly("run.queued", "run.running", "run.finished");
        assertThat(JSON.readTree(events.get(2).payload()).path("means").path("exact_match").asDouble()).isEqualTo(1.0);
        Set<String> ids = Set.copyOf(events.stream().map(Store.Event::eventId).toList());
        assertThat(webhookIds).doesNotContainAnyElementsOf(ids);
        assertThat(count("select count(*) from outbox where aggregate_id = ? and published is null", id)).isEqualTo(3);

        // the broker (webhook) is down: nothing is published, nothing is lost, failures are recorded
        webhookDown.set(true);
        relay.drain();
        assertThat(count("select count(*) from outbox where aggregate_id = ? and published is null", id)).isEqualTo(3);
        assertThat(count("select attempts from outbox where event_id = ?", events.get(0).eventId())).isEqualTo(1);
        // order within a run is kept: after the first event failed, the later ones were not attempted
        assertThat(count("select attempts from outbox where event_id = ?", events.get(2).eventId())).isZero();

        // a fresh relay (as after a restart) publishes everything once the webhook is back
        webhookDown.set(false);
        new OutboxRelay(store, consumers, true).drain();
        assertThat(count("select count(*) from outbox where aggregate_id = ? and published is null", id)).isZero();
        assertThat(webhookIds.stream().filter(ids::contains).toList()).hasSize(3).doesNotHaveDuplicates();
    }

    @Test
    void crashAfterDeliveryRedeliversAndConsumersApplyEachEventOnce() throws Exception {
        relay.drain(); // publish what other tests left behind
        dataset("dup", 3, "");
        target("dup-target", "/v1/completions");
        long before = counters.counters().getOrDefault("runs_done", 0L);
        long id = send("POST", "/runs", Map.of("dataset", "dup", "target", "dup-target", "metrics", List.of("exact_match")), 202).path("run").asLong();
        waitRun(id, 20);
        String finished = store.events(id).get(2).eventId();
        // crash right after the webhook accepted run.finished, before that was recorded
        AtomicBoolean crashed = new AtomicBoolean();
        relay.setAfterDelivery((consumer, e) -> {
            if (consumer.equals("webhooks") && e.eventId().equals(finished) && crashed.compareAndSet(false, true)) {
                throw new IllegalStateException("simulated crash");
            }
        });
        try {
            relay.drain();
            assertThat(count("select count(*) from outbox where event_id = ? and published is null", finished)).isEqualTo(1);
            relay.drain();
        } finally {
            relay.setAfterDelivery(null);
        }
        assertThat(count("select count(*) from outbox where aggregate_id = ? and published is null", id)).isZero();
        // at least once: the webhook saw the event twice, with the same id for its own deduplication
        assertThat(webhookIds.stream().filter(finished::equals).count()).isEqualTo(2);
        // the counter consumer applied it once
        assertThat(counters.counters().get("runs_done")).isEqualTo(before + 1);
        // and a duplicate delivered straight to it (e.g. by a second relay) changes nothing
        Store.Event ev = store.events(id).get(2);
        counters.accept(ev);
        counters.accept(ev);
        assertThat(counters.counters().get("runs_done")).isEqualTo(before + 1);
    }

    // ------------------------------------------------------------------ idempotency keys

    @Test
    void idempotencyKeysMakeRunCreationSafeToRetry() throws Exception {
        dataset("idem", 4, "");
        target("idem-target", "/v1/completions");
        var body = Map.of("dataset", "idem", "target", "idem-target", "metrics", List.of("exact_match"));
        var first = call("POST", "/runs", body, Map.of("Idempotency-Key", "k-1"));
        assertThat(first.statusCode()).isEqualTo(202);
        long id = JSON.readTree(first.body()).path("run").asLong();
        var again = call("POST", "/runs", body, Map.of("Idempotency-Key", "k-1"));
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(again.headers().firstValue("Idempotent-Replayed")).hasValue("true");
        assertThat(JSON.readTree(again.body()).path("run").asLong()).isEqualTo(id);
        // same key, different request
        var other = call("POST", "/runs", Map.of("dataset", "idem", "target", "idem-target", "metrics", List.of("contains")), Map.of("Idempotency-Key",
                "k-1"));
        assertThat(other.statusCode()).isEqualTo(422);

        // 8 concurrent first attempts with one key create exactly one run and one run.queued event
        long runsBefore = count("select count(*) from run");
        long eventsBefore = count("select count(*) from outbox where type = 'run.queued'");
        var pool = Executors.newVirtualThreadPerTaskExecutor();
        List<java.util.concurrent.Future<HttpResponse<String>>> fs = new ArrayList<>();
        for (int i = 0; i < 8; i++) fs.add(pool.submit(() -> call("POST", "/runs", body, Map.of("Idempotency-Key", "k-race"))));
        Set<Long> runIds = new java.util.HashSet<>();
        int created = 0;
        for (var f : fs) {
            var r = f.get();
            assertThat(r.statusCode()).as(r.body()).isIn(200, 202);
            if (r.statusCode() == 202) created++;
            runIds.add(JSON.readTree(r.body()).path("run").asLong());
        }
        assertThat(created).isEqualTo(1);
        assertThat(runIds).hasSize(1);
        assertThat(count("select count(*) from run")).isEqualTo(runsBefore + 1);
        assertThat(count("select count(*) from outbox where type = 'run.queued'")).isEqualTo(eventsBefore + 1);
        waitRun(runIds.iterator().next(), 20);
        waitRun(id, 20);
    }

    // ------------------------------------------------------------------ GitHub checks

    static final String SHA = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void checkRunsArePublishedOnceWithTheRightShape() throws Exception {
        relay.drain();
        dataset("gh", 6, "");
        target("gh-target", "/v1/completions");
        long base = send("POST", "/runs", Map.of("dataset", "gh", "target", "gh-target", "metrics", List.of("exact_match")), 202).path("run").asLong();
        waitRun(base, 20);
        var gh = Map.of("owner", "acme", "repo", "model", "head_sha", SHA, "gate", Map.of("baseline", base, "rules", List.of(Map.of("metric",
                "exact_match", "maxDrop", 0.05))));
        long id = send("POST", "/runs", Map.of("dataset", "gh", "target", "gh-target", "metrics", List.of("exact_match"), "cache", false, "github", gh),
                202).path("run").asLong();
        waitRun(id, 20);
        github.clear();
        relay.drain();

        List<String> calls = github.stream().map(c -> c[0] + " " + c[1].replaceAll("\\?.*", "")).toList();
        assertThat(calls).containsExactly("GET /repos/acme/model/commits/" + SHA + "/check-runs", "POST /repos/acme/model/check-runs",
                "PATCH /repos/acme/model/check-runs/" + store.checkRunId(id).orElseThrow(), "PATCH /repos/acme/model/check-runs/" + store.checkRunId(id)
                        .orElseThrow());
        for (String[] c : github) {
            assertThat(c[2]).isEqualTo("Bearer ghs_test_token");
            assertThat(c[3]).isEqualTo("application/vnd.github+json");
            assertThat(c[4]).isEqualTo("2022-11-28");
        }
        assertThat(github.get(0)[1]).contains("check_name=EvalOps");
        JsonNode create = JSON.readTree(github.get(1)[5]);
        assertThat(create.path("name").asText()).isEqualTo("EvalOps");
        assertThat(create.path("head_sha").asText()).isEqualTo(SHA);
        assertThat(create.path("external_id").asText()).isEqualTo("evalops-run-" + id);
        assertThat(create.path("status").asText()).isEqualTo("queued");
        assertThat(JSON.readTree(github.get(2)[5]).path("status").asText()).isEqualTo("in_progress");
        JsonNode done = JSON.readTree(github.get(3)[5]);
        assertThat(done.path("status").asText()).isEqualTo("completed");
        assertThat(done.path("conclusion").asText()).isEqualTo("success");
        assertThat(done.path("completed_at").asText()).isNotBlank();
        assertThat(done.path("output").path("title").asText()).isEqualTo("Gate passed against run " + base);
        assertThat(done.path("output").path("summary").asText()).contains("| exact_match | 1.0000").contains("PASS exact_match");
        assertThat(checkRuns).hasSize(1);

        // redelivery of run.finished: an update, not a second check run
        Store.Event finished = store.events(id).get(2);
        checks.accept(finished);
        // crash after GitHub created the check run but before its id was stored: found by external_id
        store.deleteCheckRunId(id);
        checks.accept(finished);
        assertThat(github.stream().filter(c -> c[0].equals("POST")).count()).isEqualTo(1);
        assertThat(checkRuns).hasSize(1);
        assertThat(checkRuns.values().iterator().next().path("conclusion").asText()).isEqualTo("success");

        // a failing gate gives a failing check
        dataset("gh", 6, " WRONG");
        long bad = send("POST", "/runs", Map.of("dataset", "gh", "target", "gh-target", "metrics", List.of("exact_match"), "github", Map.of("owner",
                "acme", "repo", "model", "head_sha", SHA.replace('0', 'f'))), 202).path("run").asLong();
        waitRun(bad, 20);
        relay.drain();
        ObjectNode badCheck = checkRuns.get(store.checkRunId(bad).orElseThrow());
        assertThat(badCheck.path("conclusion").asText()).isEqualTo("success"); // no gate: the run itself finished
        send("POST", "/runs", Map.of("dataset", "gh", "target", "gh-target", "metrics", List.of("exact_match"), "github", Map.of("owner", "acme")), 400);
    }

    @Test
    void gitHubAppAuthMintsAnInstallationToken() throws Exception {
        var kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        var kp = kpg.generateKeyPair();
        appPublicKey = kp.getPublic();
        Path pem = Files.createTempFile("app", ".pem");
        Files.writeString(pem, "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(kp.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----\n");
        var app = new GitHubChecks(store, reports, fakeBase() + "/gh", "EvalOps", "", "12345", "678", pem.toString());
        github.clear();
        var res = appCall(app);
        assertThat(res.path("check_runs").isArray()).isTrue();
        appCall(app); // the installation token is cached
        assertThat(github.get(0)[1]).isEqualTo("/app/installations/678/access_tokens");
        assertThat(github.stream().filter(c -> c[1].contains("access_tokens")).count()).isEqualTo(1);
        assertThat(github.get(1)[2]).isEqualTo("Bearer ghs_from_app");
        Files.delete(pem);
    }

    static JsonNode appCall(GitHubChecks app) throws Exception {
        var m = GitHubChecks.class.getDeclaredMethod("call", String.class, String.class, JsonNode.class);
        m.setAccessible(true);
        return (JsonNode) m.invoke(app, "GET", "/repos/acme/model/commits/" + SHA + "/check-runs", null);
    }

    // ------------------------------------------------------------------ LLM judge

    @Test
    void judgeScoresWithAFixedRubricAndGreedyDecoding() throws Exception {
        ObjectNode d = JSON.createObjectNode().put("name", "judged");
        ArrayNode items = d.putArray("items");
        for (int i = 0; i < 6; i++) items.addObject().put("input", "question " + i + " answer w" + i).put("reference", "W" + i);
        items.addObject().put("input", "question WRONG answer w9").put("reference", "W9");
        items.addObject().put("input", "question UNSURE answer w8").put("reference", "W8");
        send("POST", "/datasets", d, 201);
        target("judged-target", "/v1/completions");
        target("judge", "/v1/chat/completions");
        send("POST", "/runs", Map.of("dataset", "judged", "target", "judged-target", "metrics", List.of("llm_judge")), 400);
        int j0 = judgeCalls.get();
        long id = send("POST", "/runs", Map.of("dataset", "judged", "target", "judged-target", "judge", "judge", "metrics", List.of("exact_match",
                "llm_judge")), 202).path("run").asLong();
        JsonNode s = waitRun(id, 20);
        assertThat(judgeCalls.get() - j0).isEqualTo(8);
        assertThat(s.path("metrics").path("llm_judge").path("mean").asDouble()).isEqualTo(6.0 / 8);
        var results = store.results(id);
        assertThat(results.get(6).scores()).containsEntry("llm_judge", 0.0).containsEntry("llm_judge_parsed", 1.0);
        assertThat(results.get(7).scores()).containsEntry("llm_judge", 0.0).containsEntry("llm_judge_parsed", 0.0);
        JsonNode req = judgeBodies.get(judgeBodies.size() - 1);
        assertThat(req.path("temperature").asDouble()).isZero();
        assertThat(req.path("seed").asInt()).isZero();
        assertThat(req.path("messages").get(0).path("content").asText()).isEqualTo(Judge.SYSTEM);
        // an identical run reuses cached verdicts: no judge requests
        int j1 = judgeCalls.get();
        long again = send("POST", "/runs", Map.of("dataset", "judged", "target", "judged-target", "judge", "judge", "metrics", List.of("exact_match",
                "llm_judge")), 202).path("run").asLong();
        assertThat(waitRun(again, 20).path("metrics").path("llm_judge").path("mean").asDouble()).isEqualTo(6.0 / 8);
        assertThat(judgeCalls.get()).isEqualTo(j1);
        assertThat(Judge.parse(" yes.").score()).isEqualTo(1.0);
        assertThat(Judge.parse("**NO**").parsed()).isTrue();
        assertThat(Judge.parse("I think").parsed()).isFalse();
    }

    // ------------------------------------------------------------------ Python workers

    Process worker(String name, int leaseMs) throws Exception {
        var pb = new ProcessBuilder("python3", "worker/worker.py", "--evalops", "http://127.0.0.1:" + port, "--threads", "1", "--lease-ms",
                String.valueOf(leaseMs), "--name", name);
        pb.redirectErrorStream(true);
        pb.redirectOutput(new File("target/worker-" + name + ".log"));
        return pb.start();
    }

    static void signal(Process p, String sig) throws Exception {
        assertThat(new ProcessBuilder("kill", "-" + sig, String.valueOf(p.pid())).start().waitFor()).isZero();
    }

    Store.Task waitLeased(long run, int idx, int leases) throws Exception {
        for (int i = 0; i < 200; i++) {
            var t = store.task(run, idx).orElseThrow();
            if (t.leases() >= leases && t.state().equals("leased")) return t;
            Thread.sleep(50);
        }
        throw new AssertionError("task not leased: " + store.task(run, idx));
    }

    @Test
    void workersLeaseTasksAndAKilledWorkersTaskIsTakenOver() throws Exception {
        dataset("wk", 6, "");
        target("wk-target", "/v1/completions");
        // a plain worker run: three workers share six items
        long id = send("POST", "/runs", Map.of("dataset", "wk", "target", "wk-target", "metrics", List.of("exact_match"), "executor", "worker"), 202)
                .path("run").asLong();
        assertThat(send("GET", "/runs/" + id, null, 200).path("status").asText()).isEqualTo("queued");
        List<Process> ws = List.of(worker("a", 2000), worker("b", 2000), worker("c", 2000));
        try {
            JsonNode s = waitRun(id, 30);
            assertThat(s.path("status").asText()).isEqualTo("done");
            assertThat(s.path("metrics").path("exact_match").path("mean").asDouble()).isEqualTo(1.0);
            assertThat(count("select count(*) from task where run_id = ? and state = 'done'", id)).isEqualTo(6);
        } finally {
            ws.forEach(Process::destroyForcibly);
        }

        // kill -9 a worker in the middle of a slow item: its lease expires and another worker finishes the item
        dataset("wk-slow", 1, " SLOW");
        long slow = send("POST", "/runs", Map.of("dataset", "wk-slow", "target", "wk-target", "metrics", List.of("edit_similarity"), "executor",
                "worker"), 202).path("run").asLong();
        Process a = worker("killed", 1000);
        Store.Task first = waitLeased(slow, 0, 1);
        assertThat(first.owner()).startsWith("killed");
        a.destroyForcibly().waitFor();
        Process b = worker("survivor", 1000);
        try {
            JsonNode s = waitRun(slow, 30);
            assertThat(s.path("status").asText()).isEqualTo("done");
            Store.Task t = store.task(slow, 0).orElseThrow();
            assertThat(t.leases()).isEqualTo(2);
            assertThat(t.token()).isEqualTo(2);
            assertThat(t.owner()).startsWith("survivor");
            assertThat(store.results(slow)).hasSize(1);
            // the dead worker's token is rejected
            var stale = call("POST", "/worker/complete", Map.of("run", slow, "idx", 0, "token", 1, "output", "late"), Map.of());
            assertThat(stale.statusCode()).isEqualTo(409);
            assertThat(store.results(slow).get(0).output()).doesNotContain("late");
        } finally {
            b.destroyForcibly();
        }
    }

    @Test
    void aPausedWorkersLateResultIsRejectedByItsFencingToken() throws Exception {
        dataset("wk-pause", 1, " SLOW");
        target("wkp-target", "/v1/completions");
        long id = send("POST", "/runs", Map.of("dataset", "wk-pause", "target", "wkp-target", "metrics", List.of("exact_match"), "executor", "worker"),
                202).path("run").asLong();
        Process a = worker("paused", 600);
        Process b = null;
        try {
            waitLeased(id, 0, 1);
            Thread.sleep(200); // the target call is in flight
            signal(a, "STOP"); // no heartbeats: the lease expires
            Thread.sleep(900);
            b = worker("takeover", 5000);
            JsonNode s = waitRun(id, 30);
            assertThat(s.path("status").asText()).isEqualTo("done");
            String winner = store.results(id).get(0).output();
            assertThat(store.task(id, 0).orElseThrow().owner()).startsWith("takeover");
            signal(a, "CONT"); // the paused worker wakes up and posts its (older) result
            Path log = Path.of("target/worker-paused.log");
            for (int i = 0; i < 100 && !Files.readString(log).contains("stale lease"); i++) Thread.sleep(50);
            assertThat(Files.readString(log)).contains("result discarded (stale lease)");
            assertThat(store.results(id).get(0).output()).isEqualTo(winner);
            assertThat(store.results(id)).hasSize(1);
        } finally {
            a.destroyForcibly();
            if (b != null) b.destroyForcibly();
        }
    }
}
