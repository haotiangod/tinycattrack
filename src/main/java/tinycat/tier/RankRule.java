package tinycat.tier;

import java.util.ArrayList;
import java.util.List;

/**
 * 排名型分档规则（火堆 / 商店 / 尼奥选取率）。
 *
 * 为什么不能用抓率那套固定阈值：这三个维度的量级和形态完全不同——
 * 火堆第一幕头名占比 24.8%，商店头名 10.6%，尼奥选项头名占全部选择约 10%，条目数 5~37 条不等。
 * 固定阈值要么全挤进 S，要么全掉进 F，所以改成「组内相对名次」分档。
 *
 * 三种规则都在这里实现，用 {@link #ACTIVE} 一个常量切换：
 * - QUARTILE（默认）：等人数四分位，前 25% 名次 = S、25-50% = A、50-75% = B、其余 = C。
 *   好处：任何条目数都能给出 4 个有效档，排版稳定，和「怪物危险度」T1-T4 同一套逻辑。
 *   代价：档内跨度可能很大（火堆第一幕 S 档从 24.8% 到 3.3%），所以档内仍按指标降序排、并标出占比。
 * - CUMULATIVE：累计占比，S = 累计覆盖前 50% 的条目，A = 到 75%，B = 到 90%，其余 = C。
 *   好处：不受头部极端值影响（火堆 S 只剩 2 张「双重释放/电击」）。
 *   坑：条目少或长尾长时会塌缩——火堆行动类型只有 5 项时全部进 S；商店长尾到不了 90%，永远没有 C。
 * - RELATIVE：相对最大值,≥最大值 50% = S、25% = A、10% = B、3% = C、其余 = D。
 *   好处：不同指标之间可比（占比 / 战损 / 次数都能套）。
 *   坑：极端头名会把长尾全压进 D（商店第一幕 9 项进 D），D 档信息量低。
 *
 * - VALUE_QUARTILE（当前默认）：直接对原始数值取 Q1/Q2/Q3 当切点，≥Q3 = S、Q2~Q3 = A、Q1~Q2 = B、其余 = C。
 *   并列条目必定同档（不拆），所以各档人数不等。坑：次数是正整数，Q1 经常正好等于最小值 1，
 *   这时没有任何条目低于 Q1，C 档为空（实测 32 张表里 17 张 C 空，个别表 B 也空）。
 * - HYBRID（4.3.1）：先看断层——≥ 头名 50% 的条目单独成 S，其余按人数三等分 A/B/C。
 *   为什么改用它：等人数会硬切 25%，火堆第一幕 S 档从 24.8% 一路铺到 2.5%（11 条，跨度 10 倍），
 *   把「睡觉 3.0%」和「双重释放 24.8%」放进同一档；而数据本身有明确断层（21.9% → 8.6%）。
 *   HYBRID 在有断层时 S 自然收缩到 1~3 条，在没有断层的表（商店）仍按人数均分，不会塌缩。
 *   代价：各档人数不再相等，S 档人数随数据形态浮动（实测火堆 1~6 条、商店 2~14 条）。
 *
 * 这些规则都是「数据驱动、无固定阈值」，改动只需换 ACTIVE，或在此新增一个枚举值。
 *
 * - JENKS（当前默认）：Fisher-Jenks 自然断点 k=5，另把 < 1%（含 0%）单独切成最低档 R0。档内平方和最优、同值必同档、每档非空，
 *   是唯一同时满足「档内差距小」和「不出现空档」的方案（实测 32 张表跨度中位 1.50、空档 0）。
 */
