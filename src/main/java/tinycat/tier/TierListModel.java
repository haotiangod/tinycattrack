package tinycat.tier;

import tinycat.stats.SeedFilter;

import java.util.List;

/**
 * 一次导出的完整数据快照。
 * 只包含纯 Java 数据，不依赖游戏对象，方便 CSV/JSON/HTML 生成与自检。
 */
public class TierListModel {
    public final String generatedAt;
    public final String characterId;
    public final String characterEn;
    public final String characterZh;
    public final boolean rotate;
    public final String startDate;
    public final String endDate;
    public final boolean includeAbandons;
    public final SeedFilter seedFilter;
    public final int minSample;
    public final int runCount;
    public final TierListScope scope;
    public final List<TierListGroup> groups;
    /**
     * 本份数据用的是不是「标准化率」口径。
     *
     * false = 界面同源的简单率（次数 / 分母），游戏内页面永远用这个；
     * true  = Plackett-Luce 标准化率（剔除对手强度与出场次数差异），只在导出包里多出一份。
     * 两份回答的是不同问题，必须同时给出，不能互相覆盖。
     */
    public final boolean standardized;

    public TierListModel(String generatedAt,
                         String characterId,
                         String characterEn,
                         String characterZh,
                         boolean rotate,
                         String startDate,
                         String endDate,
                         boolean includeAbandons,
                         SeedFilter seedFilter,
                         int minSample,
                         int runCount,
                         TierListScope scope,
                         List<TierListGroup> groups) {
        this(generatedAt, characterId, characterEn, characterZh, rotate, startDate, endDate,
                includeAbandons, seedFilter, minSample, runCount, scope, groups, false);
    }

    public TierListModel(String generatedAt,
                         String characterId,
                         String characterEn,
                         String characterZh,
                         boolean rotate,
                         String startDate,
                         String endDate,
                         boolean includeAbandons,
                         SeedFilter seedFilter,
                         int minSample,
                         int runCount,
                         TierListScope scope,
                         List<TierListGroup> groups,
                         boolean standardized) {
        this.generatedAt = generatedAt;
        this.characterId = characterId;
        this.characterEn = characterEn;
        this.characterZh = characterZh;
        this.rotate = rotate;
        this.startDate = startDate;
        this.endDate = endDate;
        this.includeAbandons = includeAbandons;
        this.seedFilter = seedFilter == null ? SeedFilter.ALL : seedFilter;
        this.minSample = minSample;
        this.runCount = runCount;
        this.scope = scope;
        this.groups = groups;
        this.standardized = standardized;
    }

    public int totalEntries() {
        int total = 0;
        for (TierListGroup group : groups) {
            total += group.entries.size();
        }
        return total;
    }

    public boolean isAggregate() {
        return "ALL".equals(characterId);
    }

    public String characterKind() {
        return isAggregate() ? "aggregate" : "character";
    }

    public int characterCount() {
        return 4;
    }
}
