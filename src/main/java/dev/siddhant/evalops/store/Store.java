package dev.siddhant.evalops.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** All database access. */
@Repository
public class Store {
    public static final ObjectMapper JSON = new ObjectMapper().configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
    private final JdbcClient db;

    public Store(JdbcClient db) {
        this.db = db;
    }

    public record Item(int idx, String input, String reference, String meta) {
    }

    public record Dataset(long id, String name, int version, String sha256, int items, Instant created) {
    }

    public record Target(long id, String name, String url, String model, String params) {
    }

    public record Run(long id, long datasetId, long targetId, String targetSnapshot, List<String> metrics, int concurrency, boolean useCache,
                      String status, Instant created, Instant finished, String error, String executor, String judgeSnapshot, String github) {
        public boolean worker() {
            return "worker".equals(executor);
        }
    }

    /** Optional parts of a new run. idemKey/requestSha may be null. */
    public record NewRun(long datasetId, long targetId, String snapshot, List<String> metrics, int concurrency, boolean useCache, String executor,
                         String judgeSnapshot, String github, String idemKey, String requestSha) {
    }

    public record Event(long seq, String eventId, String type, long aggregateId, String payload, int attempts) {
    }

    public record Task(long runId, int idx, String state, long token, String owner, Instant leaseExpires, int leases) {
    }

    public record Result(int idx, String output, Double latencyMs, String error, Map<String, Double> scores, boolean cached, int attempts) {
    }

