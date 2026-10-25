package tinycat.tier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import tinycat.stats.ClassStat;
import tinycat.stats.Neow;

/**
 * 尼奥选项的两套排名与置信区间。
 *
 * <p><b>简单率</b> = 被选次数 ÷ 出现次数。分母是「这个选项自己出现了多少次」，
 * 每局 4 个选项里它出现就算一次。</p>
 *
 * <p><b>为什么还要标准化</b>：简单率把「对手有多强」也算进去了。
 * 一个选项如果老和 100 金、删 2 张这种王牌同场，它被选中的比例会被压低；
 * 反过来老和换 boss 遗物同场，比率会被抬高。两者都不是「偏好」本身。</p>
 *
 * <p><b>标准化</b>用 Bradley-Terry 模型：把每局「1 选中 + 3 跳过」拆成 3 场两两对决，
 * 用全部对决估计每个选项的强度 s_i，满足 P(i 胜过 j) = s_i / (s_i + s_j)。
 * 强度归一化后就是「控制对手强度之后」的相对受欢迎程度。</p>
 *
 * <p><b>置信区间</b>：简单率用 Wilson 区间（精确、小样本友好）；
 * 标准化率用配对矩阵的 bootstrap（100 次重采样）。两条区间重叠时，
 * 名次差不能当成真实差异。</p>
 */
public final class NeowRanker {

    private NeowRanker() {
    }

    /** 一个选项的两种排名结果。 */
    public static final class Row {
        public final Neow neow;
        public final int offered;
        public final int picked;
        /** 简单率（%）。 */
        public final double simple;
        public final double simpleLow;
        public final double simpleHigh;
        /** Bradley-Terry 强度（几何均值归一）。 */
        public final double strength;
        /** 标准化率（%）：强度占全部之和的比例。 */
        public final double standardized;
        public final double standardizedLow;
        public final double standardizedHigh;

        Row(Neow neow, int offered, int picked, double simple, double simpleLow, double simpleHigh,
            double strength, double standardized, double standardizedLow, double standardizedHigh) {
            this.neow = neow;
            this.offered = offered;
            this.picked = picked;
            this.simple = simple;
            this.simpleLow = simpleLow;
            this.simpleHigh = simpleHigh;
            this.strength = strength;
            this.standardized = standardized;
            this.standardizedLow = standardizedLow;
            this.standardizedHigh = standardizedHigh;
        }
    }

