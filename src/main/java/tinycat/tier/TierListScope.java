package tinycat.tier;

/**
 * 按统计页决定导出范围。
 *
 * 这样按钮只在具体页面出现，导出包也只包含当前页对应的 Tier List，
 * 不会在「总体统计」页导出无关的卡牌/Boss 遗物数据。
 *
 * 前几个是「抓率型」（指标 = 选取次数 / 提供次数，档位用固定百分比阈值）；
 * 后三个是「排名型」（火堆 / 商店 / 尼奥），各维度量级差异太大，档位改用 {@link RankRule}。
 *
 * 卡牌有四个分幕视图与一个打包视图：前端「第一幕」「第一幕后」两页都落到
 * {@link #CARDS_ALL_ACTS}，一次导出四组，避免用户只能看到其中一段。
 */
public enum TierListScope {
    ALL("all", "All pick rates", "全部抓率"),
    CARDS_ALL_ACTS("cards_all_acts", "Card pick rate - all acts", "卡牌选取率（分幕）"),
    CARDS_ACT1("cards_act1", "Card pick rate - Act 1", "第一幕卡牌选取率"),
    CARDS_ACT2("cards_act2", "Card pick rate - Act 2", "第二幕卡牌选取率"),
    CARDS_ACT3_4("cards_act3_4", "Card pick rate - Acts 3-4", "第三、四幕卡牌选取率"),
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

    /** 卡牌抓率型：CSV/HTML 带标准化率口径，档位用 S..F。 */
    public boolean isCards() {
        return this == CARDS_ALL_ACTS || this == CARDS_ACT1 || this == CARDS_ACT2
                || this == CARDS_ACT3_4 || this == CARDS_AFTER_ACT1;
    }

    /**
     * 标准化视图是否把 <1% 长尾再切一档（R0 / R00）。
     *
     * 只有卡牌表有这么多长尾条目：标准化率合计 100%，七八十张卡里一半在 1% 以下，
     * 一个 R0 会装下 50+ 张。尼奥/火堆/商店条目少，切了只会多出空行。
     */
    public boolean splitsStandardTail() {
        return this == ALL || isCards();
    }
}
