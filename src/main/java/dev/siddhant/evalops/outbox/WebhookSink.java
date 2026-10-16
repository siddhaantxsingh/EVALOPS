package dev.siddhant.evalops.outbox;

import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Event;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes every event to the configured webhook URLs
 * ({@code evalops.outbox.webhooks}). The body is the event envelope; the
 * {@code X-EvalOps-Event-Id} header carries the event id, which receivers
 * use to deduplicate, because delivery is at least once. A non-2xx answer
 * fails the delivery, and the relay retries it.
 */
@Component
public class WebhookSink implements EventConsumer {
    private final List<String> urls;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public WebhookSink(@Value("${evalops.outbox.webhooks:}") List<String> urls) {
        this.urls = urls.stream().filter(u -> !u.isBlank()).toList();
    }

    @Override
    public String name() {
        return "webhooks";
    }

    @Override
    public void accept(Event e) throws Exception {
        if (urls.isEmpty()) return;
        var env = Store.JSON.createObjectNode().put("id", e.eventId()).put("type", e.type()).put("aggregate_id", e.aggregateId());
        env.set("payload", Store.JSON.readTree(e.payload()));
        for (String u : urls) {
            var req = HttpRequest.newBuilder(URI.create(u)).timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .header("X-EvalOps-Event-Id", e.eventId()).header("X-EvalOps-Event-Type", e.type())
                    .POST(HttpRequest.BodyPublishers.ofString(env.toString())).build();
            int sc = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (sc / 100 != 2) throw new IllegalStateException("webhook " + u + " answered HTTP " + sc);
        }
    }
}
