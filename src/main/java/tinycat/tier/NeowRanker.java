package tinycat.tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tinycat.stats.ClassStat;
import tinycat.stats.Neow;
import tinycat.stats.Rate;

/**
 * 尼奥选项的两套排名与区间。
 *
 * <p><b>简单率</b> = 被选次数 ÷ 出现次数。分母是「这个选项自己出现了多少次」，
 * 每局 4 个选项里它出现就算一次。</p>
 *
 * <p><b>为什么还要标准化</b>：简单率把「对手有多强」也算进去了。
 * 一个选项如果老和 100 金、删 2 张这种王牌同场，它被选中的比例会被压低；
 * 反过来老和换 boss 遗物同场，比率会被抬高。两者都不是「偏好」本身。</p>
 *
 * <p><b>标准化</b>用 Plackett-Luce 模型：每局是一个选择集 S（1 选中 + 3 跳过），
 * {@code P(选中 i | S) = s_i / Σ_{j∈S} s_j}。和旧版「拆成 3 场两两对决」相比，
 * 整屏似然不会把一次决定当成 3 份证据，也不会让「总被提供、总在同屏输」的选项
 * 靠出场次数堆分。</p>
 *
 * <p><b>为什么用层级模型</b>：全胜选项（21/21、8/8）的似然对强度是单调的，
 * 没有有限的最大似然解——普通 PL 的点估计靠「+1 假想胜场」钉住，
 * 于是 8 次出场被抬高约 1/8、21 次只抬高约 1/21，名次变成样本量的函数；
 * 只把先验从 +1 改成 +0.5，头名就会从 8/8 换成 21/21。
 * 这里改用 {@link NeowStrength}：log 强度加 log-normal 先验，尺度用经验贝叶斯选，
 * 给后验均值、95% 可信区间和 P(第一)。</p>
 *
 * <p><b>并列</b>：全胜项之间数据分不出先后。{@link NeowStrength} 把「组首胜过它的
 * 把握不足 95%」的条目并成一组；组内按理应由样本量决定先后——出现次数多的更可信，
 * 所以同一组里按出现次数从多到少排。两条区间重叠时，名次差不能当成真实差异。</p>
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
        /** 后验均值占比（%）：强度占报告集合全部强度之和的比例。 */
        public final double standardized;
        public final double standardizedLow;
        public final double standardizedHigh;
        /** P(排名第一)，0~1，来自后验抽样。 */
        public final double rankOne;
        /** 并列组号（从 1 开始）；同组 = 统计上分不出先后。 */
        public final int tieGroup;
        /** 并列组在表里的起止名次（1-based）；不并列时起止相同。 */
        public int tieStart;
        public int tieEnd;

        Row(Neow neow, int offered, int picked, double simple, double simpleLow, double simpleHigh,
            double standardized, double standardizedLow, double standardizedHigh,
            double rankOne, int tieGroup) {
            this.neow = neow;
            this.offered = offered;
            this.picked = picked;
            this.simple = simple;
            this.simpleLow = simpleLow;
            this.simpleHigh = simpleHigh;
            this.standardized = standardized;
            this.standardizedLow = standardizedLow;
            this.standardizedHigh = standardizedHigh;
            this.rankOne = rankOne;
            this.tieGroup = tieGroup;
        }

        /** 是否和别的条目并列。 */
        public boolean tied() {
            return this.tieEnd > this.tieStart;
        }
    }

    /**
     * @param minOffered 只统计出现次数不少于该值的选项
     * @return 按并列组、组内按出现次数降序
     */
    public static List<Row> rank(ClassStat cs, int minOffered) {
        if (cs == null) {
            return new ArrayList<Row>();
        }
        return rank(cs.neowPickRate, cs.neowChoices, minOffered);
    }

    /** 自检与离线核对用：直接喂逐局计数与选择块，不依赖 ClassStat。 */
    static List<Row> rank(List<Rate<Neow>> pickRate, List<Neow[]> choices, int minOffered) {
        List<Row> result = new ArrayList<Row>();
        if (choices == null || choices.isEmpty()) {
            return result;
        }

        // 出现/被选必须来自逐局计数：两两矩阵里一次选中会被记成 3 条，反推出来的分母是 3p+s
        Map<Neow, int[]> counts = new LinkedHashMap<Neow, int[]>();
        if (pickRate != null) {
            for (Rate<Neow> rate : pickRate) {
                if (rate == null || rate.what == null) {
                    continue;
                }
                counts.put(rate.what, new int[]{rate.win, rate.total()});
            }
        }

        // 全选项池：被跳过的选项也是对手，哪怕它从没被选过、或没进最终表
        Set<Neow> universeSet = new LinkedHashSet<Neow>();
        for (Neow[] block : choices) {
            if (block == null) {
                continue;
            }
            for (Neow neow : block) {
                if (neow != null) {
                    universeSet.add(neow);
                }
            }
        }
        if (universeSet.size() < 2) {
            return result;
        }
        List<Neow> universe = new ArrayList<Neow>(universeSet);
        Map<Neow, Integer> index = new LinkedHashMap<Neow, Integer>(universe.size() * 2);
        for (int i = 0; i < universe.size(); i++) {
            index.put(universe.get(i), Integer.valueOf(i));
        }
        List<int[]> groups = PlackettLuce.blocks(choices, index);
        if (groups.size() < 2) {
            return result;
        }

        int[] report = reportOf(universe, counts, minOffered);
        if (report.length < 2) {
            return result;
        }

        NeowStrength.Result fit = NeowStrength.fit(groups, universe.size(), report);
        for (int id : report) {
            Neow neow = universe.get(id);
            int[] count = counts.get(neow);
            int p = count == null ? 0 : count[0];
            int n = count == null ? 0 : count[1];
            double simple = n > 0 ? 100.0D * p / n : 0.0D;
            double[] ci = wilson(p, n);
            result.add(new Row(neow, n, p, simple, ci[0] * 100.0D, ci[1] * 100.0D,
                    fit.mean[id], fit.low[id], fit.high[id], fit.rankOne[id], fit.tieGroup[id]));
        }
        Collections.sort(result, new Comparator<Row>() {
            @Override
            public int compare(Row a, Row b) {
                int byTie = Integer.compare(a.tieGroup, b.tieGroup);
                if (byTie != 0) {
                    return byTie;
                }
                int byOffered = Integer.compare(b.offered, a.offered);
                if (byOffered != 0) {
                    return byOffered;
                }
                int byShare = Double.compare(b.standardized, a.standardized);
                if (byShare != 0) {
                    return byShare;
                }
                return a.neow.toString().compareTo(b.neow.toString());
            }
        });
        markTieRanges(result);
        return result;
    }

    /** 把每行的并列起止名次写回去（表格里显示「并列 1–6」）。 */
    private static void markTieRanges(List<Row> rows) {
        int i = 0;
        while (i < rows.size()) {
            int j = i;
            while (j + 1 < rows.size() && rows.get(j + 1).tieGroup == rows.get(i).tieGroup) {
                j++;
            }
            for (int t = i; t <= j; t++) {
                rows.get(t).tieStart = i + 1;
                rows.get(t).tieEnd = j + 1;
            }
            i = j + 1;
        }
    }

    private static int[] reportOf(List<Neow> universe, Map<Neow, int[]> counts, int minOffered) {
        int[] buffer = new int[universe.size()];
        int len = 0;
        for (int i = 0; i < universe.size(); i++) {
            int[] count = counts.get(universe.get(i));
            int offered = count == null ? 0 : count[1];
            if (offered >= minOffered && offered > 0) {
                buffer[len++] = i;
            }
        }
        return Arrays.copyOf(buffer, len);
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