public enum RankRule {
    QUARTILE("等人数四分位", "Equal-count quartiles",
            "组内按指标降序排名：前 25% = S，25-50% = A，50-75% = B，其余 = C"),
    CUMULATIVE("累计占比", "Cumulative share",
            "S = 累计覆盖前 50% 的条目，A = 到 75%，B = 到 90%，其余 = C"),
    RELATIVE("相对最大值", "Relative to max",
            "≥ 最大值的 50% = S，≥25% = A，≥10% = B，≥3% = C，其余 = D"),
    HYBRID("头名断层 + 等人数", "Top-half cut then equal count",
            "≥ 头名 50% 的条目单独成 S（数据有断层时 S 自然只剩 1~3 条），其余条目按人数三等分为 A/B/C"),
    VALUE_QUARTILE("数值四分位", "Value quartiles",
            "直接按指标数值的 Q3 / Q2 / Q1 切档：≥Q3 = S，Q2~Q3 = A，Q1~Q2 = B，其余 = C。"
                    + "并列条目必定同档，所以各档人数不等；次数是正整数时 Q1 常等于最小值，C 档可能为空"),
    JENKS("Jenks 自然断点 k=5 + 低于 1% 独立档", "Jenks breaks (k=5) + below-1% tier",
            "用 Fisher-Jenks 动态规划把条目切成 5 段，使各档内部平方和之和最小（ArcGIS 默认的重尾分档法）。"
                    + "同值必定同档、每档至少 1 条，不会出现空档；指标只有 3~4 个不同取值时底部档留空");

    /** 当前生效的规则；换规则只改这一行。 */
    public static final RankRule ACTIVE = JENKS;

    /** JENKS 规则的档数（S/A/B/C/D 五档）。 */
    public static final int JENKS_K = 5;

    public final String labelZh;
    public final String labelEn;
    public final String noteZh;

    RankRule(String labelZh, String labelEn, String noteZh) {
        this.labelZh = labelZh;
        this.labelEn = labelEn;
        this.noteZh = noteZh;
    }

