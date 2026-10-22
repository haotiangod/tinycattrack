package tinycat.tier;

/**
 * 按统计页决定导出范围。
 *
 * 这样按钮只在具体页面出现，导出包也只包含当前页对应的 Tier List，
 * 不会在「总体统计」页导出无关的卡牌/Boss 遗物数据。
 */
public enum TierListScope {
    ALL("all", "All pick rates", "全部抓率"),
    CARDS_ACT1("cards_act1", "Card pick rate - Act 1", "第一幕卡牌选取率"),
    CARDS_AFTER_ACT1("cards_after_act1", "Card pick rate - After Act 1", "第一幕后卡牌选取率"),
    BOSS_RELICS("boss_relics", "Boss relic pick rate", "BOSS 遗物选取率");

    public final String key;
    public final String labelEn;
    public final String labelZh;

    TierListScope(String key, String labelEn, String labelZh) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
    }
}
