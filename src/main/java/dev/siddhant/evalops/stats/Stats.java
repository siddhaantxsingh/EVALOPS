package dev.siddhant.evalops.stats;

import java.util.Arrays;
import java.util.SplittableRandom;

/**
 * Bootstrap statistics for metric means and paired run comparisons. All
 * resampling uses a seeded generator, so a report is reproducible.
 */
public final class Stats {
    private Stats() {
    }

    public record Interval(double mean, double lo, double hi) {
    }

    /** Paired comparison of candidate vs baseline on the same items. */
    public record Paired(double baseline, double candidate, double delta, double lo, double hi, double pValue, int n) {
        public boolean significant(double alpha) {
            return pValue < alpha;
        }
    }

    public static double mean(double[] x) {
        return x.length == 0 ? Double.NaN : Arrays.stream(x).sum() / x.length;
    }

    /** Percentile bootstrap confidence interval of the mean. */
    public static Interval bootstrapMean(double[] x, int resamples, double level, long seed) {
        if (x.length == 0) return new Interval(Double.NaN, Double.NaN, Double.NaN);
        double[] means = new double[resamples];
        var rng = new SplittableRandom(seed);
        for (int b = 0; b < resamples; b++) {
            double s = 0;
            for (int i = 0; i < x.length; i++) s += x[rng.nextInt(x.length)];
            means[b] = s / x.length;
        }
        Arrays.sort(means);
        double a = (1 - level) / 2;
        return new Interval(mean(x), quantile(means, a), quantile(means, 1 - a));
    }

    /**
     * Paired test of mean(cand - base): bootstrap interval of the mean
     * difference, and a two-sided p-value from a sign-flip permutation test
     * (exact under the null that each item's difference is symmetric about 0).
     */
    public static Paired paired(double[] base, double[] cand, int resamples, double level, long seed) {
        if (base.length != cand.length) throw new IllegalArgumentException("runs must cover the same items");
        int n = base.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) d[i] = cand[i] - base[i];
        Interval ci = bootstrapMean(d, resamples, level, seed);
        double obs = Math.abs(mean(d));
        var rng = new SplittableRandom(seed ^ 0x5DEECE66DL);
        int extreme = 0;
        for (int b = 0; b < resamples; b++) {
            double s = 0;
            for (double v : d) s += rng.nextBoolean() ? v : -v;
            if (Math.abs(s / n) >= obs - 1e-12) extreme++;
        }
        double p = n == 0 || obs == 0 ? 1.0 : (extreme + 1.0) / (resamples + 1.0);
        return new Paired(mean(base), mean(cand), mean(d), ci.lo(), ci.hi(), p, n);
    }

    static double quantile(double[] sorted, double q) {
        double pos = q * (sorted.length - 1);
        int i = (int) Math.floor(pos);
        int j = Math.min(sorted.length - 1, i + 1);
        return sorted[i] + (pos - i) * (sorted[j] - sorted[i]);
    }

    public static double percentile(double[] x, double q) {
        if (x.length == 0) return Double.NaN;
        double[] s = x.clone();
        Arrays.sort(s);
        return quantile(s, q);
    }
}
