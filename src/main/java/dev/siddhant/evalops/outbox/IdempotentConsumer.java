package dev.siddhant.evalops.outbox;

import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Event;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A consumer whose effect is a database change. The processed-event record
 * and the effect commit in one transaction, so a redelivered event is
 * skipped and an effect is never applied twice or half.
 */
public abstract class IdempotentConsumer implements EventConsumer {
    protected final Store store;
    private final TransactionTemplate tx;

    protected IdempotentConsumer(Store store, TransactionTemplate tx) {
        this.store = store;
        this.tx = tx;
    }

    @Override
    public final void accept(Event e) {
        tx.executeWithoutResult(s -> {
            if (store.markProcessed(name(), e.eventId())) apply(e);
        });
    }

    /** The effect; runs inside the transaction that records the event as processed. */
    protected abstract void apply(Event e);
}
