package dev.siddhant.evalops.outbox;

import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Event;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls the outbox and delivers unpublished events, oldest first, to every
 * {@link EventConsumer}. An event is marked published only after all
 * consumers accepted it, so a crash at any point before that leads to
 * redelivery, never to loss (at least once). A consumer that has already
 * recorded the event is skipped. If an event fails, later events of the
 * same aggregate (run) wait, so one run's events stay in order; other runs
 * continue.
 */
@Component
public class OutboxRelay {
    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private final Store store;
    private final List<EventConsumer> consumers;
    private final boolean enabled;
    /**
     * Fault injection for tests: runs after a consumer accepted an event and
     * before that is recorded. Throwing here is a crash at the worst moment.
     */
    private volatile BiConsumer<String, Event> afterDelivery = (c, e) -> {
    };

    public OutboxRelay(Store store, List<EventConsumer> consumers, @Value("${evalops.outbox.relay-enabled:true}") boolean enabled) {
        this.store = store;
        this.consumers = consumers;
        this.enabled = enabled;
    }

    public void setAfterDelivery(BiConsumer<String, Event> hook) {
        this.afterDelivery = hook == null ? (c, e) -> {
        } : hook;
    }

    @Scheduled(fixedDelayString = "${evalops.outbox.poll-ms:200}")
    public void poll() {
        if (enabled) drain();
    }

    /** Deliver what is pending; returns the number of events published. */
    public synchronized int drain() {
        int published = 0;
        Set<Long> blocked = new HashSet<>();
        List<Event> batch = store.unpublished(200);
        for (Event e : batch) {
            if (blocked.contains(e.aggregateId())) continue;
            try {
                for (EventConsumer c : consumers) {
                    if (store.processed(c.name(), e.eventId())) continue;
                    c.accept(e);
                    afterDelivery.accept(c.name(), e);
                    if (!(c instanceof IdempotentConsumer)) store.markProcessed(c.name(), e.eventId());
                }
                store.markPublished(e.seq());
                published++;
            } catch (Exception ex) {
                blocked.add(e.aggregateId());
                log.warn("outbox event {} ({}) not delivered: {}", e.seq(), e.type(), ex.toString());
                try {
                    store.markFailed(e.seq(), ex.toString());
                } catch (Exception ignored) {
                    // the database may be the problem; the event stays unpublished either way
                }
            }
        }
        return published;
    }
}
