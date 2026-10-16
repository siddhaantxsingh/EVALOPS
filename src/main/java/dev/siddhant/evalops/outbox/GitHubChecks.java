package dev.siddhant.evalops.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.runner.Reports;
import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Event;
import dev.siddhant.evalops.store.Store.Run;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes evaluation runs as GitHub check runs. A run created with
 * {@code "github": {"owner", "repo", "head_sha", "gate"?}} gets one check
 * run on that commit: queued, then in progress, then completed with a
 * conclusion (the gate's verdict when a gate is given, otherwise whether the
 * run finished) and a Markdown summary of the metrics.
 *
 * <p>Idempotency: the check run's id is stored per evaluation run, and
 * creation first looks for an existing check run on the commit with the
 * same {@code external_id}. A redelivered event, or a crash between
 * GitHub's answer and storing the id, therefore updates the existing check
 * run instead of creating a second one. Updates are PATCHes of the full
 * state, so repeating one is harmless.
 *
 * <p>Auth: either a token ({@code evalops.github.token}), or a GitHub App
 * ({@code app-id}, {@code installation-id}, {@code private-key} as a PKCS#8
 * PEM file), from which installation tokens are minted and cached.
 */
@Component
public class GitHubChecks implements EventConsumer {
    private static final Logger log = LoggerFactory.getLogger(GitHubChecks.class);
    private final Store store;
    private final Reports reports;
    private final String api;
    private final String checkName;
    private final String staticToken;
    private final String appId;
    private final String installationId;
    private final String privateKeyPath;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private String installationToken;
    private Instant installationTokenExpires = Instant.EPOCH;

    public GitHubChecks(Store store, Reports reports, @Value("${evalops.github.api-url:https://api.github.com}") String api,
                        @Value("${evalops.github.check-name:EvalOps}") String checkName, @Value("${evalops.github.token:}") String staticToken,
                        @Value("${evalops.github.app-id:}") String appId, @Value("${evalops.github.installation-id:}") String installationId,
                        @Value("${evalops.github.private-key:}") String privateKeyPath) {
        this.store = store;
        this.reports = reports;
        this.api = api.replaceAll("/+$", "");
        this.checkName = checkName;
        this.staticToken = staticToken;
        this.appId = appId;
        this.installationId = installationId;
        this.privateKeyPath = privateKeyPath;
    }

    public boolean configured() {
        return !staticToken.isBlank() || (!appId.isBlank() && !installationId.isBlank() && !privateKeyPath.isBlank());
    }

    @Override
    public String name() {
        return "github-checks";
    }

    /** Thrown for answers that retrying cannot fix (for example 404 or 422): the event is dropped with a warning. */
    static class Permanent extends RuntimeException {
        Permanent(String m) {
            super(m);
        }
    }

    @Override
    public void accept(Event e) throws Exception {
        if (!configured() || !e.type().startsWith("run.")) return;
        Run run = store.run(e.aggregateId()).orElse(null);
        if (run == null || run.github() == null) return;
        JsonNode gh = Store.JSON.readTree(run.github());
        try {
            long id = ensureCheck(run, gh);
            ObjectNode patch = Store.JSON.createObjectNode();
            switch (e.type()) {
                case "run.queued" -> {
                    return; // created as queued
                }
                case "run.running" -> patch.put("status", "in_progress");
                case "run.finished" -> completed(run, gh, patch);
                default -> {
                    return;
                }
            }
            call("PATCH", repo(gh) + "/check-runs/" + id, patch);
        } catch (Permanent p) {
            log.warn("GitHub check for run {} dropped: {}", run.id(), p.getMessage());
        }
    }

    private String repo(JsonNode gh) {
        return "/repos/" + gh.path("owner").asText() + "/" + gh.path("repo").asText();
    }

    static String externalId(long runId) {
        return "evalops-run-" + runId;
    }

    long ensureCheck(Run run, JsonNode gh) throws Exception {
        var known = store.checkRunId(run.id());
        if (known.isPresent()) return known.get();
        String sha = gh.path("head_sha").asText();
        JsonNode list = call("GET", repo(gh) + "/commits/" + sha + "/check-runs?filter=all&check_name=" + URLEncoder.encode(checkName,
                StandardCharsets.UTF_8), null);
        for (JsonNode cr : list.path("check_runs")) {
            if (externalId(run.id()).equals(cr.path("external_id").asText())) {
                store.putCheckRunId(run.id(), cr.path("id").asLong());
                return cr.path("id").asLong();
            }
        }
        ObjectNode body = Store.JSON.createObjectNode().put("name", checkName).put("head_sha", sha).put("external_id", externalId(run.id()))
                .put("status", "queued");
        long id = call("POST", repo(gh) + "/check-runs", body).path("id").asLong();
        store.putCheckRunId(run.id(), id);
        return id;
    }

