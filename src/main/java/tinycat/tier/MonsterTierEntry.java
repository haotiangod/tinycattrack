package tinycat.tier;

public final class MonsterTierEntry {
    public final String id;
    public final String nameEn;
    public final String nameZh;
    /** boss / elite / normal / mixed */
    public final String type;
    public final int sampleCount;
    /** 占同幕同类（Boss 或非 Boss）全部样本的百分比，0-100。 */
    public final double sampleShare;
    /** 该遭遇所有样本的实际战损总和；负值表示净回血。 */
    public final int totalHpLoss;
    /** 占同幕同类净战损合计的百分比；同类合计 <= 0 时为 NaN。 */
    public final double lossShare;
    public final double meanHpLoss;
    public final double medianHpLoss;
    public final double p90HpLoss;
    public final double ci95Low;
    public final double ci95High;
    /** 样本不足的标记；仅用于展示提示，不再影响分档。 */
    public final boolean lowSample;
    /** 分档结果；所有条目都会分档，样本不足的由 lowSample 标注。 */
    public final MonsterTier tier;
    public final int tierRank;
    /** 非 Boss 条目在该幕的等人数分档排名；Boss 为 0。 */
    public final int nonBossRank;
    public String imagePath;

    public MonsterTierEntry(String id,
                            String nameEn,
                            String nameZh,
                            String type,
                            int sampleCount,
                            double sampleShare,
                            int totalHpLoss,
                            double lossShare,
                            double meanHpLoss,
                            double medianHpLoss,
                            double p90HpLoss,
                            double ci95Low,
                            double ci95High,
                            MonsterTier tier,
                            int tierRank,
                            int nonBossRank,
                            boolean lowSample) {
        this.id = id;
        this.nameEn = nameEn;
        this.nameZh = nameZh;
        this.type = type;
        this.sampleCount = sampleCount;
        this.sampleShare = sampleShare;
        this.totalHpLoss = totalHpLoss;
        this.lossShare = lossShare;
        this.meanHpLoss = meanHpLoss;
        this.medianHpLoss = medianHpLoss;
        this.p90HpLoss = p90HpLoss;
        this.ci95Low = ci95Low;
        this.ci95High = ci95High;
        this.tier = tier;
        this.tierRank = tierRank;
        this.nonBossRank = nonBossRank;
        this.lowSample = lowSample;
    }

    public boolean isBoss() {
        return "boss".equals(type);
    }

    public boolean isRanked() {
        return tier != null;
    }

    public String typeZh() {
        if ("boss".equals(type)) {
            return "Boss";
        }
        if ("elite".equals(type)) {
            return "精英";
        }
        if ("mixed".equals(type)) {
            return "普通/精英";
        }
        return "普通";
    }

    public String typeEn() {
        if ("boss".equals(type)) {
            return "Boss";
        }
        if ("elite".equals(type)) {
            return "Elite";
        }
        if ("mixed".equals(type)) {
            return "Normal / Elite";
        }
        return "Normal";
    }
}