    /**
     * @param minOffered 只统计出现次数不少于该值的选项
     * @return 按标准化率降序
     */
    public static List<Row> rank(ClassStat cs, int minOffered) {
        List<Row> result = new ArrayList<Row>();
        if (cs == null || cs.neowBeats == null || cs.neowBeats.isEmpty()) {
            return result;
        }

        // 收集出现过的选项，并统计出现/被选次数（对角线来自矩阵的行和与列和）
        Map<Neow, Integer> picked = new LinkedHashMap<Neow, Integer>();
        Map<Neow, Integer> offered = new LinkedHashMap<Neow, Integer>();
        for (Map.Entry<Neow, Map<Neow, Integer>> row : cs.neowBeats.entrySet()) {
            for (Map.Entry<Neow, Integer> cell : row.getValue().entrySet()) {
                int n = cell.getValue() == null ? 0 : cell.getValue();
                Neow winner = row.getKey();
                Neow loser = cell.getKey();
                if (winner.equals(loser) || n <= 0) {
                    continue;
                }
                add(picked, winner, n);
                add(offered, winner, n);
                add(offered, loser, n);
            }
        }
        List<Neow> items = new ArrayList<Neow>();
        for (Neow neow : offered.keySet()) {
            if (offered.get(neow) >= minOffered) {
                items.add(neow);
            }
        }
        if (items.size() < 2) {
            return result;
        }

        double[] strength = fit(cs.neowBeats, items, 1.0D);
        double[] low = new double[items.size()];
        double[] high = new double[items.size()];
        bootstrap(cs, items, strength, low, high);

        double sum = 0.0D;
        for (double value : strength) {
            sum += value;
        }
        for (int i = 0; i < items.size(); i++) {
            Neow neow = items.get(i);
            int p = picked.containsKey(neow) ? picked.get(neow) : 0;
            int n = offered.get(neow);
            double simple = n > 0 ? 100.0D * p / n : 0.0D;
            double[] ci = wilson(p, n);
            result.add(new Row(neow, n, p, simple, ci[0] * 100.0D, ci[1] * 100.0D,
                    strength[i], sum > 0 ? 100.0D * strength[i] / sum : 0.0D,
                    low[i], high[i]));
        }
        Collections.sort(result, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                return Double.compare(b.standardized, a.standardized);
            }
        });
        return result;
    }

    private static void add(Map<Neow, Integer> map, Neow key, int delta) {
        Integer old = map.get(key);
        map.put(key, old == null ? delta : old + delta);
    }

    /** MM 迭代求 Bradley-Terry 强度，返回按 items 顺序的强度数组（几何均值 = 1）。 */
    private static double[] fit(Map<Neow, Map<Neow, Integer>> beats, List<Neow> items, double prior) {
        int n = items.size();
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = 1.0D;
        }
        for (int iter = 0; iter < 200; iter++) {
            double[] next = new double[n];
            for (int i = 0; i < n; i++) {
                Neow a = items.get(i);
                Map<Neow, Integer> rowA = beats.get(a);
                int wins = 0;
                double denom = 0.0D;
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    Neow b = items.get(j);
                    int ab = count(rowA, b);
                    int ba = count(beats.get(b), a);
                    int total = ab + ba;
                    if (total <= 0) {
                        continue;
                    }
                    wins += ab;
                    denom += total / (s[i] + s[j]);
                }
                next[i] = denom > 0.0D ? (wins + prior) / (denom + prior) : 1.0e-9D;
            }
            // 几何均值归一，避免整体漂移
            double logSum = 0.0D;
            for (double v : next) {
                logSum += Math.log(Math.max(v, 1.0e-12D));
            }
            double geo = Math.exp(logSum / n);
            for (int i = 0; i < n; i++) {
                s[i] = Math.max(next[i] / geo, 1.0e-12D);
            }
        }
        return s;
    }

    private static int count(Map<Neow, Integer> row, Neow key) {
        if (row == null) {
            return 0;
        }
        Integer value = row.get(key);
        return value == null ? 0 : value;
    }

    /** 对配对矩阵做 100 次 bootstrap，给出标准化率（%）的 95% 区间。 */
    private static void bootstrap(ClassStat cs, List<Neow> items, double[] point,
                                  double[] low, double[] high) {
        int n = items.size();
        int samples = 100;
        Random random = new Random(20261025L);
        List<double[]> draws = new ArrayList<double[]>(samples);
        for (int b = 0; b < samples; b++) {
            Map<Neow, Map<Neow, Integer>> shadow = new LinkedHashMap<Neow, Map<Neow, Integer>>();
            for (int i = 0; i < n; i++) {
                Neow a = items.get(i);
                Map<Neow, Integer> rowA = cs.neowBeats.get(a);
                for (int j = 0; j < n; j++) {
                    if (i == j) {
                        continue;
                    }
                    Neow other = items.get(j);
                    int ab = count(rowA, other);
                    int ba = count(cs.neowBeats.get(other), a);
                    int total = ab + ba;
                    if (total <= 0) {
                        continue;
                    }
                    int reAb = resample(ab, total, random);
                    if (reAb > 0) {
                        Map<Neow, Integer> row = shadow.get(a);
                        if (row == null) {
                            row = new LinkedHashMap<Neow, Integer>();
                            shadow.put(a, row);
                        }
                        Integer old = row.get(other);
                        row.put(other, old == null ? reAb : old + reAb);
                    }
                }
            }
            double[] s = fit(shadow, items, 1.0D);
            double sum = 0.0D;
            for (double v : s) {
                sum += v;
            }
            double[] pct = new double[n];
            for (int i = 0; i < n; i++) {
                pct[i] = sum > 0 ? 100.0D * s[i] / sum : 0.0D;
            }
            draws.add(pct);
        }
        for (int i = 0; i < n; i++) {
            double[] values = new double[draws.size()];
            for (int d = 0; d < draws.size(); d++) {
                values[d] = draws.get(d)[i];
            }
            java.util.Arrays.sort(values);
            low[i] = values[(int) Math.floor(0.025D * (values.length - 1))];
            high[i] = values[(int) Math.ceil(0.975D * (values.length - 1))];
        }
    }

    /** 从 total 次试验、succ 次成功里重采样一次（正态近似，够用且快）。 */
    private static int resample(int succ, int total, Random random) {
        if (total <= 0) {
            return 0;
        }
        double p = (double) succ / total;
        double sd = Math.sqrt(Math.max(p * (1.0D - p), 1.0e-9D) * total);
        double value = succ + random.nextGaussian() * sd;
        return (int) Math.max(0, Math.min(total, Math.round(value)));
    }

    /** Wilson score 区间，返回 [low, high]（0~1）。 */
    public static double[] wilson(int success, int total) {
        if (total <= 0) {
            return new double[]{0.0D, 1.0D};
        }
        double z = 1.959964D;
        double p = (double) success / total;
        double denom = 1.0D + z * z / total;
        double center = (p + z * z / (2.0D * total)) / denom;
        double margin = z * Math.sqrt(p * (1.0D - p) / total + z * z / (4.0D * total * total)) / denom;
        return new double[]{Math.max(0.0D, center - margin), Math.min(1.0D, center + margin)};
    }
}
