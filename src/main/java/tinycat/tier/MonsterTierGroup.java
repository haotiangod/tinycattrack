package tinycat.tier;

import java.util.ArrayList;
import java.util.List;

public final class MonsterTierGroup {
    public final int act;
    public final String labelEn;
    public final String labelZh;
    public final List<MonsterTierEntry> bosses;
    public final List<MonsterTierEntry> nonBosses;
    /** 低于样本门槛的条目；0.2.x 之后不再单独成区，恒为空列表，保留字段兼容旧导出。 */
    public final List<MonsterTierEntry> lowSampleEntries;
    /** 本幕被标记为"样本不足"的条目数量（仍然参与分档）。 */
    public final int lowSampleFlagged;
    public final int bossSampleTotal;
    public final int nonBossSampleTotal;
    public final int bossNetHpLoss;
    public final int nonBossNetHpLoss;
    public final int lowSampleCount;

    public MonsterTierGroup(int act,
                            String labelEn,
                            String labelZh,
                            List<MonsterTierEntry> bosses,
                            List<MonsterTierEntry> nonBosses,
                            List<MonsterTierEntry> lowSampleEntries,
                            int bossSampleTotal,
                            int nonBossSampleTotal,
                            int bossNetHpLoss,
                            int nonBossNetHpLoss) {
        this.act = act;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
        this.bosses = bosses;
        this.nonBosses = nonBosses;
        this.lowSampleEntries = lowSampleEntries;
        this.bossSampleTotal = bossSampleTotal;
        this.nonBossSampleTotal = nonBossSampleTotal;
        this.bossNetHpLoss = bossNetHpLoss;
        this.nonBossNetHpLoss = nonBossNetHpLoss;
        this.lowSampleCount = lowSampleEntries.size();

        int flagged = 0;
        for (MonsterTierEntry entry : bosses) {
            if (entry.lowSample) {
                flagged++;
            }
        }
        for (MonsterTierEntry entry : nonBosses) {
            if (entry.lowSample) {
                flagged++;
            }
        }
        this.lowSampleFlagged = flagged;
    }

    public List<MonsterTierEntry> entriesOf(MonsterTier tier) {
        List<MonsterTierEntry> result = new ArrayList<MonsterTierEntry>();
        if (tier == null) {
            return result;
        }
        if (tier.isBoss()) {
            result.addAll(bosses);
            return result;
        }
        for (MonsterTierEntry entry : nonBosses) {
            if (entry.tier == tier) {
                result.add(entry);
            }
        }
        return result;
    }

    public int countOf(MonsterTier tier) {
        return entriesOf(tier).size();
    }

    public List<MonsterTierEntry> allEntries() {
        List<MonsterTierEntry> result = new ArrayList<MonsterTierEntry>(
                bosses.size() + nonBosses.size() + lowSampleEntries.size());
        result.addAll(bosses);
        result.addAll(nonBosses);
        result.addAll(lowSampleEntries);
        return result;
    }

    public boolean isEmpty() {
        return bosses.isEmpty() && nonBosses.isEmpty() && lowSampleEntries.isEmpty();
    }
}
