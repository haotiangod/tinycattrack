package tinycat.tier;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plackett-Luce（条件 logit）强度模型与整屏 bootstrap。
 *
 * <p>每一次奖励屏就是一个选择集 S，模型假设
 * {@code P(选中 i | S) = s_i / Σ_{j∈S} s_j}。这和「把 3 选 1 拆成 3 场两两对决」的
 * Bradley-Terry 不是一回事：拆成对决后，一次「3 张牌 + 跳过」会被记成 3 份独立证据，
 * 于是「常被提供、但每次都被同屏更强的牌压住」的卡会靠出场次数堆出很高的胜场；
 * 而 PL 用整屏似然，赢下一屏能说明多少强度，取决于同屏还有谁。</p>
 *
 * <p><b>为什么先验很弱</b>：旧实现给每个条目先记 1 次胜利 + 1 次出场，等于把
 * 「2 负 / 277 胜」和「60 负 / 726 胜」往中间拉平——实测把快检（97.87%）压在冷头（92.4%）后面。
 * 按对局分组做 5 折交叉验证（留出屏上的对数损失，越小越好）：
 * 卡牌 b=1 → 0.6179，b=0.01 → 0.5845；尼奥 b=1 → 0.6052，b=0.01 → 0.5129。
 * 两边的 top-1 命中率也一起变好（卡牌 0.746 → 0.752，尼奥 0.771 → 0.798）。
 * 所以这里只留 1 次假想胜利和 0.01 次假想出场，让真实交手记录说话，
 * 同时给「从未输过」的条目一个有限的强度，不至于除零。</p>
 *
 * <p><b>区间</b>：按屏整组重采样（1000 次，固定种子）。一次选择是一个决定，
 * 里面的几条「谁赢谁」不能当独立样本；把整屏一起抽，才是这个数据的抽样单位。
 * 注意：全胜条目（尼奥 21/21）的区间仍然偏窄——重采样造不出「它输过」的世界，
 * 这是观测数据本身的限制，读区间时不要把下界当成「最差情况」。</p>
 */
final class PlackettLuce {

    /** 每个条目先记 1 次假想获胜。 */
    static final double PRIOR_WINS = 1.0D;
    /** 每个条目先记 0.01 次假想出场；取 0.01 的依据见类注释里的交叉验证。 */
    static final double PRIOR_SURVIVAL = 0.01D;
    /** 点估计的迭代上限：收敛慢的条目（接近全胜）在 200 次后仍在动，给足余量。 */
    static final int POINT_MAX_ITER = 2000;
    /** 点估计的收敛阈值：强度相对变化 1e-9，保证各家工具算出的数值一致。 */
    static final double POINT_TOL = 1.0e-9D;
    /** 重采样的迭代上限与阈值：分位只需 1e-4 的精度（份额误差 < 0.001pp）。 */
    static final int DRAW_MAX_ITER = 800;
    static final double DRAW_TOL = 1.0e-5D;
    /** 整屏重采样次数：2.5% 分位取第 25 小的样本，端点足够稳。 */
    static final int CLUSTER_BOOTSTRAP = 1000;
    /** 固定种子：同一天、同一份数据两次导出必须一模一样。 */
    static final long SEED = 20261025L;

    private PlackettLuce() {
    }

    /**
     * 把「选中在前」的选择块转成下标，块内去重、丢掉不在 index 里的选项。
     *
     * 少于 2 个选项的屏不产生任何信息，直接丢弃。
     */
    static <K> List<int[]> blocks(List<K[]> choices, Map<K, Integer> index) {
        List<int[]> out = new ArrayList<int[]>(choices == null ? 0 : choices.size());
        if (choices == null) {
            return out;
        }
        for (K[] choice : choices) {
            if (choice == null || choice.length < 2) {
                continue;
            }
            int[] block = new int[choice.length];
            int len = 0;
            for (K item : choice) {
                Integer id = item == null ? null : index.get(item);
                if (id == null) {
                    continue;
                }
                boolean duplicate = false;
                for (int t = 0; t < len; t++) {
                    if (block[t] == id.intValue()) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    block[len++] = id.intValue();
                }
            }
            if (len >= 2) {
                out.add(len == block.length ? block : Arrays.copyOf(block, len));
            }
        }
        return out;
    }

    /** 拟合强度（几何均值归一）；init 可为 null。 */
    static double[] fit(List<int[]> blocks, int n, double[] init) {
        int[] weights = new int[blocks.size()];
        Arrays.fill(weights, 1);
        return fit(blocks, weights, n, init, POINT_TOL, POINT_MAX_ITER);
    }