    private void completed(Run run, JsonNode gh, ObjectNode patch) {
        Run fresh = store.run(run.id()).orElseThrow();
        boolean ok = fresh.status().equals("done");
        StringBuilder sum = new StringBuilder();
        ObjectNode s = reports.summary(fresh);
        sum.append("Run ").append(run.id()).append(": ").append(fresh.status()).append(", ").append(s.path("completed").asInt()).append(" items, ")
                .append(s.path("errors").asInt()).append(" errors.\n\n| metric | mean | 95 % interval |\n|---|---|---|\n");
        s.path("metrics").fields().forEachRemaining(m -> sum.append(String.format("| %s | %.4f | [%.4f, %.4f] |%n", m.getKey(),
                m.getValue().path("mean").asDouble(), m.getValue().path("ci95_lo").asDouble(), m.getValue().path("ci95_hi").asDouble())));
        String title = "Run " + run.id() + " " + fresh.status();
        JsonNode gate = gh.path("gate");
        if (ok && gate.isObject()) {
            List<Reports.Rule> rules = new ArrayList<>();
            for (JsonNode r : gate.path("rules")) {
                rules.add(new Reports.Rule(r.path("metric").asText(), r.hasNonNull("maxDrop") ? r.get("maxDrop").asDouble() : null,
                        r.hasNonNull("alpha") ? r.get("alpha").asDouble() : null, r.hasNonNull("minValue") ? r.get("minValue").asDouble() : null));
            }
            var base = store.run(gate.path("baseline").asLong());
            if (base.isEmpty()) {
                ok = false;
                title = "Gate baseline run not found";
            } else {
                ObjectNode g = reports.gate(base.get(), fresh, rules);
                ok = g.path("pass").asBoolean();
                title = "Gate " + (ok ? "passed" : "failed") + " against run " + base.get().id();
                sum.append("\n**Gate** against baseline run ").append(base.get().id()).append(":\n\n");
                for (JsonNode c : g.path("checks")) {
                    sum.append("* ").append(c.path("pass").asBoolean() ? "PASS " : "FAIL ").append(c.path("metric").asText()).append(": ")
                            .append(c.path("reason").asText()).append('\n');
                }
            }
        }
        patch.put("status", "completed").put("conclusion", ok ? "success" : "failure")
                .put("completed_at", (fresh.finished() == null ? Instant.now() : fresh.finished()).toString());
        patch.putObject("output").put("title", title).put("summary", sum.toString());
    }

    JsonNode call(String method, String path, JsonNode body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(api + path)).timeout(Duration.ofSeconds(20)).header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "evalops").header("Authorization", "Bearer " + token());
        var pub = body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString());
        if (body != null) b.header("Content-Type", "application/json");
        HttpResponse<String> resp;
        try {
            resp = http.send(b.method(method, pub).build(), HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException("GitHub unreachable: " + e.getMessage(), e);
        }
        int sc = resp.statusCode();
        if (sc / 100 == 2) return resp.body().isBlank() ? Store.JSON.createObjectNode() : Store.JSON.readTree(resp.body());
        if (sc == 401) {
            synchronized (this) {
                installationTokenExpires = Instant.EPOCH;
            }
        }
        if (sc == 401 || sc == 403 || sc == 408 || sc == 429 || sc >= 500) throw new IllegalStateException(method + " " + path + ": HTTP " + sc);
        throw new Permanent(method + " " + path + ": HTTP " + sc + " " + resp.body());
    }

    synchronized String token() throws Exception {
        if (!staticToken.isBlank()) return staticToken;
        if (installationToken != null && Instant.now().isBefore(installationTokenExpires.minusSeconds(300))) return installationToken;
        var req = HttpRequest.newBuilder(URI.create(api + "/app/installations/" + installationId + "/access_tokens")).timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "evalops")
                .header("Authorization", "Bearer " + appJwt(appId, Files.readString(Path.of(privateKeyPath)), Instant.now()))
                .POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 201) throw new IllegalStateException("installation token: HTTP " + resp.statusCode());
        JsonNode t = Store.JSON.readTree(resp.body());
        installationToken = t.path("token").asText();
        installationTokenExpires = Instant.parse(t.path("expires_at").asText());
        return installationToken;
    }

    /** A GitHub App JWT (RS256): issued 60 s in the past against clock drift, valid 9 minutes. */
    public static String appJwt(String appId, String pkcs8Pem, Instant now) throws Exception {
        String b64 = pkcs8Pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        if (pkcs8Pem.contains("BEGIN RSA PRIVATE KEY")) {
            throw new IllegalArgumentException("convert the key to PKCS#8: openssl pkcs8 -topk8 -nocrypt -in app.pem -out app-pkcs8.pem");
        }
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        var enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String claims = enc.encodeToString(("{\"iat\":" + (now.getEpochSecond() - 60) + ",\"exp\":" + (now.getEpochSecond() + 540) + ",\"iss\":\"" + appId
                + "\"}").getBytes(StandardCharsets.UTF_8));
        Signature sig = Signature.getInstance("SHA256withRSA");
        sig.initSign(key);
        sig.update((header + "." + claims).getBytes(StandardCharsets.UTF_8));
        return header + "." + claims + "." + enc.encodeToString(sig.sign());
    }
}