    /**
     * 给一组已按指标降序排好的数值分档。
     *
     * @param values 已降序排序的指标值（占比 / 选取率 / 次数）
     * @param rule   规则
     * @return 与 values 一一对应的档位
     */
    public static List<Tier> assign(List<Double> values, RankRule rule) {
        List<Tier> out = new ArrayList<Tier>(values == null ? 0 : values.size());
        if (values == null || values.isEmpty()) {
            return out;
        }
        if (rule == JENKS) {
            // < 1%（含 0，从未被选）单独成最低档：长尾表里这批条目很多，
            // 和 1%~7% 一起切会被自然断点并成一档，看不出「几乎不会被选」和「偶尔被选」的差别。
            List<Double> kept = new ArrayList<Double>();
            for (Double value : values) {
                double v = value == null ? 0.0D : value.doubleValue();
                if (v >= 1.0D) {
                    kept.add(value);
                }
            }
            List<Tier> inner = jenks(kept, JENKS_K);
            int idx = 0;
            for (Double value : values) {
                double v = value == null ? 0.0D : value.doubleValue();
                if (v < 1.0D) {
                    out.add(Tier.RZ);
                } else if (idx < inner.size()) {
                    out.add(inner.get(idx++));
                } else {
                    out.add(Tier.RZ);
                }
            }
            return out;
        }
        int n = values.size();
        double max = 0.0D;
        double sum = 0.0D;
        for (Double value : values) {
            double v = value == null ? 0.0D : value.doubleValue();
            if (v > max) {
                max = v;
            }
            sum += v;
        }
        // HYBRID 需要先知道 S 档有多少条（≥ 头名 50% 的全部算 S），剩下的才三等分
        int topCount = 0;
        if (rule == HYBRID && max > 0.0D) {
            double cut = max * 0.5D;
            for (Double value : values) {
                if (value != null && value.doubleValue() >= cut && value.doubleValue() > 0.0D) {
                    topCount++;
                }
            }
        }

        // VALUE_QUARTILE 直接对原始数值取 Q1/Q2/Q3（升序插值，等价 Excel QUARTILE.INC）
        double q1 = 0.0D;
        double q2 = 0.0D;
        double q3 = 0.0D;
        if (rule == VALUE_QUARTILE) {
            double[] asc = new double[n];
            for (int i = 0; i < n; i++) {
                Double value = values.get(i);
                asc[n - 1 - i] = value == null ? 0.0D : value.doubleValue();
            }
            q1 = quantile(asc, 0.25D);
            q2 = quantile(asc, 0.50D);
            q3 = quantile(asc, 0.75D);
        }

        double running = 0.0D;
        for (int i = 0; i < n; i++) {
            double v = values.get(i) == null ? 0.0D : values.get(i).doubleValue();
            Tier tier;
            if (rule == CUMULATIVE) {
                running += sum > 0.0D ? v / sum : 0.0D;
                tier = running <= 0.50D ? Tier.RS
                        : running <= 0.75D ? Tier.RA
                        : running <= 0.90D ? Tier.RB
                        : Tier.RC;
            } else if (rule == RELATIVE) {
                double ratio = max > 0.0D ? v / max : 0.0D;
                tier = ratio >= 0.50D ? Tier.RS
                        : ratio >= 0.25D ? Tier.RA
                        : ratio >= 0.10D ? Tier.RB
                        : ratio >= 0.03D ? Tier.RC
                        : Tier.RD;
            } else if (rule == HYBRID) {
                if (i < topCount) {
                    tier = Tier.RS;
                } else {
                    int rest = n - topCount;
                    double pos = rest > 0 ? ((i - topCount) + 0.5D) / rest : 0.0D;
                    tier = pos < 1.0D / 3.0D ? Tier.RA
                            : pos < 2.0D / 3.0D ? Tier.RB
                            : Tier.RC;
                }
            } else if (rule == VALUE_QUARTILE) {
                tier = v >= q3 ? Tier.RS
                        : v >= q2 ? Tier.RA
                        : v >= q1 ? Tier.RB
                        : Tier.RC;
            } else {
                double pos = (i + 0.5D) / n;
                tier = pos < 0.25D ? Tier.RS
                        : pos < 0.50D ? Tier.RA
                        : pos < 0.75D ? Tier.RB
                        : Tier.RC;
            }
            out.add(tier);
        }
        return out;
    }

    /** 排名型档位，下标 0 = 最高档。 */
    private static final Tier[] RANK_TIERS = {Tier.RS, Tier.RA, Tier.RB, Tier.RC, Tier.RD};

