package tinycat.tier;

import java.util.List;
import java.util.Random;

/**
 * 尼奥选项的层级 Plackett-Luce：给 log 强度加 log-normal 先验，用 Laplace 近似取后验。
 *
 * <p><b>为什么不用普通 PL 的点估计</b>：一个选项从没输过时，它的似然对其强度是单调的，
 * 没有有限的最大似然解（separation）。普通 PL 靠「+1 假想胜场」把解钉住，于是
 * 8 次出场被抬高约 1/8、21 次只抬高约 1/21——名次变成样本量的函数，而不是战绩的函数。
 * 实测这份 406 局数据：先验从 a=1 改成 a=0.5，头名就从 8/8 换成 21/21。</p>
 *
 * <p><b>改成什么</b>：θ_i = log s_i，先验 θ ~ N(0, σ²)，σ = 3。σ 不能靠边际似然选：
 * 全胜项把似然推到边界，边际似然会一路偏向「先验越弱越好」（实测到网格上界 6.0 仍在升），
 * 得到的是分离本身，不是信息。这里改用与项目选 PL 先验同一套办法——按局 5 折交叉验证，
 * 留出整屏算预测概率：σ=1 / 2 / 3 / 4 / 6 的 geo-mean 是 0.5516 / 0.5908 / **0.5968** / 0.5964 / 0.5922，
 * top-1 是 79.3% / 80.8% / 80.3% / 80.5% / 80.5%；σ=3 的 geo-mean 最好。</p>
 *
 * <p><b>后验怎么用</b>：H = -∇²f 在 MAP 处的 Cholesky 因子 L 满足 Cov = (L Lᵀ)⁻¹，
 * 所以 θ_draw = θ̂ + L⁻ᵀ z（z ~ N(0,I)）就是后验抽样，不用显式求逆。
 * 抽样换算成占比后给后验均值、95% 区间和 {@code P(排名第一)}。</p>
 *
 * <p><b>并列</b>：全胜项之间只差先验带来的强度，样本本身分不出大小。
 * 这里用 {@code P(组首 > 该项) < 0.95} 判定「分不出来」，并成一组；
 * 组内谁先谁后交给样本量（出现次数多的更可信），由调用方排序。</p>
 *
 * <p>本类只服务尼奥。卡牌几千次出现、几乎没有全胜项，继续用 {@link PlackettLuce}，
 * 两者的数值不可互相比较。</p>
 */
final class NeowStrength {

    /** 后验抽样的固定种子：同一份数据两次导出必须一模一样。 */
    static final long SEED = 20261025L;
    /** 后验抽样次数：区间端点与 P(第一) 的分辨率约 1/8000。 */
    static final int DRAWS = 8000;
    /** 组首优于某项的后验概率低于此值，判为并列。 */
    static final double TIE_PROB = 0.95D;

    /**
     * 先验尺度：log 强度 ~ N(0, σ²)。按局 5 折交叉验证选（见类注释），不是每份数据现调。
     */
    static final double SIGMA = 3.0D;
    private static final double TINY = 1.0e-12D;

    private NeowStrength() {
    }

    /** 后验结果；下标与调用方的选项下标一致，非报告项全为 0。 */
    static final class Result {
        final double[] mean;
        final double[] low;
        final double[] high;
        final double[] rankOne;
        /** 并列组号，按后验均值从高到低从 1 开始；0 = 不在报告集合里。 */
        final int[] tieGroup;

        Result(double[] mean, double[] low, double[] high,
               double[] rankOne, int[] tieGroup) {
            this.mean = mean;
            this.low = low;
            this.high = high;
            this.rankOne = rankOne;
            this.tieGroup = tieGroup;
        }
    }