    public static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static String canonical(Object o) {
        try {
            return JSON.writeValueAsString(o instanceof JsonNode n ? JSON.treeToValue(n, Object.class) : o);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    // ------------------------------------------------------------ datasets

    /**
     * Store a dataset. Content identical to the latest version of the same
     * name returns that version; anything else becomes version n + 1.
     * Versions are immutable.
     */
    @Transactional
    public Dataset addDataset(String name, List<Item> items) {
        String sha = sha256(canonical(items));
        Optional<Dataset> latest = latest(name);
        if (latest.isPresent() && latest.get().sha256().equals(sha)) return latest.get();
        int version = latest.map(d -> d.version() + 1).orElse(1);
        var kh = new GeneratedKeyHolder();
        db.sql("insert into dataset(name, version, sha256, items, created) values (?, ?, ?, ?, ?)")
                .params(name, version, sha, items.size(), Timestamp.from(Instant.now())).update(kh, "id");
        long id = kh.getKey().longValue();
        for (Item it : items) {
            db.sql("insert into dataset_item(dataset_id, idx, input, reference, meta) values (?, ?, ?, ?, ?)")
                    .params(id, it.idx(), it.input(), it.reference(), it.meta()).update();
        }
        return dataset(id).orElseThrow();
    }

    private static Dataset ds(java.sql.ResultSet r) throws java.sql.SQLException {
        return new Dataset(r.getLong("id"), r.getString("name"), r.getInt("version"), r.getString("sha256"), r.getInt("items"),
                r.getTimestamp("created").toInstant());
    }

    public Optional<Dataset> latest(String name) {
        return db.sql("select * from dataset where name = ? order by version desc limit 1").param(name).query((r, i) -> ds(r)).optional();
    }

    public Optional<Dataset> dataset(long id) {
        return db.sql("select * from dataset where id = ?").param(id).query((r, i) -> ds(r)).optional();
    }

    public Optional<Dataset> dataset(String name, int version) {
        return db.sql("select * from dataset where name = ? and version = ?").params(name, version).query((r, i) -> ds(r)).optional();
    }

    public List<Dataset> datasets() {
        return db.sql("select * from dataset order by name, version").query((r, i) -> ds(r)).list();
    }

    public List<Item> items(long datasetId) {
        return db.sql("select * from dataset_item where dataset_id = ? order by idx").param(datasetId)
                .query((r, i) -> new Item(r.getInt("idx"), r.getString("input"), r.getString("reference"), r.getString("meta"))).list();
    }

    // ------------------------------------------------------------ targets

    public Target putTarget(String name, String url, String model, String params) {
        int n = db.sql("update target set url = ?, model = ?, params = ? where name = ?").params(url, model, params, name).update();
        if (n == 0) {
            db.sql("insert into target(name, url, model, params, created) values (?, ?, ?, ?, ?)")
                    .params(name, url, model, params, Timestamp.from(Instant.now())).update();
        }
        return target(name).orElseThrow();
    }

    public Optional<Target> target(String name) {
        return db.sql("select * from target where name = ?").param(name)
                .query((r, i) -> new Target(r.getLong("id"), r.getString("name"), r.getString("url"), r.getString("model"), r.getString("params")))
                .optional();
    }

    public List<Target> targets() {
        return db.sql("select * from target order by name")
                .query((r, i) -> new Target(r.getLong("id"), r.getString("name"), r.getString("url"), r.getString("model"), r.getString("params"))).list();
    }

    // ------------------------------------------------------------ runs

    public long createRun(long datasetId, long targetId, String snapshot, List<String> metrics, int concurrency, boolean useCache) {
        return createRun(new NewRun(datasetId, targetId, snapshot, metrics, concurrency, useCache, "local", null, null, null, null));
    }

    /**
     * Create a run, its worker tasks, its idempotency key and its
     * {@code run.queued} event in one transaction. A duplicate idempotency
     * key raises {@link org.springframework.dao.DuplicateKeyException} and
     * nothing is written.
     */
    @Transactional
    public long createRun(NewRun n) {
        var kh = new GeneratedKeyHolder();
        db.sql("insert into run(dataset_id, target_id, target_snapshot, metrics, concurrency, use_cache, status, created, executor, judge_snapshot, github) "
                        + "values (?, ?, ?, ?, ?, ?, 'queued', ?, ?, ?, ?)")
                .params(n.datasetId(), n.targetId(), n.snapshot(), String.join(",", n.metrics()), n.concurrency(), n.useCache(),
                        Timestamp.from(Instant.now()), n.executor(), n.judgeSnapshot(), n.github()).update(kh, "id");
        long id = kh.getKey().longValue();
        if (n.idemKey() != null) {
            db.sql("insert into idempotency_key(idem_key, request_sha256, run_id, created) values (?, ?, ?, ?)")
                    .params(n.idemKey(), n.requestSha(), id, Timestamp.from(Instant.now())).update();
        }
        if ("worker".equals(n.executor())) {
            db.sql("insert into task(run_id, idx, state) select ?, idx, 'queued' from dataset_item where dataset_id = ?").params(id, n.datasetId()).update();
        }
        var p = JSON.createObjectNode().put("run", id).put("dataset_id", n.datasetId()).put("executor", n.executor());
        if (n.github() != null) p.set("github", readTree(n.github()));
        appendEvent("run.queued", id, p.toString());
        return id;
    }

    public record IdemKey(String requestSha, long runId) {
    }

    public Optional<IdemKey> idempotencyKey(String key) {
        return db.sql("select request_sha256, run_id from idempotency_key where idem_key = ?").param(key)
                .query((r, i) -> new IdemKey(r.getString(1), r.getLong(2))).optional();
    }

    public static com.fasterxml.jackson.databind.JsonNode readTreePublic(String s) {
        return readTree(s);
    }

    static com.fasterxml.jackson.databind.JsonNode readTree(String s) {
        try {
            return JSON.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Run run(java.sql.ResultSet r) throws java.sql.SQLException {
        Timestamp f = r.getTimestamp("finished");
        return new Run(r.getLong("id"), r.getLong("dataset_id"), r.getLong("target_id"), r.getString("target_snapshot"),
                List.of(r.getString("metrics").split(",")), r.getInt("concurrency"), r.getBoolean("use_cache"), r.getString("status"),
                r.getTimestamp("created").toInstant(), f == null ? null : f.toInstant(), r.getString("error"), r.getString("executor"),
                r.getString("judge_snapshot"), r.getString("github"));
    }

    public Optional<Run> run(long id) {
        return db.sql("select * from run where id = ?").param(id).query((r, i) -> run(r)).optional();
    }

    public List<Run> runs() {
        return db.sql("select * from run order by id").query((r, i) -> run(r)).list();
    }

    public List<Run> runsWithStatus(String status) {
        return db.sql("select * from run where status = ? order by id").param(status).query((r, i) -> run(r)).list();
    }

    /** Change a run's status and append the matching event in the same transaction. */
    @Transactional
    public void setStatus(long id, String status, String error) {
        boolean end = status.equals("done") || status.equals("failed");
        db.sql("update run set status = ?, error = ?, finished = ? where id = ?")
                .params(status, error, end ? Timestamp.from(Instant.now()) : null, id).update();
        var p = JSON.createObjectNode().put("run", id).put("status", status);
        if (error != null) p.put("error", error);
        if (end) {
            Run run = run(id).orElseThrow();
            List<Result> res = results(id);
            p.put("completed", res.size()).put("errors", res.stream().filter(x -> x.error() != null).count());
            var means = p.putObject("means");
            for (String m : run.metrics()) means.put(m, res.stream().mapToDouble(x -> x.scores().getOrDefault(m, 0.0)).average().orElse(0));
            if (run.github() != null) p.set("github", readTree(run.github()));
        }
        appendEvent(end ? "run.finished" : "run." + status, id, p.toString());
    }

    // ------------------------------------------------------------ outbox

    /** Append an event. Callers must be inside the transaction that makes the state change. */
    public String appendEvent(String type, long aggregateId, String payload) {
        String eventId = java.util.UUID.randomUUID().toString();
        db.sql("insert into outbox(event_id, type, aggregate_id, payload, created) values (?, ?, ?, ?, ?)")
                .params(eventId, type, aggregateId, payload, Timestamp.from(Instant.now())).update();
        return eventId;
    }

    public List<Event> unpublished(int limit) {
        return db.sql("select * from outbox where published is null order by seq limit ?").param(limit)
                .query((r, i) -> new Event(r.getLong("seq"), r.getString("event_id"), r.getString("type"), r.getLong("aggregate_id"),
                        r.getString("payload"), r.getInt("attempts"))).list();
    }

    public List<Event> events(long aggregateId) {
        return db.sql("select * from outbox where aggregate_id = ? order by seq").param(aggregateId)
                .query((r, i) -> new Event(r.getLong("seq"), r.getString("event_id"), r.getString("type"), r.getLong("aggregate_id"),
                        r.getString("payload"), r.getInt("attempts"))).list();
    }

    public void markPublished(long seq) {
        db.sql("update outbox set published = ?, attempts = attempts + 1, last_error = null where seq = ?").params(Timestamp.from(Instant.now()), seq).update();
    }

    public void markFailed(long seq, String error) {
        db.sql("update outbox set attempts = attempts + 1, last_error = ? where seq = ?").params(error, seq).update();
    }

    public long unpublishedCount() {
        return db.sql("select count(*) from outbox where published is null").query(Long.class).single();
    }

    /** Record that a consumer applied an event; false if it already had. Call inside the consumer's transaction. */
    public boolean markProcessed(String consumer, String eventId) {
        try {
            db.sql("insert into processed_event(consumer, event_id, processed) values (?, ?, ?)").params(consumer, eventId, Timestamp.from(Instant.now()))
                    .update();
            return true;
        } catch (org.springframework.dao.DuplicateKeyException e) {
            return false;
        }
    }

    public boolean processed(String consumer, String eventId) {
        return db.sql("select count(*) from processed_event where consumer = ? and event_id = ?").params(consumer, eventId).query(Long.class).single() > 0;
    }

    // ------------------------------------------------------------ worker tasks

    private static Task task(java.sql.ResultSet r) throws java.sql.SQLException {
        Timestamp e = r.getTimestamp("lease_expires");
        return new Task(r.getLong("run_id"), r.getInt("idx"), r.getString("state"), r.getLong("token"), r.getString("owner"),
                e == null ? null : e.toInstant(), r.getInt("leases"));
    }

    public Optional<Task> task(long runId, int idx) {
        return db.sql("select * from task where run_id = ? and idx = ?").params(runId, idx).query((r, i) -> task(r)).optional();
    }

    /**
     * Lease one task that is queued, or whose lease has expired. The
     * conditional update makes the lease atomic: of two workers racing for
     * the same task, one updates a row and the other tries the next one.
     * Every lease increments the task's fencing token.
     */
    public Optional<Task> lease(String owner, java.time.Duration leaseFor) {
        for (int attempt = 0; attempt < 20; attempt++) {
            Timestamp now = Timestamp.from(Instant.now());
            List<Task> cands = db.sql("select t.* from task t join run r on r.id = t.run_id where r.status in ('queued', 'running') and "
                    + "(t.state = 'queued' or (t.state = 'leased' and t.lease_expires < ?)) order by t.run_id, t.idx limit 8").param(now)
                    .query((r, i) -> task(r)).list();
            if (cands.isEmpty()) return Optional.empty();
            for (Task c : cands) {
                int n = db.sql("update task set state = 'leased', token = token + 1, owner = ?, lease_expires = ?, leases = leases + 1 "
                                + "where run_id = ? and idx = ? and token = ? and (state = 'queued' or (state = 'leased' and lease_expires < ?))")
                        .params(owner, Timestamp.from(Instant.now().plus(leaseFor)), c.runId(), c.idx(), c.token(), now).update();
                if (n == 1) return task(c.runId(), c.idx());
            }
        }
        return Optional.empty();
    }

    /** Extend a lease; false if the token is no longer current. */
    public boolean heartbeat(long runId, int idx, long token, java.time.Duration leaseFor) {
        return db.sql("update task set lease_expires = ? where run_id = ? and idx = ? and token = ? and state = 'leased'")
                .params(Timestamp.from(Instant.now().plus(leaseFor)), runId, idx, token).update() == 1;
    }

    /**
     * Fenced completion: the result is stored only if {@code token} is still
     * the task's current token. A worker whose lease expired and was taken by
     * another worker holds an older token and is rejected. Returns false on
     * rejection; on success the result and the task state commit together.
     */
    @Transactional
    public boolean completeTask(long runId, int idx, long token, Result r) {
        int n = db.sql("update task set state = 'done', lease_expires = null where run_id = ? and idx = ? and token = ? and state = 'leased'")
                .params(runId, idx, token).update();
        if (n != 1) return false;
        putResult(runId, r);
        return true;
    }

    public long openTasks(long runId) {
        return db.sql("select count(*) from task where run_id = ? and state <> 'done'").param(runId).query(Long.class).single();
    }

    /** Finish a worker run if all its tasks are done; only one caller wins the status change. */
    @Transactional
    public boolean finishIfComplete(long runId) {
        if (openTasks(runId) > 0) return false;
        int n = db.sql("update run set status = 'finishing' where id = ? and status in ('queued', 'running')").param(runId).update();
        if (n != 1) return false;
        List<Result> res = results(runId);
        long errors = res.stream().filter(x -> x.error() != null).count();
        if (!res.isEmpty() && errors == res.size()) setStatus(runId, "failed", "every item failed; first error: " + res.get(0).error());
        else setStatus(runId, "done", null);
        return true;
    }

    // ------------------------------------------------------------ GitHub checks

    public Optional<Long> checkRunId(long runId) {
        return db.sql("select check_run_id from github_check where run_id = ?").param(runId).query(Long.class).optional();
    }

    public void putCheckRunId(long runId, long checkRunId) {
        db.sql("merge into github_check(run_id, check_run_id) key(run_id) values (?, ?)").params(runId, checkRunId).update();
    }

    public void deleteCheckRunId(long runId) {
        db.sql("delete from github_check where run_id = ?").param(runId).update();
    }

    public void putResult(long runId, Result r) {
        db.sql("merge into result(run_id, idx, output, latency_ms, error, scores, cached, attempts) key(run_id, idx) values (?, ?, ?, ?, ?, ?, ?, ?)")
                .params(runId, r.idx(), r.output(), r.latencyMs(), r.error(), canonical(r.scores()), r.cached(), r.attempts()).update();
    }

    @SuppressWarnings("unchecked")
    public List<Result> results(long runId) {
        return db.sql("select * from result where run_id = ? order by idx").param(runId).query((r, i) -> {
            Map<String, Double> scores;
            try {
                scores = JSON.readValue(r.getString("scores"), Map.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            double lat = r.getDouble("latency_ms");
            return new Result(r.getInt("idx"), r.getString("output"), r.wasNull() ? null : lat, r.getString("error"), scores, r.getBoolean("cached"),
                    r.getInt("attempts"));
        }).list();
    }

    // ------------------------------------------------------------ response cache

    public Optional<String> cached(String key) {
        return db.sql("select output from response_cache where cache_key = ?").param(key).query(String.class).optional();
    }

    public void cache(String key, String output) {
        db.sql("merge into response_cache(cache_key, output, created) key(cache_key) values (?, ?, ?)")
                .params(key, output, Timestamp.from(Instant.now())).update();
    }
}