    /**
     * 加权 MM 迭代。weights[t] 是第 t 屏在本次样本里出现的次数（bootstrap 用）。
     *
     * 更新式：s_i ← (胜场_i + a) / (Σ_{屏∋i} w_t / Σ_{j∈S_t} s_j + b)。
     */
    private static double[] fit(List<int[]> blocks, int[] weights, int n, double[] init,
                                double tol, int maxIter) {
        double[] s = new double[n];
        for (int i = 0; i < n; i++) {
            s[i] = init != null && init[i] > 1.0e-12D ? init[i] : 1.0D;
        }
        double[] wins = new double[n];
        boolean[] seen = new boolean[n];
        for (int t = 0; t < blocks.size(); t++) {
            int weight = weights[t];
            if (weight <= 0) {
                continue;
            }
            int[] block = blocks.get(t);
            wins[block[0]] += weight;
            for (int id : block) {
                seen[id] = true;
            }
        }

        for (int iter = 0; iter < maxIter; iter++) {
            double[] denom = new double[n];
            for (int t = 0; t < blocks.size(); t++) {
                int weight = weights[t];
                if (weight <= 0) {
                    continue;
                }
                int[] block = blocks.get(t);
                double z = 0.0D;
                for (int id : block) {
                    z += s[id];
                }
                if (z <= 0.0D) {
                    continue;
                }
                double share = weight / z;
                for (int id : block) {
                    denom[id] += share;
                }
            }

            double[] next = new double[n];
            double logSum = 0.0D;
            int active = 0;
            for (int i = 0; i < n; i++) {
                if (!seen[i]) {
                    next[i] = 0.0D;
                    continue;
                }
                double value = (wins[i] + PRIOR_WINS) / (denom[i] + PRIOR_SURVIVAL);
                if (value < 1.0e-12D) {
                    value = 1.0e-12D;
                }
                next[i] = value;
                logSum += Math.log(value);
                active++;
            }
            if (active == 0) {
                return next;
            }
            double geo = Math.exp(logSum / active);

            double maxRel = 0.0D;
            for (int i = 0; i < n; i++) {
                if (!seen[i]) {
                    continue;
                }
                double value = Math.max(next[i] / geo, 1.0e-12D);
                maxRel = Math.max(maxRel, Math.abs(value / s[i] - 1.0D));
                s[i] = value;
            }
            // 分位对强度的第 4 位小数不敏感，重采样不必和点估计一个精度
            if (maxRel < tol) {
                break;
            }
        }
        return s;
    }

    /**
     * 整屏重采样 B 次，返回每次的强度向量（已按几何均值归一）。
     *
     * 调用方按自己报告的条目集合把强度换算成占比，再取分位——
     * 这样「被最小样本过滤掉的条目」仍然是对手，但不会出现在表里。
     */
    static double[][] bootstrap(List<int[]> blocks, int n, double[] point) {
        int m = blocks.size();
        double[][] draws = new double[CLUSTER_BOOTSTRAP][];
        if (m == 0) {
            for (int b = 0; b < CLUSTER_BOOTSTRAP; b++) {
                draws[b] = point.clone();
            }
            return draws;
        }
        int[] weights = new int[m];
        Random random = new Random(SEED);
        for (int b = 0; b < CLUSTER_BOOTSTRAP; b++) {
            Arrays.fill(weights, 0);
            for (int t = 0; t < m; t++) {
                weights[random.nextInt(m)]++;
            }
            draws[b] = fit(blocks, weights, n, point, DRAW_TOL, DRAW_MAX_ITER);
        }
        return draws;
    }

    /**
     * 把强度抽样换算成「相对 report 这些条目的占比（%）」的 95% 分位区间。
     *
     * low/high 按条目下标写回；未进 report 的位置保持 0。
     */
    static void shareInterval(double[][] draws, int[] report, double[] low, double[] high) {
        int k = report.length;
        if (k == 0) {
            return;
        }
        int b = draws.length;
        double[][] values = new double[k][b];
        for (int d = 0; d < b; d++) {
            double[] s = draws[d];
            double total = 0.0D;
            for (int id : report) {
                total += s[id];
            }
            for (int i = 0; i < k; i++) {
                values[i][d] = total > 0.0D ? 100.0D * s[report[i]] / total : 0.0D;
            }
        }
        int lo = (int) Math.floor(0.025D * (b - 1));
        int hi = (int) Math.ceil(0.975D * (b - 1));
        for (int i = 0; i < k; i++) {
            Arrays.sort(values[i]);
            low[report[i]] = values[i][lo];
            high[report[i]] = values[i][hi];
        }
    }

    /** 强度 → 占比（%，合计 100）。 */
    static double share(double strength, double total) {
        return total > 0.0D ? 100.0D * strength / total : 0.0D;
    }

    /** 报告集合的强度之和。 */
    static double sum(double[] strength, int[] report) {
        double total = 0.0D;
        for (int id : report) {
            total += strength[id];
        }
        return total;
    }
}
