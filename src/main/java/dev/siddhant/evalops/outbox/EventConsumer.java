package dev.siddhant.evalops.outbox;

import dev.siddhant.evalops.store.Store.Event;

/**
 * Receives outbox events from {@link OutboxRelay}. Delivery is at least
 * once: the same event (same {@code eventId}) can arrive again after a crash
 * or a failure of another consumer. Implementations must be idempotent by
 * event id; {@link IdempotentConsumer} does that for database effects.
 */
public interface EventConsumer {
    /** Stable name, used as the key of the consumer's processed-event records. */
    String name();

    /** Apply the event. Throwing leaves the event unpublished, and it is retried. */
    void accept(Event e) throws Exception;
}
