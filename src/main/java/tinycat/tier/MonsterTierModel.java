package tinycat.tier;

import tinycat.stats.SeedFilter;

import java.util.ArrayList;
import java.util.List;

public final class MonsterTierModel {
    public final String generatedAt;
    public final String characterId;
    public final String characterEn;
    public final String characterZh;
    public final boolean rotate;
    public final String startDate;
    public final String endDate;
    public final boolean includeAbandons;
    public final SeedFilter seedFilter;
    public final int bossMinSample;
    public final int nonBossMinSample;
    public final int runCount;
    public final int lowSampleCount;
    public final List<MonsterTierGroup> groups;

    public MonsterTierModel(String generatedAt,
                            String characterId,
                            String characterEn,
                            String characterZh,
                            boolean rotate,
                            String startDate,
                            String endDate,
                            boolean includeAbandons,
                            SeedFilter seedFilter,
                            int bossMinSample,
                            int nonBossMinSample,
                            int runCount,
                            int lowSampleCount,
                            List<MonsterTierGroup> groups) {
        this.generatedAt = generatedAt;
        this.characterId = characterId;
        this.characterEn = characterEn;
        this.characterZh = characterZh;
        this.rotate = rotate;
        this.startDate = startDate;
        this.endDate = endDate;
        this.includeAbandons = includeAbandons;
        this.seedFilter = seedFilter;
        this.bossMinSample = bossMinSample;
        this.nonBossMinSample = nonBossMinSample;
        this.runCount = runCount;
        this.lowSampleCount = lowSampleCount;
        this.groups = groups == null ? new ArrayList<MonsterTierGroup>() : groups;
    }

    public boolean isAggregate() {
        return "ALL".equals(characterId);
    }

    public List<MonsterTierEntry> allEntries() {
        List<MonsterTierEntry> result = new ArrayList<MonsterTierEntry>();
        for (MonsterTierGroup group : groups) {
            result.addAll(group.allEntries());
        }
        return result;
    }
}
