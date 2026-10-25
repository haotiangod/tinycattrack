package tinycat.tier;

/**
 * 按统计页决定导出范围。
 *
 * 这样按钮只在具体页面出现，导出包也只包含当前页对应的 Tier List，
 * 不会在「总体统计」页导出无关的卡牌/Boss 遗物数据。
 *
 * 前四个是「抓率型」（指标 = 选取次数 / 提供次数，档位用固定百分比阈值）；
 * 后三个是「排名型」（火堆 / 商店 / 尼奥），各维度量级差异太大，档位改用 {@link RankRule}。
 */
public enum TierListScope {
    ALL("all", "All pick rates", "全部抓率"),
    CARDS_ACT1("cards_act1", "Card pick rate - Act 1", "第一幕卡牌选取率"),
    CARDS_AFTER_ACT1("cards_after_act1", "Card pick rate - After Act 1", "第一幕后卡牌选取率"),
    BOSS_RELICS("boss_relics", "Boss relic pick rate", "BOSS 遗物选取率"),
    CAMPFIRE("campfire_actions", "Campfire action tiers", "火堆行动梯度"),
    CAMPFIRE_UPGRADE("campfire_upgrade_pref", "Campfire preference", "火堆偏好"),
    SHOP("shop_actions", "Shop action tiers", "商店行动梯度"),
    NEOW("neow_pick_rate", "Neow option tiers (bonus + cost)", "尼奥开局选项梯度（奖励 + 代价）");

    public final String key;
    public final String labelEn;
    public final String labelZh;

    TierListScope(String key, String labelEn, String labelZh) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
    }

    /** 排名型（用 RankRule 分档，指标不是抓率）。 */
    public boolean isRankBased() {
        return this == CAMPFIRE || this == CAMPFIRE_UPGRADE || this == SHOP || this == NEOW;
    }
}
