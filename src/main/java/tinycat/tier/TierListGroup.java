package tinycat.tier;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个 Tier List 分组（卡牌第一幕 / 卡牌第一幕后 / Boss 遗物第一幕 / Boss 遗物第二幕）。
 *
 * entries 已按“档位 -> 抓率降序 -> 提供次数降序 -> 名称”排好序；
 * tiers 是按 8 个档位分好的只读视图，方便 HTML/JSON 输出。
 */
public class TierListGroup {
    public final String key;
    public final String labelEn;
    public final String labelZh;
    public final List<TierEntry> entries;
    public final Map<Tier, List<TierEntry>> tiers;

    public TierListGroup(String key, String labelEn, String labelZh, List<TierEntry> entries) {
        this.key = key;
        this.labelEn = labelEn;
        this.labelZh = labelZh;
        this.entries = entries;

        Map<Tier, List<TierEntry>> map = new LinkedHashMap<>();
        for (Tier tier : Tier.values()) {
            map.put(tier, new ArrayList<TierEntry>());
        }
        for (TierEntry entry : entries) {
            if (entry.tier != null) {
                map.get(entry.tier).add(entry);
            }
        }
        this.tiers = map;
    }

    public List<TierEntry> entriesOf(Tier tier) {
        List<TierEntry> list = tiers.get(tier);
        return list == null ? new ArrayList<TierEntry>() : list;
    }

    public int countOf(Tier tier) {
        return entriesOf(tier).size();
    }
}
