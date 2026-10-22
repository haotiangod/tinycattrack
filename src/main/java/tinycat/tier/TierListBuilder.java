package tinycat.tier;

import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import tinycat.NameMapper;
import tinycat.YearMonth;
import tinycat.stats.BossRelic;
import tinycat.stats.Card;
import tinycat.stats.ClassStat;
import tinycat.stats.Rate;
import tinycat.stats.SeedFilter;
import tinycat.ui.MoreStatsScreen;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 ClassStat 组装 Tier List 导出数据。
 *
 * 重要口径（已与用户确认）：
 * - 卡牌第一幕 / 第一幕后：直接复用 ClassStat.cardPicksAct1 / cardPicksAfterAct1，
 *   不重新按 Run.getAct(floor) 分幕。现有聚合用 floor >= 17 作为“第一幕后”，
 *   与统计屏现有数字保持一致。
 * - Boss 遗物：复用 bossRelicPickRateAct1 / Act2。
 * - SKIP 保留；Singing Bowl（颂钵）并入 SKIP 桶，因为它本质也是“跳过拿牌”。
 * - CURSE / STATUS 卡从卡牌 Tier List 剔除。
 * - 只统计 Run.valid() 已经滤掉非 A20、每日/特殊局的数据；种子局由当前面板筛选决定。
 */
public final class TierListBuilder {
    private static final String[] CHARACTER_IDS = {
            "ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"
    };
    private static final String[] CHARACTER_EN = {
            "All characters (aggregate)", "Ironclad", "Silent", "Defect", "Watcher"
    };
    private static final String[] CHARACTER_ZH = {
            "全角色汇总", "战士", "猎人", "机器人", "观者"
    };

    private TierListBuilder() {
    }

    public static TierListModel build(MoreStatsScreen screen) {
        return build(screen, TierListScope.ALL);
    }

    public static TierListModel build(MoreStatsScreen screen, TierListScope scope) {
        if (screen == null) {
            throw new IllegalStateException("statistics screen is not initialized");
        }
        if (scope == null) {
            scope = TierListScope.ALL;
        }
        ClassStat cs = screen.getClassStat();
        if (cs == null) {
            throw new IllegalStateException("class statistics is not ready");
        }

        int classIndex = screen.classDropdown == null ? 0 : screen.classDropdown.getSelectedIndex();
        if (classIndex < 0 || classIndex >= CHARACTER_IDS.length) {
            classIndex = 0;
        }
        int minSample = screen.sampleSizeDropdown == null ? 0 : screen.sampleSizeDropdown.getSelectedIndex();

        List<TierListGroup> groups = new ArrayList<TierListGroup>(4);
        if (scope == TierListScope.ALL || scope == TierListScope.CARDS_ACT1) {
            groups.add(buildCards("cards_act1",
                    "Card pick rate - Act 1",
                    "第一幕卡牌选取率",
                    cs.cardPicksAct1,
                    minSample));
        }
        if (scope == TierListScope.ALL || scope == TierListScope.CARDS_AFTER_ACT1) {
            groups.add(buildCards("cards_after_act1",
                    "Card pick rate - After Act 1",
                    "第一幕后卡牌选取率",
                    cs.cardPicksAfterAct1,
                    minSample));
        }
        if (scope == TierListScope.ALL || scope == TierListScope.BOSS_RELICS) {
            groups.add(buildRelics("boss_relics_act1",
                    "Boss relic pick rate - Act 1",
                    "第一幕 BOSS 遗物选取率",
                    cs.bossRelicPickRateAct1,
                    minSample));
            groups.add(buildRelics("boss_relics_act2",
                    "Boss relic pick rate - Act 2",
                    "第二幕 BOSS 遗物选取率",
                    cs.bossRelicPickRateAct2,
                    minSample));
        }

        String generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        String startDate = dateAt(screen.startDates, screen.startDateDropdown == null ? 0 : screen.startDateDropdown.getSelectedIndex());
        String endDate = dateAt(screen.endDates, screen.endDateDropDown == null ? 0 : screen.endDateDropDown.getSelectedIndex());
        boolean includeAbandons = screen.abandonDropdown == null || screen.abandonDropdown.getSelectedIndex() == 0;
        SeedFilter seedFilter = screen.currentSeedFilter();
        int runCount = cs.numVictory + cs.numDeath;

        return new TierListModel(generatedAt,
                CHARACTER_IDS[classIndex],
                CHARACTER_EN[classIndex],
                CHARACTER_ZH[classIndex],
                cs.rotate,
                startDate,
                endDate,
                includeAbandons,
                seedFilter,
                minSample,
                runCount,
                scope,
                groups);
    }

