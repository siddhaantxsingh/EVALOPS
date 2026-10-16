package dev.siddhant.evalops.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.store.Store;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * LLM-as-judge: asks a chat model (an OpenAI-style /v1/chat/completions
 * endpoint) whether a candidate answer is correct, given the question and a
 * reference answer. The rubric is fixed and versioned, decoding is greedy
 * (temperature 0, fixed seed), and verdicts are cached under the judge
 * configuration and the full prompt, so a re-run reproduces the same
 * verdicts. The verdict is the first word of the reply: YES scores 1, NO
 * scores 0; anything else scores 0 and is counted as unparsed.
 */
@Component
public class Judge {
    public static final String METRIC = "llm_judge";
    public static final String RUBRIC_VERSION = "correctness-v1";
    public static final String SYSTEM = """
            You are a strict grader. You compare a candidate answer with a reference answer.
            Reply YES if the candidate answer means the same as the reference answer and answers the question correctly.
            Reply NO if the candidate answer is wrong, incomplete, contradicts the reference, or does not answer the question.
            Reply with exactly one word: YES or NO.""";

    public record Verdict(double score, boolean parsed, String raw) {
    }

    private final Store store;
    private final MeterRegistry metrics;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Duration timeout;
    private final int maxAttempts;

    public Judge(Store store, MeterRegistry metrics, @Value("${evalops.request-timeout:60s}") Duration timeout,
                 @Value("${evalops.max-attempts:3}") int maxAttempts) {
        this.store = store;
        this.metrics = metrics;
        this.timeout = timeout;
        this.maxAttempts = maxAttempts;
    }

    /** The judge snapshot stored with a run: endpoint, model, decoding and rubric version. */
    public static ObjectNode snapshot(String url, String model) {
        ObjectNode s = Store.JSON.createObjectNode().put("url", url).put("rubric", RUBRIC_VERSION);
        if (model != null) s.put("model", model);
        s.putObject("params").put("temperature", 0).put("seed", 0).put("max_tokens", 3);
        return s;
    }

    public static String userMessage(String question, String reference, String candidate) {
        return "Question: " + question.strip() + "\nReference answer: " + (reference == null ? "" : reference.strip()) + "\nCandidate answer: "
                + candidate.strip() + "\nIs the candidate answer correct? Answer YES or NO.";
    }

    public static Verdict parse(String raw) {
        String w = raw.strip().replaceAll("^[^A-Za-z]+", "").toUpperCase(Locale.ROOT);
        if (w.startsWith("YES")) return new Verdict(1, true, raw);
        if (w.startsWith("NO")) return new Verdict(0, true, raw);
        return new Verdict(0, false, raw);
    }

    public Verdict judge(String snapshotJson, String question, String reference, String candidate, boolean useCache) {
        if (snapshotJson == null) throw new IllegalStateException("run has no judge configured");
        JsonNode snap;
        try {
            snap = Store.JSON.readTree(snapshotJson);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        String user = userMessage(question, reference, candidate);
        String key = Store.sha256("judge\n" + snapshotJson + "\n" + SYSTEM + "\n" + user);
        if (useCache) {
            var hit = store.cached(key);
            if (hit.isPresent()) return count(parse(hit.get()));
        }
        String error = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String raw = chat(snap, user);
                if (useCache) store.cache(key, raw);
                return count(parse(raw));
            } catch (RunService.Transient e) {
                error = e.getMessage();
                RunService.sleep(200L << (attempt - 1));
            } catch (Exception e) {
                error = e.toString();
                break;
            }
        }
        metrics.counter("evalops.judge", "outcome", "error").increment();
        return new Verdict(0, false, "judge error: " + error);
    }

    private Verdict count(Verdict v) {
        metrics.counter("evalops.judge", "outcome", v.parsed() ? (v.score() > 0 ? "yes" : "no") : "unparsed").increment();
        return v;
    }

    String chat(JsonNode snap, String user) throws Exception {
        ObjectNode body = ((ObjectNode) snap.path("params")).deepCopy();
        if (snap.hasNonNull("model")) body.put("model", snap.get("model").asText());
        ArrayNode msgs = body.putArray("messages");
        msgs.addObject().put("role", "system").put("content", SYSTEM);
        msgs.addObject().put("role", "user").put("content", user);
        body.put("stream", false);
        var req = HttpRequest.newBuilder(URI.create(snap.path("url").asText())).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
        HttpResponse<String> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new RunService.Transient("connection error: " + e.getMessage());
        }
        int sc = resp.statusCode();
        if (sc == 429 || sc >= 500) throw new RunService.Transient("HTTP " + sc);
        if (sc != 200) throw new IllegalStateException("HTTP " + sc + ": " + resp.body());
        JsonNode content = Store.JSON.readTree(resp.body()).path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) throw new IllegalStateException("response has no choices[0].message.content");
        return content.asText();
    }
}
