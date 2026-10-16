package dev.siddhant.evalops.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Scoring functions: (output, reference) -> score in [0, 1].
 * Text is normalised (trim, collapse whitespace) where noted.
 */
public final class Metrics {
    private Metrics() {
    }

    public interface Metric {
        double score(String output, String reference);
    }

    public static final Map<String, Metric> ALL = Map.of(
            "exact_match", (o, r) -> norm(o).equals(norm(r)) ? 1 : 0,
            "contains", (o, r) -> norm(o).contains(norm(r)) ? 1 : 0,
            "regex_match", Metrics::regexMatch,
            "first_line_exact", (o, r) -> firstLine(o).equals(firstLine(r)) ? 1 : 0,
            "edit_similarity", Metrics::editSimilarity,
            "rouge_l_f1", Metrics::rougeL,
            "bleu", Metrics::bleu,
            "json_valid", (o, r) -> jsonValid(o) ? 1 : 0);

    public static Set<String> names() {
        return ALL.keySet();
    }

    public static String norm(String s) {
        return s == null ? "" : s.strip().replaceAll("\\s+", " ");
    }

    static String firstLine(String s) {
        if (s == null) return "";
        String t = s.replaceFirst("^\\n+", "");
        int i = t.indexOf('\n');
        return (i < 0 ? t : t.substring(0, i)).stripTrailing();
    }

    static double regexMatch(String o, String r) {
        try {
            return Pattern.compile(r, Pattern.DOTALL).matcher(o == null ? "" : o).find() ? 1 : 0;
        } catch (PatternSyntaxException e) {
            return 0;
        }
    }

    /** 1 - Levenshtein(o, r) / max(|o|, |r|) on characters (1 for two empty strings). */
    public static double editSimilarity(String o, String r) {
        o = o == null ? "" : o;
        r = r == null ? "" : r;
        int n = o.length(), m = r.length();
        if (n == 0 && m == 0) return 1;
        int[] prev = new int[m + 1], cur = new int[m + 1];
        for (int j = 0; j <= m; j++) prev[j] = j;
        for (int i = 1; i <= n; i++) {
            cur[0] = i;
            for (int j = 1; j <= m; j++) {
                int c = o.charAt(i - 1) == r.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + c);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return 1.0 - (double) prev[m] / Math.max(n, m);
    }

    static List<String> tokens(String s) {
        String t = norm(s).toLowerCase();
        return t.isEmpty() ? List.of() : Arrays.asList(t.split(" "));
    }

    /** ROUGE-L F1 over whitespace tokens (longest common subsequence). */
    public static double rougeL(String o, String r) {
        List<String> a = tokens(o), b = tokens(r);
        if (a.isEmpty() || b.isEmpty()) return a.isEmpty() && b.isEmpty() ? 1 : 0;
        int[][] dp = new int[a.size() + 1][b.size() + 1];
        for (int i = 1; i <= a.size(); i++)
            for (int j = 1; j <= b.size(); j++)
                dp[i][j] = a.get(i - 1).equals(b.get(j - 1)) ? dp[i - 1][j - 1] + 1 : Math.max(dp[i - 1][j], dp[i][j - 1]);
        double lcs = dp[a.size()][b.size()];
        if (lcs == 0) return 0;
        double p = lcs / a.size(), rc = lcs / b.size();
        return 2 * p * rc / (p + rc);
    }

    /**
     * Sentence BLEU-4 with add-one smoothing on the 2..4-gram precisions
     * (Lin & Och 2004, "smoothing 2") and the standard brevity penalty.
     */
    public static double bleu(String o, String r) {
        List<String> c = tokens(o), ref = tokens(r);
        if (c.isEmpty() || ref.isEmpty()) return 0;
        double logSum = 0;
        for (int n = 1; n <= 4; n++) {
            Map<List<String>, Integer> rc = ngrams(ref, n), cc = ngrams(c, n);
            int match = 0, total = Math.max(0, c.size() - n + 1);
            for (var e : cc.entrySet()) match += Math.min(e.getValue(), rc.getOrDefault(e.getKey(), 0));
            double p = n == 1 ? (total == 0 ? 0 : (double) match / total) : (match + 1.0) / (total + 1.0);
            if (p == 0) return 0;
            logSum += Math.log(p) / 4;
        }
        double bp = c.size() >= ref.size() ? 1 : Math.exp(1 - (double) ref.size() / c.size());
        return bp * Math.exp(logSum);
    }

    static Map<List<String>, Integer> ngrams(List<String> t, int n) {
        Map<List<String>, Integer> m = new HashMap<>();
        for (int i = 0; i + n <= t.size(); i++) m.merge(new ArrayList<>(t.subList(i, i + n)), 1, Integer::sum);
        return m;
    }

    static final ObjectMapper JSON = new ObjectMapper();

    static boolean jsonValid(String o) {
        try {
            JSON.readTree(o == null ? "" : o.strip());
            return o != null && !o.isBlank();
        } catch (Exception e) {
            return false;
        }
    }
}