    private static TierListGroup buildCards(String key,
                                            String labelEn,
                                            String labelZh,
                                            List<Rate<Card>> rates,
                                            int minSample) {
        // LinkedHashMap 保证同名条目只出现一次；int[0] = picked, int[1] = offered
        Map<String, int[]> counts = new LinkedHashMap<String, int[]>();
        if (rates != null) {
            for (Rate<Card> rate : rates) {
                if (rate == null || rate.what == null) {
                    continue;
                }
                String name = rate.what.name;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                if (isCurseOrStatus(name)) {
                    continue;
                }
                // 颂钵是“跳过并加最大生命”的变体，并入 SKIP 桶
                String mergeKey = "Singing Bowl".equals(name) ? "SKIP" : name;
                int[] counter = counts.get(mergeKey);
                if (counter == null) {
                    counter = new int[2];
                    counts.put(mergeKey, counter);
                }
                counter[0] += rate.win;
                counter[1] += rate.total();
            }
        }

        List<TierEntry> entries = new ArrayList<TierEntry>();
        for (Map.Entry<String, int[]> item : counts.entrySet()) {
            int picked = item.getValue()[0];
            int offered = item.getValue()[1];
            if (offered <= 0 || offered < minSample) {
                continue;
            }
            Tier tier = Tier.of(picked, offered);
            if (tier == null) {
                continue;
            }
            String name = item.getKey();
            String nameEn;
            String nameZh;
            String type;
            if ("SKIP".equals(name)) {
                nameEn = "Skip";
                nameZh = "跳过";
                type = "skip";
            } else {
                nameEn = name;
                nameZh = NameMapper.toChinese(name);
                type = "card";
            }
            double rate = 100.0D * picked / offered;
            entries.add(new TierEntry(name, nameEn, nameZh, offered, picked, rate, tier, type));
        }
        sortEntries(entries);
        return new TierListGroup(key, labelEn, labelZh, entries);
    }

    private static TierListGroup buildRelics(String key,
                                             String labelEn,
                                             String labelZh,
                                             List<Rate<BossRelic>> rates,
                                             int minSample) {
        List<TierEntry> entries = new ArrayList<TierEntry>();
        if (rates != null) {
            for (Rate<BossRelic> rate : rates) {
                if (rate == null || rate.what == null) {
                    continue;
                }
                int picked = rate.win;
                int offered = rate.total();
                if (offered <= 0 || offered < minSample) {
                    continue;
                }
                Tier tier = Tier.of(picked, offered);
                if (tier == null) {
                    continue;
                }
                String name = rate.what.name;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                String nameEn;
                String nameZh;
                String type;
                if ("SKIP".equals(name)) {
                    nameEn = "Skip";
                    nameZh = "跳过";
                    type = "skip";
                } else {
                    nameEn = name;
                    nameZh = NameMapper.toChinese(name);
                    type = "boss_relic";
                }
                double rateValue = 100.0D * picked / offered;
                entries.add(new TierEntry(name, nameEn, nameZh, offered, picked, rateValue, tier, type));
            }
        }
        sortEntries(entries);
        return new TierListGroup(key, labelEn, labelZh, entries);
    }

    private static boolean isCurseOrStatus(String name) {
        if ("SKIP".equals(name) || "Singing Bowl".equals(name)) {
            return false;
        }
        try {
            AbstractCard card = CardLibrary.getCard(name);
            return card != null
                    && (card.type == AbstractCard.CardType.CURSE
                    || card.type == AbstractCard.CardType.STATUS);
        } catch (Exception ignored) {
            // 查不到（Mod 卡牌/特殊条目）就保留，图片阶段再用占位图兜底
            return false;
        }
    }

    private static void sortEntries(List<TierEntry> entries) {
        entries.sort(new Comparator<TierEntry>() {
            @Override
            public int compare(TierEntry a, TierEntry b) {
                int tierCompare = Integer.compare(a.tier.ordinal(), b.tier.ordinal());
                if (tierCompare != 0) {
                    return tierCompare;
                }
                int rateCompare = Double.compare(b.rate, a.rate);
                if (rateCompare != 0) {
                    return rateCompare;
                }
                int offeredCompare = Integer.compare(b.offered, a.offered);
                if (offeredCompare != 0) {
                    return offeredCompare;
                }
                int pickedCompare = Integer.compare(b.picked, a.picked);
                if (pickedCompare != 0) {
                    return pickedCompare;
                }
                return a.nameEn.compareToIgnoreCase(b.nameEn);
            }
        });
    }

    private static String dateAt(List<YearMonth> dates, int index) {
        if (dates == null || dates.isEmpty()) {
            return "";
        }
        if (index < 0 || index >= dates.size()) {
            index = 0;
        }
        return dates.get(index).toString();
    }
}