    /**
     * @param blocks 每屏一个选择集，block[0] = 被选中项，其余为同屏对手
     * @param n      选项总数
     * @param report 要报告的选项下标（占比在这批选项上归一）
     */
    static Result fit(List<int[]> blocks, int n, int[] report) {
        double[] wins = new double[n];
        for (int[] block : blocks) {
            wins[block[0]] += 1.0D;
        }

        Solution best = solve(blocks, n, wins, SIGMA);

        double[] mean = new double[n];
        double[] low = new double[n];
        double[] high = new double[n];
        double[] rankOne = new double[n];
        int[] tieGroup = new int[n];
        if (best == null || report.length == 0) {
            return new Result(mean, low, high, rankOne, tieGroup);
        }

        int k = report.length;
        double[][] share = new double[k][DRAWS];
        Random random = new Random(SEED);
        double[] z = new double[n];
        double[] temp = new double[k];
        for (int d = 0; d < DRAWS; d++) {
            for (int i = 0; i < n; i++) {
                z[i] = random.nextGaussian();
            }
            // Lᵀ y = z → θ_draw = θ̂ + y，Cov(y) = (L Lᵀ)⁻¹
            double[] y = solveUpperTranspose(best.chol, z);
            double max = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < k; j++) {
                double value = best.theta[report[j]] + y[report[j]];
                temp[j] = value;
                if (value > max) {
                    max = value;
                }
            }
            double sum = 0.0D;
            for (int j = 0; j < k; j++) {
                double weight = Math.exp(temp[j] - max);
                temp[j] = weight;
                sum += weight;
            }
            int top = 0;
            for (int j = 0; j < k; j++) {
                share[j][d] = 100.0D * temp[j] / sum;
                if (temp[j] > temp[top]) {
                    top = j;
                }
            }
            rankOne[report[top]] += 1.0D;
        }

        double[] values = new double[DRAWS];
        for (int j = 0; j < k; j++) {
            double sum = 0.0D;
            for (int d = 0; d < DRAWS; d++) {
                sum += share[j][d];
                values[d] = share[j][d];
            }
            int id = report[j];
            mean[id] = sum / DRAWS;
            rankOne[id] /= DRAWS;
            java.util.Arrays.sort(values);
            low[id] = values[quantileIndex(0.025D)];
            high[id] = values[quantileIndex(0.975D)];
        }

