package tinycat.tier;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 「同一份数据、两种口径」的视图切换。
 *
 * 为什么需要两份：
 * - 简单率（次数 / 分母）回答「玩家实际点了哪个」，和游戏内页面、.run 记录一一对应，
 *   是唯一能直接对着原始存档核对的数字；
 * - 标准化率（Plackett-Luce）回答「把对手强度、出场次数造成的偏差扣掉之后，谁更值得选」。
 *   三选一这种小样本场景必须用，否则「常和强卡一起出现」的选项会被系统性低估。
 * 两者不是同一件事，任何一份都不能替代另一份，所以导出时必须两份都出：
 *   tierlist.html / .png / .jpg            → 简单率（与游戏内页面一致）
 *   tierlist_standardized.html / .png / .jpg → 标准化率
 *
 * 实现方式：标准化视图不新造渲染器，而是把 {@link TierEntry#standardized} 复制进
 * {@link TierEntry#rate}，再按标准化率重排序、重新分档。这样图片/HTML/表格渲染代码
 * 完全不用改，两种视图不会出现「一份改了另一份忘了改」的漂移。
 */
public final class RateVariant {
    /** 标准化视图的文件名前缀后缀：tierlist + 这个 = tierlist_standardized.png */
    public static final String FILE_SUFFIX = "_standardized";
    /** 图表/ 目录里的中文后缀：00-完整梯度图 + 这个 = 00-完整梯度图-标准化率 */
    public static final String CHART_SUFFIX = "-标准化率";

    private RateVariant() {
    }

    /** 这份数据里有没有任何一个条目带标准化率。 */
    public static boolean hasStandardized(TierListModel model) {
        if (model == null || model.groups == null) {
            return false;
        }
        for (TierListGroup group : model.groups) {
            for (TierEntry entry : group.entries) {
                if (entry.standardized >= 0.0D) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 生成标准化率视图；没有标准化数据时返回 null（调用方据此跳过第二份导出）。
     *
     * 只保留带标准化率的条目：没有标准化率的表（卡牌抓率等）本来就不该出现在这份里，
     * 硬塞进去会让读者以为它们被算过。
     */
    public static TierListModel standardized(TierListModel model) {
        if (!hasStandardized(model)) {
            return null;
        }
        List<TierListGroup> groups = new ArrayList<TierListGroup>(model.groups.size());
        for (TierListGroup group : model.groups) {
            List<TierEntry> kept = new ArrayList<TierEntry>();
            for (TierEntry entry : group.entries) {
                if (entry.standardized >= 0.0D) {
                    kept.add(entry);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            Collections.sort(kept, new Comparator<TierEntry>() {
                @Override
                public int compare(TierEntry a, TierEntry b) {
                    // 统计上分不出先后的条目并成一组；组内样本大的在前，因为更可信
                    if (a.tieGroup > 0 && b.tieGroup > 0) {
                        int byTie = Integer.compare(a.tieGroup, b.tieGroup);
                        if (byTie != 0) {
                            return byTie;
                        }
                        int bySample = Integer.compare(b.offered, a.offered);
                        if (bySample != 0) {
                            return bySample;
                        }
                    }
                    int byMetric = Double.compare(b.standardized, a.standardized);
                    if (byMetric != 0) {
                        return byMetric;
                    }
                    int byCount = Integer.compare(b.offered, a.offered);
                    if (byCount != 0) {
                        return byCount;
                    }
                    return a.nameEn.compareToIgnoreCase(b.nameEn);
                }
            });
            List<Double> values = new ArrayList<Double>(kept.size());
            for (TierEntry entry : kept) {
                values.add(Double.valueOf(entry.standardized));
            }
            // 分档跟着标准化率重切：简单率切出来的档在这里没有意义。
            // 卡牌表条目多、一半以上低于 1%，长尾再切一档，整表共 7 档；
            // 尼奥等条目少的标准化表仍用原规则，不凭空多出空行。
            boolean splitTail = model.scope == null || model.scope.splitsStandardTail();
            List<Tier> tiers = splitTail
                    ? RankRule.assignStandardized(values)
                    : RankRule.assign(values, RankRule.ACTIVE);
            List<TierEntry> entries = new ArrayList<TierEntry>(kept.size());
            for (int i = 0; i < kept.size(); i++) {
                TierEntry src = kept.get(i);
                TierEntry copy = new TierEntry(src.id, src.nameEn, src.nameZh,
                        src.offered, src.picked, src.standardized, tiers.get(i), src.type);
                copy.imagePath = src.imagePath;
                copy.imageText = src.imageText;
                copy.standardized = src.standardized;
                copy.stdLow = src.stdLow;
                copy.stdHigh = src.stdHigh;
                copy.tieGroup = src.tieGroup;
                copy.tied = src.tied;
                copy.rankOne = src.rankOne;
                // 简单率视图把标准化率塞在备注里；这里反过来，备注写简单率与置信区间
                copy.context = contextOf(src, model.scope);
                entries.add(copy);
            }
            groups.add(new TierListGroup(group.key, group.labelEn, group.labelZh, entries, group.note));
        }
        return new TierListModel(model.generatedAt,
                model.characterId,
                model.characterEn,
                model.characterZh,
                model.rotate,
                model.startDate,
                model.endDate,
                model.includeAbandons,
                model.seedFilter,
                model.minSample,
                model.runCount,
                model.scope,
                groups,
                true);
    }

    /**
     * 标准化视图的备注：反过来写简单率与置信区间。
     *
     * 图片卡片只有 ~25 个字符的副标宽度，所以这里用最省字的形式（占比 20.00% · CI 11.0–13.0），
     * CSV/JSON/xlsx 里同样够用。卡牌表的副标本来就带「被选 / 提供」，再塞 CI 一定被截断，
     * 所以卡牌只写占比，CI 走表格与 CSV/xlsx/JSON 的独立列。
     */
    private static String contextOf(TierEntry entry, TierListScope scope) {
        boolean card = scope == null || scope == TierListScope.ALL || scope.isCards();
        if (card) {
            return String.format(Locale.US, "占比 %.2f%%", entry.rate);
        }
        if (entry.stdHigh > 0.0D) {
            return String.format(Locale.US, "占比 %.2f%% · CI %.1f–%.1f%s",
                    entry.rate, entry.stdLow, entry.stdHigh, entry.tied ? " · 并列" : "");
        }
        return String.format(Locale.US, "占比 %.2f%%%s", entry.rate, entry.tied ? " · 并列" : "");
    }
}
