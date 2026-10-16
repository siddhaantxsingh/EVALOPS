package dev.siddhant.evalops.runner;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.siddhant.evalops.stats.Stats;
import dev.siddhant.evalops.store.Store;
import dev.siddhant.evalops.store.Store.Result;
import dev.siddhant.evalops.store.Store.Run;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Run summaries, paired comparisons and CI gates. */
@Component
public class Reports {
    private final Store store;
    private final int resamples;

    public Reports(Store store, @Value("${evalops.bootstrap-resamples:2000}") int resamples) {
        this.store = store;
        this.resamples = resamples;
    }

    public static class BadRequest extends RuntimeException {
        public BadRequest(String m) {
            super(m);
        }
    }

    public ObjectNode summary(Run run) {
        List<Result> res = store.results(run.id());
        int total = store.dataset(run.datasetId()).orElseThrow().items();
        ObjectNode o = Store.JSON.createObjectNode().put("run", run.id()).put("status", run.status()).put("items", total)
                .put("completed", res.size()).put("errors", res.stream().filter(r -> r.error() != null).count())
                .put("cached", res.stream().filter(Result::cached).count());
        if (run.error() != null) o.put("error", run.error());
        double[] lat = res.stream().filter(r -> r.latencyMs() != null && r.error() == null).mapToDouble(Result::latencyMs).toArray();
        o.putObject("latency_ms").put("p50", Stats.percentile(lat, 0.5)).put("p95", Stats.percentile(lat, 0.95));
        ObjectNode ms = o.putObject("metrics");
        for (String m : run.metrics()) {
            double[] x = res.stream().mapToDouble(r -> r.scores().getOrDefault(m, 0.0)).toArray();
            var ci = Stats.bootstrapMean(x, resamples, 0.95, run.id());
            ms.putObject(m).put("mean", ci.mean()).put("ci95_lo", ci.lo()).put("ci95_hi", ci.hi());
        }
        return o;
    }

    record Pair(double[] base, double[] cand, List<Integer> idx) {
    }

    private Pair align(Run base, Run cand, String metric) {
        Map<Integer, Result> b = store.results(base.id()).stream().collect(Collectors.toMap(Result::idx, r -> r));
        Map<Integer, Result> c = store.results(cand.id()).stream().collect(Collectors.toMap(Result::idx, r -> r));
        List<Integer> idx = b.keySet().stream().filter(c::containsKey).sorted().toList();
        return new Pair(idx.stream().mapToDouble(i -> b.get(i).scores().getOrDefault(metric, 0.0)).toArray(),
                idx.stream().mapToDouble(i -> c.get(i).scores().getOrDefault(metric, 0.0)).toArray(), idx);
    }

    private void checkComparable(Run base, Run cand) {
        if (base.datasetId() != cand.datasetId()) throw new BadRequest("runs use different dataset versions; a paired comparison needs the same items");
        if (!base.status().equals("done") || !cand.status().equals("done")) throw new BadRequest("both runs must be done");
    }

    public ObjectNode compare(Run base, Run cand, int maxRegressions) {
        checkComparable(base, cand);
        ObjectNode o = Store.JSON.createObjectNode().put("baseline", base.id()).put("candidate", cand.id());
        ObjectNode ms = o.putObject("metrics");
        List<String> common = base.metrics().stream().filter(cand.metrics()::contains).toList();
        for (String m : common) {
            Pair p = align(base, cand, m);
            var r = Stats.paired(p.base(), p.cand(), resamples, 0.95, base.id() * 31 + cand.id());
            ms.putObject(m).put("baseline", r.baseline()).put("candidate", r.candidate()).put("delta", r.delta()).put("ci95_lo", r.lo())
                    .put("ci95_hi", r.hi()).put("p_value", r.pValue()).put("n", r.n());
        }
        // items that got worse on the first metric, worst first
        if (!common.isEmpty()) {
            String m = common.get(0);
            Pair p = align(base, cand, m);
            Map<Integer, Result> b = store.results(base.id()).stream().collect(Collectors.toMap(Result::idx, x -> x));
            Map<Integer, Result> c = store.results(cand.id()).stream().collect(Collectors.toMap(Result::idx, x -> x));
            var items = store.items(base.datasetId()).stream().collect(Collectors.toMap(Store.Item::idx, x -> x));
            ArrayNode reg = o.putArray("regressions").addObject().put("metric", m).putArray("items");
            java.util.stream.IntStream.range(0, p.idx().size()).boxed().filter(i -> p.cand()[i] < p.base()[i])
                    .sorted((x, y) -> Double.compare(p.cand()[x] - p.base()[x], p.cand()[y] - p.base()[y])).limit(maxRegressions).forEach(i -> {
                        int id = p.idx().get(i);
                        reg.addObject().put("idx", id).put("input", items.get(id).input()).put("reference", items.get(id).reference())
                                .put("baseline_output", b.get(id).output()).put("candidate_output", c.get(id).output())
                                .put("baseline_score", p.base()[i]).put("candidate_score", p.cand()[i]);
                    });
        }
        return o;
    }

    /**
     * A gate rule; each condition applies only when given. maxDrop: fail when
     * the metric drops by more than maxDrop and the drop is significant
     * (p < alpha, default 0.05). minValue: fail when the candidate's mean is
     * below it.
     */
    public record Rule(String metric, Double maxDrop, Double alpha, Double minValue) {
    }

    public ObjectNode gate(Run base, Run cand, List<Rule> rules) {
        checkComparable(base, cand);
        ObjectNode o = Store.JSON.createObjectNode();
        ArrayNode checks = o.putArray("checks");
        boolean pass = true;
        for (Rule rule : rules) {
            if (!base.metrics().contains(rule.metric()) || !cand.metrics().contains(rule.metric())) throw new BadRequest("metric " + rule.metric() + " not in both runs");
            Pair p = align(base, cand, rule.metric());
            var r = Stats.paired(p.base(), p.cand(), resamples, 0.95, base.id() * 31 + cand.id());
            double alpha = rule.alpha() == null ? 0.05 : rule.alpha();
            if (rule.maxDrop() == null && rule.minValue() == null) throw new BadRequest("rule for " + rule.metric() + " needs maxDrop and/or minValue");
            double maxDrop = rule.maxDrop() == null ? 0.0 : rule.maxDrop();
            boolean regressed = rule.maxDrop() != null && r.delta() < -maxDrop && r.pValue() < alpha;
            boolean below = rule.minValue() != null && r.candidate() < rule.minValue();
            boolean ok = !regressed && !below;
            pass &= ok;
            String why = regressed ? String.format("dropped %.4f (p = %.4f) beyond the allowed %.4f", -r.delta(), r.pValue(), maxDrop)
                    : below ? String.format("%.4f is below the minimum %.4f", r.candidate(), rule.minValue()) : "ok";
            checks.addObject().put("metric", rule.metric()).put("pass", ok).put("baseline", r.baseline()).put("candidate", r.candidate())
                    .put("delta", r.delta()).put("p_value", r.pValue()).put("reason", why);
        }
        o.put("pass", pass);
        return o;
    }
}