    /**
     * Fisher-Jenks 自然断点（最优分割）。
     *
     * 目标：把条目切成 k 段，使「各段内部离差平方和之和」最小。
     * 实现要点：
     * 1) **先按不同数值分组**，DP 在分组上做加权平方和，因此同值必定同档，绝不会把并列拆开
     *    （等人数分位正是犯了这个错）。
     * 2) 复杂度 O(k·m²)，m = 不同取值个数（一张表 30~90），导出瞬间完成。
     * 3) 不同取值少于 k 时只切 m 段，档位从 S 往下排，底部留空。
     *
     * 为什么要它：火堆/商店是重尾计数，档内跨度必须小、又不能出现空档。
     * 实测 32 张表：档内跨度中位 1.50（等人数 2.29）、空档 0 个（数值四分位 5 个）。
     */
    private static List<Tier> jenks(List<Double> values, int k) {
        int n = values.size();
        double[] asc = new double[n];
        for (int i = 0; i < n; i++) {
            Double v = values.get(i);
            asc[i] = v == null ? 0.0D : v.doubleValue();
        }
        java.util.Arrays.sort(asc);

        // 1) 按不同数值分组（升序）
        int m = 0;
        double[] uniq = new double[n];
        int[] weight = new int[n];
        for (int i = 0; i < n; i++) {
            if (m > 0 && Double.compare(uniq[m - 1], asc[i]) == 0) {
                weight[m - 1]++;
            } else {
                uniq[m] = asc[i];
                weight[m] = 1;
                m++;
            }
        }
        int groups = Math.min(k, m);

        // 2) 前缀加权和，用于 O(1) 求区间内离差平方和
        double[] sw = new double[m + 1];
        double[] s1 = new double[m + 1];
        double[] s2 = new double[m + 1];
        for (int i = 1; i <= m; i++) {
            double v = uniq[i - 1];
            sw[i] = sw[i - 1] + weight[i - 1];
            s1[i] = s1[i - 1] + v * weight[i - 1];
            s2[i] = s2[i - 1] + v * v * weight[i - 1];
        }

        // 3) DP：best[j][i] = 前 i 个取值分成 j 段的最小平方和
        double INF = Double.MAX_VALUE;
        double[][] best = new double[groups + 1][m + 1];
        int[][] back = new int[groups + 1][m + 1];
        for (int j = 0; j <= groups; j++) {
            for (int i = 0; i <= m; i++) {
                best[j][i] = INF;
            }
        }
        best[0][0] = 0.0D;
        for (int j = 1; j <= groups; j++) {
            for (int i = j; i <= m; i++) {
                for (int t = j - 1; t < i; t++) {
                    if (best[j - 1][t] >= INF) {
                        continue;
                    }
                    double cost = best[j - 1][t] + sse(sw, s1, s2, t + 1, i);
                    if (cost < best[j][i]) {
                        best[j][i] = cost;
                        back[j][i] = t;
                    }
                }
            }
        }

        // 4) 回溯出每段起点（1-based）
        int[] start = new int[groups + 1];
        int cursor = m;
        for (int j = groups; j >= 1; j--) {
            start[j] = back[j][cursor] + 1;
            cursor = back[j][cursor];
        }

        // 5) 按数值回查档位：段号越大 = 数值越高 = 档位越高
        List<Tier> out = new ArrayList<Tier>(n);
        for (int i = 0; i < n; i++) {
            Double v = values.get(i);
            double value = v == null ? 0.0D : v.doubleValue();
            int pos = 1;
            for (int t = 1; t <= m; t++) {
                if (Double.compare(uniq[t - 1], value) == 0) {
                    pos = t;
                    break;
                }
            }
            int seg = 1;
            for (int j = 1; j <= groups; j++) {
                if (start[j] <= pos) {
                    seg = j;
                }
            }
            int tierIndex = groups - seg;
            if (tierIndex < 0) {
                tierIndex = 0;
            }
            if (tierIndex > RANK_TIERS.length - 1) {
                tierIndex = RANK_TIERS.length - 1;
            }
            out.add(RANK_TIERS[tierIndex]);
        }
        return out;
    }

    /** 升序数组闭区间 [a, b]（1-based）的加权离差平方和。 */
    private static double sse(double[] sw, double[] s1, double[] s2, int a, int b) {
        double count = sw[b] - sw[a - 1];
        if (count <= 0.0D) {
            return 0.0D;
        }
        double sum = s1[b] - s1[a - 1];
        double square = s2[b] - s2[a - 1];
        return square - sum * sum / count;
    }

    /** 升序数组的 p 分位点（inclusive 插值，等价 Excel QUARTILE.INC）。 */
    private static double quantile(double[] asc, double p) {
        int n = asc.length;
        if (n == 0) {
            return 0.0D;
        }
        if (n == 1) {
            return asc[0];
        }
        double h = (n - 1) * p;
        int lo = (int) Math.floor(h);
        int hi = (int) Math.ceil(h);
        if (lo == hi) {
            return asc[lo];
        }
        return asc[lo] + (h - lo) * (asc[hi] - asc[lo]);
    }

    /** 导出说明行用：一行中文+英文，交代当前规则。 */
    public String describeZh() {
        return "分档规则：" + labelZh + "（" + noteZh + "）";
    }

    public String describeEn() {
        return "Tier rule: " + labelEn;
    }
}