        assignTieGroups(share, report, mean, tieGroup);
        return new Result(mean, low, high, rankOne, tieGroup);
    }

    /**
     * 按后验均值从高到低分组：与组首的胜出概率不足 {@link #TIE_PROB} 就留在同一组。
     */
    private static void assignTieGroups(double[][] share, int[] report, double[] mean, int[] tieGroup) {
        int k = report.length;
        Integer[] rows = new Integer[k];
        for (int i = 0; i < k; i++) {
            rows[i] = Integer.valueOf(i);
        }
        final double[] current = mean;
        java.util.Arrays.sort(rows, new java.util.Comparator<Integer>() {
            @Override
            public int compare(Integer a, Integer b) {
                int byMean = Double.compare(current[report[b.intValue()]], current[report[a.intValue()]]);
                if (byMean != 0) {
                    return byMean;
                }
                return a.compareTo(b);
            }
        });

        int group = 1;
        int leader = rows[0].intValue();
        tieGroup[report[leader]] = group;
        for (int pos = 1; pos < k; pos++) {
            int row = rows[pos].intValue();
            if (probGreater(share, leader, row) < TIE_PROB) {
                tieGroup[report[row]] = group;
            } else {
                group++;
                leader = row;
                tieGroup[report[row]] = group;
            }
        }
    }

    /** 后验抽样里 leader 的占比高于 row 的比例。 */
    private static double probGreater(double[][] share, int leader, int row) {
        int count = 0;
        for (int d = 0; d < DRAWS; d++) {
            if (share[leader][d] > share[row][d]) {
                count++;
            }
        }
        return (double) count / DRAWS;
    }

    private static int quantileIndex(double p) {
        int index = (int) Math.floor(p * (DRAWS - 1));
        return Math.max(0, Math.min(DRAWS - 1, index));
    }

    /** MAP 解与 H 的 Cholesky 因子。 */
    private static final class Solution {
        final double[] theta;
        final double[][] chol;

        Solution(double[] theta, double[][] chol) {
            this.theta = theta;
            this.chol = chol;
        }
    }

    /**
     * Newton 求 MAP，并返回 H 的 Cholesky 因子（后验抽样的协方差由它隐含）。
     *
     * @return 失败（Hessian 不是正定）时返回 null
     */
    private static Solution solve(List<int[]> blocks, int n, double[] wins, double sigma) {
        double prior = 1.0D / (sigma * sigma);
        double[] theta = new double[n];
        double[] grad = new double[n];
        double[][] hess = new double[n][n];
        double[] trial = new double[n];
        double value = 0.0D;
        double[][] chol = null;

        for (int iter = 0; iter < 200; iter++) {
            value = evaluate(blocks, n, wins, sigma, theta, grad, hess);
            double maxGrad = 0.0D;
            for (int i = 0; i < n; i++) {
                maxGrad = Math.max(maxGrad, Math.abs(grad[i]));
            }
            if (maxGrad < 1.0e-9D) {
                break;
            }
            chol = cholesky(hess);
            if (chol == null) {
                return null;
            }
            double[] step = solveUpperTranspose(chol, solveLower(chol, grad));
            double slope = 0.0D;
            for (int i = 0; i < n; i++) {
                slope += grad[i] * step[i];
            }
            double rate = 1.0D;
            while (rate > 1.0e-12D) {
                for (int i = 0; i < n; i++) {
                    trial[i] = theta[i] + rate * step[i];
                }
                double trialValue = evaluate(blocks, n, wins, sigma, trial, null, null);
                if (trialValue >= value + 1.0e-4D * rate * slope) {
                    System.arraycopy(trial, 0, theta, 0, n);
                    break;
                }
                rate *= 0.5D;
            }
            if (rate <= 1.0e-12D) {
                break;
            }
        }

        value = evaluate(blocks, n, wins, sigma, theta, null, hess);
        chol = cholesky(hess);
        if (chol == null || Double.isNaN(value)) {
            return null;
        }
        return new Solution(theta, chol);
    }

    /**
     * f(θ) = Σ_屏 (θ_winner − log Σ_屏 e^θ) − ½Σθ²/σ²；需要时一并写出梯度与 Hessian。
     */
    private static double evaluate(List<int[]> blocks, int n, double[] wins, double sigma,
                                   double[] theta, double[] grad, double[][] hess) {
        double prior = 1.0D / (sigma * sigma);
        double value = 0.0D;
        for (int i = 0; i < n; i++) {
            value -= 0.5D * theta[i] * theta[i] * prior;
            if (grad != null) {
                grad[i] = wins[i] - theta[i] * prior;
            }
            if (hess != null) {
                java.util.Arrays.fill(hess[i], 0.0D);
                hess[i][i] = prior;
            }
        }
        double[] weight = new double[8];
        for (int t = 0; t < blocks.size(); t++) {
            int[] block = blocks.get(t);
            int size = block.length;
            if (weight.length < size) {
                weight = new double[size];
            }
            double max = Double.NEGATIVE_INFINITY;
            for (int j = 0; j < size; j++) {
                double v = theta[block[j]];
                if (v > max) {
                    max = v;
                }
            }
            double z = 0.0D;
            for (int j = 0; j < size; j++) {
                double w = Math.exp(theta[block[j]] - max);
                weight[j] = w;
                z += w;
            }
            value += theta[block[0]] - (max + Math.log(z));
            for (int a = 0; a < size; a++) {
                double pa = weight[a] / z;
                int ia = block[a];
                if (grad != null) {
                    grad[ia] -= pa;
                }
                if (hess != null) {
                    for (int b = 0; b < size; b++) {
                        double delta = a == b ? pa : 0.0D;
                        hess[ia][block[b]] += delta - pa * (weight[b] / z);
                    }
                }
            }
        }
        return value;
    }

    /** 下三角 L，H = L Lᵀ；不是正定时返回 null。 */
    private static double[][] cholesky(double[][] a) {
        int n = a.length;
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) {
                    sum -= l[i][k] * l[j][k];
                }
                if (i == j) {
                    if (sum <= TINY) {
                        return null;
                    }
                    l[i][i] = Math.sqrt(sum);
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        return l;
    }

    /** 解 L x = b（前向代入）。 */
    private static double[] solveLower(double[][] l, double[] b) {
        int n = b.length;
        double[] x = new double[n];
        for (int i = 0; i < n; i++) {
            double sum = b[i];
            for (int j = 0; j < i; j++) {
                sum -= l[i][j] * x[j];
            }
            x[i] = sum / l[i][i];
        }
        return x;
    }

    /** 解 Lᵀ x = b（回代）；后验抽样用它，Cov(L⁻ᵀ z) = (L Lᵀ)⁻¹。 */
    private static double[] solveUpperTranspose(double[][] l, double[] b) {
        int n = b.length;
        double[] x = new double[n];
        for (int i = n - 1; i >= 0; i--) {
            double sum = b[i];
            for (int j = i + 1; j < n; j++) {
                sum -= l[j][i] * x[j];
            }
            x[i] = sum / l[i][i];
        }
        return x;
    }
}
