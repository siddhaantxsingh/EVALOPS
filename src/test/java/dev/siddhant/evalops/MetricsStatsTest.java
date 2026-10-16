package dev.siddhant.evalops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.siddhant.evalops.metrics.Metrics;
import dev.siddhant.evalops.stats.Stats;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MetricsStatsTest {
    @Test
    void metricsOnKnownCases() {
        var m = Metrics.ALL;
        assertThat(m.get("exact_match").score("  Paris\n", "Paris")).isEqualTo(1);
        assertThat(m.get("exact_match").score("paris", "Paris")).isEqualTo(0);
        assertThat(m.get("contains").score("The capital is Paris.", "Paris")).isEqualTo(1);
        assertThat(m.get("regex_match").score("answer: 42", "\\b4\\d\\b")).isEqualTo(1);
        assertThat(m.get("regex_match").score("x", "(")).isEqualTo(0); // invalid regex scores 0, no exception
        assertThat(m.get("first_line_exact").score("\nreturn x\n# more", "return x\nfoo")).isEqualTo(1);
        // kitten -> sitting: distance 3, max length 7
        assertThat(Metrics.editSimilarity("kitten", "sitting")).isCloseTo(1 - 3.0 / 7, within(1e-12));
        assertThat(Metrics.editSimilarity("", "")).isEqualTo(1);
        // LCS("the cat sat on the mat", "the cat on the mat") = 5 tokens: P = 5/6, R = 5/5
        assertThat(Metrics.rougeL("the cat sat on the mat", "the cat on the mat")).isCloseTo(2 * (5.0 / 6) * 1 / (5.0 / 6 + 1), within(1e-12));
        assertThat(Metrics.bleu("the cat sat on the mat", "the cat sat on the mat")).isCloseTo(1.0, within(1e-12));
        assertThat(Metrics.bleu("completely different words here", "the cat sat on the mat")).isEqualTo(0);
        double partial = Metrics.bleu("the cat sat on a mat", "the cat sat on the mat");
        assertThat(partial).isBetween(0.3, 0.9);
        assertThat(m.get("json_valid").score("{\"a\": [1, 2]}", "")).isEqualTo(1);
        assertThat(m.get("json_valid").score("{a: 1}", "")).isEqualTo(0);
    }

    @Test
    void bootstrapIntervalCoversTheTrueMean() {
        // Bernoulli(0.6), n = 200: a 95 % interval should contain 0.6 about 95 % of the time
        var rng = new Random(1);
        int covered = 0, trials = 300;
        for (int t = 0; t < trials; t++) {
            double[] x = new double[200];
            for (int i = 0; i < x.length; i++) x[i] = rng.nextDouble() < 0.6 ? 1 : 0;
            var ci = Stats.bootstrapMean(x, 1000, 0.95, t);
            if (ci.lo() <= 0.6 && 0.6 <= ci.hi()) covered++;
        }
        assertThat((double) covered / trials).isBetween(0.91, 0.98);
    }

    @Test
    void pairedTestHasTheRightFalsePositiveRateAndPower() {
        var rng = new Random(2);
        int falsePos = 0, detected = 0, trials = 300;
        // n = 300: per-item differences have sd ~0.63, so a +0.15 effect is
        // ~4.1 standard errors and the expected power is ~0.98 (at n = 150 it
        // is only ~0.82)
        for (int t = 0; t < trials; t++) {
            int n = 300;
            double[] base = new double[n], same = new double[n], better = new double[n];
            for (int i = 0; i < n; i++) {
                double difficulty = rng.nextDouble(); // shared per item: the pairing matters
                base[i] = rng.nextDouble() < 0.9 - 0.6 * difficulty ? 1 : 0;
                same[i] = rng.nextDouble() < 0.9 - 0.6 * difficulty ? 1 : 0;
                better[i] = rng.nextDouble() < Math.min(1, 1.05 - 0.6 * difficulty) ? 1 : 0;
            }
            if (Stats.paired(base, same, 999, 0.95, t).significant(0.05)) falsePos++;
            if (Stats.paired(base, better, 999, 0.95, t).significant(0.05)) detected++;
        }
        assertThat((double) falsePos / trials).as("false positive rate").isBetween(0.02, 0.09);
        assertThat((double) detected / trials).as("power for a +0.15 effect").isGreaterThan(0.9);
    }

    @Test
    void reportsAreReproducible() {
        double[] a = {1, 0, 1, 1, 0, 1, 0, 1}, b = {1, 1, 1, 1, 0, 1, 1, 1};
        assertThat(Stats.paired(a, b, 500, 0.95, 7)).isEqualTo(Stats.paired(a, b, 500, 0.95, 7));
    }
}
