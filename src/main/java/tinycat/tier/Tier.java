package tinycat.tier;

/**
 * Tier List 的 8 个档位。
 *
 * 分档依据（必须是原始计数，不能用四舍五入后的百分比）：
 * - ZERO：picked == 0 且 offered > 0，表示从未被选取
 * - S：抓率 >= 90%
 * - A：75% <= 抓率 < 90%
 * - B：60% <= 抓率 < 75%
 * - C：45% <= 抓率 < 60%
 * - D：30% <= 抓率 < 45%
 * - E：15% <= 抓率 < 30%
 * - F：0 < 抓率 < 15%
 *
 * 注意：文档里的 F 写的是 1-14%，但 0.1%-0.9% 会落在空档；
 * 这里把 (0, 15%) 全部归入 F，保证任何 picked > 0 的条目都有档位。
 */
public enum Tier {
    S("S", "S (90%+)", "S 必抓 (90%+)", 90.0D, 100.0D, "#e5c07b"),
    A("A", "A (75-89%)", "A (75-89%)", 75.0D, 90.0D, "#98c379"),
    B("B", "B (60-74%)", "B (60-74%)", 60.0D, 75.0D, "#61afef"),
    C("C", "C (45-59%)", "C (45-59%)", 45.0D, 60.0D, "#c678dd"),
    D("D", "D (30-44%)", "D (30-44%)", 30.0D, 45.0D, "#e06c75"),
    E("E", "E (15-29%)", "E (15-29%)", 15.0D, 30.0D, "#d19a66"),
    F("F", "F (<15%)", "F 极少 (<15%)", 0.0D, 15.0D, "#7f848e"),
    ZERO("0", "0 (0%)", "0 从未被选 (0%)", 0.0D, 0.0D, "#4b5263"),
    /**
     * 排名型档位：用于「只有次数或比率、量级跨度极大」的表（火堆 / 商店 / 尼奥选取率）。
     *
     * 这些表不能用固定百分比阈值（火堆第一幕头名 24.8%，尼奥选取率头名 100%，
     * 同一套 90/75/60 阈值套上去要么全 S 要么全 F），所以改按「组内名次」分档，
     * 具体规则见 {@link RankRule}。标签只写字母，规则由导出说明行交代。
     */
    RS("RS", "S", "S", 0.0D, 0.0D, "#e5c07b"),
    RA("RA", "A", "A", 0.0D, 0.0D, "#98c379"),
    RB("RB", "B", "B", 0.0D, 0.0D, "#61afef"),
    RC("RC", "C", "C", 0.0D, 0.0D, "#c678dd"),
    RD("RD", "D", "D", 0.0D, 0.0D, "#e06c75"),
    /**
     * 排名型最低档：指标 < 1%（含 0%，即从未被选）。
     *
     * 长尾表的 0%~0.9% 条目数量很多，若和 1%~7% 一起走自然断点，会被并成同一档，
     * 看不出「几乎不会被选」和「偶尔被选」的差别，所以单独切出来。
     */
    RZ("R0", "0 (<1%)", "0 极低 (<1%)", 0.0D, 0.0D, "#4b5263");

    public final String key;
    public final String labelEn;
    public final String labelZh;
    public final double minRate;
    public final double maxRate;
    public final String color;

    Tier(String key, String labelEn, String labelZh, double minRate, double maxRate, String color) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
        this.minRate = minRate;
        this.maxRate = maxRate;
        this.color = color;
    }

    /**
     * 根据原始计数分档。
     *
     * @param picked  被选取次数（Rate.win）
     * @param offered 被提供次数（Rate.win + Rate.loss）
     * @return 档位；offered <= 0 时返回 null（不入档）
     */
    public static Tier of(int picked, int offered) {
        if (offered <= 0) {
            return null;
        }
        if (picked <= 0) {
            return ZERO;
        }
        double rate = 100.0D * picked / offered;
        if (rate >= 90.0D) {
            return S;
        }
        if (rate >= 75.0D) {
            return A;
        }
        if (rate >= 60.0D) {
            return B;
        }
        if (rate >= 45.0D) {
            return C;
        }
        if (rate >= 30.0D) {
            return D;
        }
        if (rate >= 15.0D) {
            return E;
        }
        return F;
    }

    public boolean isZero() {
        return this == ZERO;
    }

    /** 排名型档位（S/A/B/C/D），与抓率型档位（S..F/0）互斥使用。 */
    public boolean isRankBased() {
        return this == RS || this == RA || this == RB || this == RC || this == RD || this == RZ;
    }
}
