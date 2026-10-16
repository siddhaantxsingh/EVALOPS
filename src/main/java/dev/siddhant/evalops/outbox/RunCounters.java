package dev.siddhant.evalops.outbox;

import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Event;
import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A read model fed by events: counts of runs queued, finished and failed.
 * Incrementing a counter is not idempotent by itself; deduplication by
 * event id makes it so. The integration tests use it to check that a
 * redelivered event has no second effect.
 */
@Component
public class RunCounters extends IdempotentConsumer {
    private final JdbcClient db;

    public RunCounters(Store store, TransactionTemplate tx, JdbcClient db) {
        super(store, tx);
        this.db = db;
    }

    @Override
    public String name() {
        return "run-counters";
    }

    @Override
    protected void apply(Event e) {
        String counter = switch (e.type()) {
            case "run.queued" -> "runs_queued";
            case "run.finished" -> e.payload().contains("\"status\":\"failed\"") ? "runs_failed" : "runs_done";
            default -> null;
        };
        if (counter == null) return;
        int n = db.sql("update run_counter set n = n + 1 where name = ?").param(counter).update();
        if (n == 0) db.sql("insert into run_counter(name, n) values (?, 1)").param(counter).update();
    }

    public Map<String, Long> counters() {
        return db.sql("select name, n from run_counter").query((r, i) -> Map.entry(r.getString(1), r.getLong(2))).list().stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
