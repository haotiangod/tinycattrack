package tinycat.tier;

import com.megacrit.cardcrawl.cards.AbstractCard;
import tinycat.NameMapper;
import tinycat.YearMonth;
import tinycat.stats.BossRelic;
import tinycat.stats.Card;
import tinycat.stats.ClassStat;
import tinycat.stats.Neow;
import tinycat.stats.Rate;
import tinycat.stats.SeedFilter;
import tinycat.ui.MoreStatsScreen;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 ClassStat 组装 Tier List 导出数据。
 *
 * 重要口径（已与用户确认）：
 * - 卡牌按实际幕拆成第一幕 / 第二幕 / 第三、四幕，另给一份「第一幕后」（第二至四幕合并）。
 *   分幕一律用 ClassStat 里已经按 Run.getAct(floor) 归好类的数据：一幕包含 floor 0，
 *   也包含一幕 Boss 后的奖励屏（真实数据里 Boss 在 17 层，不能用 floor >= 17 硬切）。
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

    /** 升级偏好的硬门槛：拥有不到 10 张时 1/1、2/2 的 100% 全是噪声。 */
    // 不设额外下限：导出跟随界面「样本量」下拉（0 = 全量）。README 里给出建议样本量。
    private static final int MIN_UPGRADE_SAMPLE = 0;

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
        if (scope == TierListScope.CAMPFIRE) {
            groups.addAll(buildCampfire(cs, minSample));
        }
        if (scope == TierListScope.CAMPFIRE_UPGRADE) {
            groups.addAll(buildCampfireUpgrade(cs, minSample));
        }
        if (scope == TierListScope.SHOP) {
            groups.addAll(buildShop(cs, minSample));
        }
        if (scope == TierListScope.NEOW) {
            groups.add(buildNeow(cs, minSample));
        }
        if (wantsCardGroup(scope, TierListScope.CARDS_ACT1)) {
            groups.add(buildCards("cards_act1",
                    "Card pick rate - Act 1",
                    "第一幕卡牌选取率",
                    cs.cardPicksAct1,
                    cs.cardChoicesAct1,
                    minSample));
        }
        if (wantsCardGroup(scope, TierListScope.CARDS_ACT2)) {
            groups.add(buildCards("cards_act2",
                    "Card pick rate - Act 2",
                    "第二幕卡牌选取率",
                    cs.cardPicksAct2,
                    cs.cardChoicesAct2,
                    minSample));
        }
        if (wantsCardGroup(scope, TierListScope.CARDS_ACT3_4)) {
            groups.add(buildCards("cards_act3_4",
                    "Card pick rate - Acts 3-4",
                    "第三、四幕卡牌选取率",
                    cs.cardPicksAct3_4,
                    cs.cardChoicesAct3_4,
                    minSample));
        }
        if (wantsCardGroup(scope, TierListScope.CARDS_AFTER_ACT1)) {
            groups.add(buildCards("cards_after_act1",
                    "Card pick rate - After Act 1",
                    "第一幕后卡牌选取率",
                    cs.cardPicksAfterAct1,
                    cs.cardChoicesAfterAct1,
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

    /** ALL 与卡牌打包页都出全套四组；单独导出某一幕时只出那一组。 */
    private static boolean wantsCardGroup(TierListScope scope, TierListScope group) {
        return scope == TierListScope.ALL
                || scope == TierListScope.CARDS_ALL_ACTS
                || scope == group;
    }

    private static TierListGroup buildCards(String key,
                                            String labelEn,
                                            String labelZh,
                                            List<Rate<Card>> rates,
                                            List<Card[]> choices,
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
        // 标准化率：按「同场三张 + 跳过」的整屏选择拟合（Plackett-Luce），挂到条目上
        CardRanker.attach(choices, entries);
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
            // 走 TierImageExporter.resolveCard：卡牌 ID 与显示名不一致 + 中文客户端下
            // AbstractCard.name 是中文，直接 CardLibrary.getCard(英文名) 必然查不到
            AbstractCard card = TierImageExporter.resolveCard(name);
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

    // ------------------------------------------------------------------
    // 排名型梯度：火堆 / 商店 / 尼奥
    //
    // 这三个维度只有「次数」或「比率」，量级差得很远（火堆头名 24.8%、商店 10.6%、尼奥 100%），
    // 用抓率那套固定阈值会全挤在一档，所以统一走 RankRule（等人数四分位，可切换）。
    // ------------------------------------------------------------------

    /** 火堆行动里的「行动类型」键；其余键都是被升级的卡。 */
    private static final Map<String, String> CAMPFIRE_ACTIONS = new LinkedHashMap<String, String>();

    /** 火堆/商店列表里由 ClassStat 合成的汇总行，不参与排名。 */
    private static final String CAMPFIRE_TOTAL_KEY = "火堆总数";
    private static final String SHOP_TOTAL_KEY = "商店总数";
    private static final String SHOP_VALID_KEY = "有效商店数";
    private static final String SHOP_PURGE_KEY = "商店删牌";

    static {
        CAMPFIRE_ACTIONS.put("SMITH", "升级");
        CAMPFIRE_ACTIONS.put("RECALL", "回忆");
        CAMPFIRE_ACTIONS.put("REST", "睡觉");
        CAMPFIRE_ACTIONS.put("PURGE", "删牌");
        CAMPFIRE_ACTIONS.put("DIG", "挖掘");
        CAMPFIRE_ACTIONS.put("LIFT", "举重");
    }

    /**
     * 火堆梯度：每幕一张「火堆行动」（升级的落点 + 睡觉/回忆/删牌/挖掘/举重），再一张「行动类型（四幕合计）」。
     *
     * SMITH 按卡牌统计，其余行动按类型统计，两类都进同一张表——它们本来就是同一个「这一次火堆我干什么」的选择集。
     * 指标 = 次数 ÷ 该幕火堆行动总数。之所以不用胜率：火堆这类条目在 ClassStat 里
     * win 记的是次数、loss 基本为 0，页面上那个 100% 不是胜率，是「次数/次数」。
     */
    private static List<TierListGroup> buildCampfire(ClassStat cs, int minSample) {
        List<TierListGroup> groups = new ArrayList<TierListGroup>(5);
        List<List<Rate<Object>>> perAct = new ArrayList<List<Rate<Object>>>(4);
        perAct.add(cs.campfireActionsAct1);
        perAct.add(cs.campfireActionsAct2);
        perAct.add(cs.campfireActionsAct3);
        perAct.add(cs.campfireActionsAct4);

        Map<String, Integer> actionCounts = new LinkedHashMap<String, Integer>();
        Map<String, Rate<Card>> act1Picks = pickRatesByName(cs.cardPicksAct1);
        Map<String, Rate<Card>> laterPicks = pickRatesByName(cs.cardPicksAfterAct1);
        int actionTotal = 0;
        for (int i = 0; i < perAct.size(); i++) {
            List<Rate<Object>> list = perAct.get(i);
            int total = totalOf(list, CAMPFIRE_TOTAL_KEY);
            Map<String, Rate<Card>> pickMap = i == 0 ? act1Picks : laterPicks;
            actionTotal += total;

            List<RankItem> items = new ArrayList<RankItem>();
            for (Rate<Object> rate : safe(list)) {
                Object what = whatOf(rate);
                if (what instanceof String) {
                    // 睡觉 / 回忆 / 删牌 / 挖掘 / 举重 也是火堆上的真实选择，必须留在每幕表里。
                    // 之前只把 Card 入表、把行动踢到「四幕合计」，等于把删牌和回忆从每幕行动里删掉了。
                    String key = (String) what;
                    if (CAMPFIRE_TOTAL_KEY.equals(key)) {
                        continue;
                    }
                    Integer old = actionCounts.get(key);
                    actionCounts.put(key, (old == null ? 0 : old) + countOf(rate));
                    String zh = CAMPFIRE_ACTIONS.get(key);
                    items.add(new RankItem(key, key, zh == null ? key : zh, "action", key,
                            total, countOf(rate)));
                    continue;
                }
                if (!(what instanceof Card)) {
                    continue;
                }
                Card card = (Card) what;
                String name = card.name;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                items.add(new RankItem(name, name, NameMapper.toChinese(name), "card", null,
                        total, countOf(rate)));
                // 升级次数受「有没有抓到这张牌」影响（神化 7 次不是它弱，是持有得少），
                // 所以把该幕的抓取次数一并带上，避免读者把持有量当成升级偏好。
                Rate<Card> pick = pickMap.get(name);
                if (pick != null && pick.total() > 0) {
                    items.get(items.size() - 1).context = String.format("抓取 %d/%d%s",
                            pick.win, pick.total(), i == 0 ? "" : "（幕后台账）");
                } else {
                    items.get(items.size() - 1).context = "抓取 0/0";
                }
            }
            String actZh = ACT_ZH[i];
            groups.add(rankGroup("campfire_act" + (i + 1),
                    "Act " + (i + 1) + " campfire actions",
                    actZh + " · 火堆行动",
                    items, minSample));
        }

        List<RankItem> actionItems = new ArrayList<RankItem>();
        for (Map.Entry<String, Integer> entry : actionCounts.entrySet()) {
            String key = entry.getKey();
            String zh = CAMPFIRE_ACTIONS.get(key);
            if (zh == null) {
                zh = key;
            }
            actionItems.add(new RankItem(key, key, zh, "action", key, actionTotal, entry.getValue()));
        }
        groups.add(rankGroup("campfire_action_types",
                "Campfire action types (all acts)",
                "行动类型（四幕合计）",
                actionItems, minSample));
        return groups;
    }

    /**
     * 火堆偏好：同一批火堆决策上的两层视图，排序用「火堆权重（W）」。
     *
     * - 卡牌层（本表排序指标）：被敲次数 ÷ 该卡出现在候选里的次数。
     *   每次锻造的分母 = 当时卡组里还有未升级副本的卡牌<b>种类</b>数（同名多张只算 1 个选项），
     *   所以它直接就是「有这些候选时，选它的概率」，可以跨幕、跨卡横向比较。
     * - A 口径（导出的独立列，不参与排序）：锻造次数 ÷ 到该幕结束累计拥过的副本数，
     *   只依赖获得记录、不受删牌影响，卡组重建失败的条目（标 *）以它为准。
     * - 备注层：被敲次数 ÷ 锻造次数 = 锻造内偏好；被敲次数 ÷ 火堆访问次数 = 占火堆，
     *   与行动占比同分母，可以直接和睡觉/回忆/抽烟比较。
     *
     * 只统计火堆锻造，不含磨刀石/战锤/课程/蛋/事件升级。已升级副本不会被再次敲，
     * 所以第二幕起同一张卡的权重自然下降，这是口径特性。
     */
    private static List<TierListGroup> buildCampfireUpgrade(ClassStat cs, int minSample) {
        int minOwned = Math.max(MIN_UPGRADE_SAMPLE, minSample);
        List<TierListGroup> groups = new ArrayList<TierListGroup>(5);
        List<List<ClassStat.CampfireShare>> perAct = new ArrayList<List<ClassStat.CampfireShare>>(4);
        perAct.add(cs.campfireChoiceDistAct1);
        perAct.add(cs.campfireChoiceDistAct2);
        perAct.add(cs.campfireChoiceDistAct3);
        perAct.add(cs.campfireChoiceDistAct4);
        // 幕顺序 1→4，合计放最后；与界面、工作簿保持一致。
        for (int i = 0; i < perAct.size(); i++) {
            groups.add(upgradePrefGroup("campfire_upgrade_act" + (i + 1),
                    "Act " + (i + 1) + " campfire preference",
                    ACT_ZH[i] + " · 火堆偏好",
                    perAct.get(i), minOwned,
                    cs.campfireSmiths[i + 1], cs.campfireVisits[i + 1],
                    sleepNote(cs, i + 1)));
        }
        groups.add(upgradePrefGroup("campfire_upgrade_total",
                "Campfire preference (all acts)",
                "四幕合计 · 火堆偏好",
                cs.campfireChoiceDistTotal, minOwned,
                cs.campfireSmiths[0], cs.campfireVisits[0],
                sleepNote(cs, 0)));
        return groups;
    }

    /** 睡觉不参与竞争榜，但它是重要信息：以组备注的形式挂在表尾。 */
    private static String sleepNote(ClassStat cs, int act) {
        List<ClassStat.CampfireOption> options;
        switch (act) {
            case 1: options = cs.campfireOptionsAct1; break;
            case 2: options = cs.campfireOptionsAct2; break;
            case 3: options = cs.campfireOptionsAct3; break;
            case 4: options = cs.campfireOptionsAct4; break;
            default: options = cs.campfireOptionsTotal; break;
        }
        if (options == null) {
            return null;
        }
        for (ClassStat.CampfireOption option : options) {
            if (option != null && "REST".equals(option.key) && option.available > 0) {
                return String.format(Locale.US, "睡觉 %.1f%% (%d/%d) · 单列：约束是血量，不进竞争榜",
                        option.percent(), option.chosen, option.available);
            }
        }
        return null;
    }

    private static String actionZh(String key) {
        if ("PURGE".equals(key)) return "抽烟";
        if ("LIFT".equals(key)) return "举重";
        if ("DIG".equals(key)) return "挖掘";
        if ("RECALL".equals(key)) return "回忆";
        return key;
    }

    /** 像素字体只支持 ASCII，行动条目用英文短标签占位。 */
    private static String actionImageText(String key) {
        if ("PURGE".equals(key)) return "TOKE";
        if ("LIFT".equals(key)) return "LIFT";
        if ("DIG".equals(key)) return "DIG";
        if ("RECALL".equals(key)) return "RECALL";
        return key;
    }

    private static TierListGroup upgradePrefGroup(String key,
                                                  String labelEn,
                                                  String labelZh,
                                                  List<ClassStat.CampfireShare> shares,
                                                  int minOwned,
                                                  int smithCount,
                                                  int visitCount,
                                                  String note) {
        List<RankItem> items = new ArrayList<RankItem>();
        List<ClassStat.CampfireShare> source = shares == null
                ? Collections.<ClassStat.CampfireShare>emptyList() : shares;
        for (ClassStat.CampfireShare share : source) {
            if (share == null || share.what == null || share.visits <= 0) {
                continue;
            }
            RankItem item;
            if (share.card) {
                Card card = (Card) share.what;
                String name = card.name;
                if (name == null || name.isEmpty()) {
                    continue;
                }
                // 分档/排序都用占比；offered = 候选次数，picked = 被敲次数。
                item = new RankItem(name, name, NameMapper.toChinese(name), "card", null,
                        share.visits, share.chosen, true);
            } else {
                String action = (String) share.what;
                item = new RankItem(action, action, actionZh(action), "action", actionImageText(action),
                        share.visits, share.chosen, true);
            }
            if (smithCount > 0) {
                item.context = String.format(Locale.US, "占锻造 %.1f%% · 占火堆 %.1f%%",
                        100.0D * share.chosen / smithCount,
                        visitCount > 0 ? 100.0D * share.chosen / visitCount : 0.0D);
            }
            items.add(item);
        }
        return rankGroup(key, labelEn, labelZh, items, minOwned, note);
    }

    /**
     * 商店梯度：每幕一张「购买商品」。
     *
     * 「商店总数 / 有效商店数 / 商店删牌」是 ClassStat 合成的汇总行，不是商品，剔除掉；
     * 分母用「该幕购买总次数」（不含删牌），所以占比读作「买的东西里 X 占多少」。
     * 删牌单独在页面/CSV 里看（它按「每次商店删一次」计，混进来会把整表压扁）。
     */
    private static List<TierListGroup> buildShop(ClassStat cs, int minSample) {
        List<TierListGroup> groups = new ArrayList<TierListGroup>(5);
        List<List<Rate<Object>>> perAct = new ArrayList<List<Rate<Object>>>(4);
        perAct.add(cs.shopActionsAct1);
        perAct.add(cs.shopActionsAct2);
        perAct.add(cs.shopActionsAct3);
        perAct.add(cs.shopActionsAct4);

        for (int i = 0; i < perAct.size(); i++) {
            List<Rate<Object>> list = perAct.get(i);
            int total = 0;
            int purgeCount = 0;
            for (Rate<Object> rate : safe(list)) {
                Object what = whatOf(rate);
                if (what instanceof String && SHOP_PURGE_KEY.equals(what)) {
                    purgeCount += countOf(rate);
                    continue;
                }
                if (what instanceof String && isShopMeta((String) what)) {
                    continue;
                }
                total += countOf(rate);
            }
            List<RankItem> items = new ArrayList<RankItem>();
            for (Rate<Object> rate : safe(list)) {
                Object what = whatOf(rate);
                if (!(what instanceof String)) {
                    continue;
                }
                String name = (String) what;
                if (isShopMeta(name)) {
                    continue;
                }
                // 商店的样本量门槛按「买过几次」算：offered 对每条都是统一的购买总数，
                // 用它过滤等于没过滤（选 4 时买过 1 次的条目照样出现）。
                int bought = countOf(rate);
                if (bought < minSample) {
                    continue;
                }
                String type = TierImageExporter.classifyShopItem(name);
                items.add(new RankItem(name, name, shopNameZh(name, type), type,
                        TierImageExporter.shopImageText(name, type), total, bought));
            }
            // 删牌并进梯度表：它和「买什么」抢的是同一笔金币，是商店里最优先的决策。
            // 分母沿用该幕商品购买总次数，所以读作「买 1 件商品的同时删了几次牌」，不会压扁其他条目。
            if (purgeCount > 0 && total > 0 && purgeCount >= minSample) {
                items.add(new RankItem(SHOP_PURGE_KEY, "Remove a card", "删牌", "purge", "PURGE",
                        total, purgeCount, true));
            }
            groups.add(rankGroup("shop_items_act" + (i + 1),
                    "Act " + (i + 1) + " purchases",
                    ACT_ZH[i] + " · 购买商品",
                    items, minSample));
        }
        groups.add(buildShopActions(cs, minSample));
        return groups;
    }

    /**
     * 商店动作构成（四幕合计）：删牌 / 买卡 / 买遗物 / 买药水。
     *
     * 删牌必须单独一行、单独成档，不能混进「购买商品」表：
     * 实测每局约 4.5 次商店、几乎每次都删 1 张（删牌 1.00 次/店），
     * 删牌次数是商店商品头名的 30 倍以上，混进同一张表会把所有商品压进 C 档。
     * 分开之后「买什么」和「做什么」是两个问题，各自才有梯度。
     */
    private static TierListGroup buildShopActions(ClassStat cs, int minSample) {
        List<List<Rate<Object>>> perAct = new ArrayList<List<Rate<Object>>>(4);
        perAct.add(cs.shopActionsAct1);
        perAct.add(cs.shopActionsAct2);
        perAct.add(cs.shopActionsAct3);
        perAct.add(cs.shopActionsAct4);

        int purge = 0;
        int card = 0;
        int relic = 0;
        int potion = 0;
        int other = 0;
        for (List<Rate<Object>> list : perAct) {
            for (Rate<Object> rate : safe(list)) {
                Object what = whatOf(rate);
                if (!(what instanceof String)) {
                    continue;
                }
                String name = (String) what;
                if (SHOP_TOTAL_KEY.equals(name) || SHOP_VALID_KEY.equals(name)) {
                    continue;
                }
                int count = countOf(rate);
                if (SHOP_PURGE_KEY.equals(name)) {
                    purge += count;
                    continue;
                }
                String type = TierImageExporter.classifyShopItem(name);
                if ("card".equals(type)) {
                    card += count;
                } else if ("relic".equals(type)) {
                    relic += count;
                } else if ("potion".equals(type)) {
                    potion += count;
                } else {
                    other += count;
                }
            }
        }
        int total = purge + card + relic + potion + other;
        List<RankItem> items = new ArrayList<RankItem>(5);
        items.add(new RankItem("shop_purge", "Purge", "删牌", "action", "PURGE", total, purge));
        items.add(new RankItem("shop_card", "Buy card", "买卡", "action", "CARD", total, card));
        items.add(new RankItem("shop_relic", "Buy relic", "买遗物", "action", "RELIC", total, relic));
        items.add(new RankItem("shop_potion", "Buy potion", "买药水", "action", "POTION", total, potion));
        if (other > 0) {
            items.add(new RankItem("shop_other", "Other", "其它", "action", "OTHER", total, other));
        }
        return rankGroup("shop_actions",
                "Shop action mix (all acts)",
                "商店动作（四幕合计）",
                items, minSample);
    }

    /**
     * 尼奥梯度：一行 = 一个「奖励 + 代价」选项，绝不按奖励合并。
     *
     * 为什么不用选取率：.run 的 neow_costs_skipped_log 里代价全是 NONE，
     * 带代价的选项收不到「被跳过」计数，按 win/(win+loss) 算会虚高到 100%。
     * 所以这里用「被选中次数」排名，分母统一取所有尼奥选项的选中总数，
     * 占比 = 该选项被选中次数 ÷ 尼奥选择总次数（不是被提供次数）。
     */
    /**
     * 尼奥开局选项：**按 (bonus, cost) 逐项统计，不同代价就是不同选项，绝不合并**。
     *
     * 分母 = `rate.total()`（该选项被选中 + 被跳过的次数），即「它出现时被选中的概率」。
     */
    private static TierListGroup buildNeow(ClassStat cs, int minSample) {
        List<RankItem> items = new ArrayList<RankItem>();
        for (Rate<Neow> rate : safe(cs.neowPickRate)) {
            Object what = whatOf(rate);
            if (!(what instanceof Neow) || rate.win <= 0) {
                continue;
            }
            Neow neow = (Neow) what;
            if (neow.bonus == null || neow.bonus.isEmpty()) {
                continue;
            }
            String cost = neow.cost == null || neow.cost.isEmpty() ? "NONE" : neow.cost;
            RankItem item = new RankItem(neow.bonus + "_" + cost,
                    neow.toString(),
                    NeowShort.zh(neow.bonus, cost),
                    "neow",
                    NeowShort.en(neow.bonus, cost),
                    rate.total(),
                    rate.win,
                    true);
            items.add(item);
        }
        TierListGroup group = rankGroup("neow_options",
                "Neow options (bonus + cost)",
                "尼奥开局选项（奖励 + 代价）",
                items, minSample);
        // 把 Plackett-Luce 标准化率与 95%CI 挂到条目上，CSV/JSON/图片/HTML 都能读到
        Map<String, NeowRanker.Row> ranked = new LinkedHashMap<String, NeowRanker.Row>();
        for (NeowRanker.Row row : NeowRanker.rank(cs, minSample)) {
            ranked.put(row.neow.bonus + "_" + row.neow.cost, row);
        }
        for (TierEntry entry : group.entries) {
            NeowRanker.Row row = ranked.get(entry.id);
            if (row != null) {
                entry.standardized = row.standardized;
                entry.stdLow = row.standardizedLow;
                entry.stdHigh = row.standardizedHigh;
                entry.tieGroup = row.tieGroup;
                entry.tied = row.tied();
                entry.rankOne = row.rankOne;
                // CSV 没有独立列，把标准化率写进备注，保证任何导出都能看到
                entry.context = String.format(java.util.Locale.US,
                        "标准化 %.2f%% (95%%CI %.2f–%.2f)%s",
                        row.standardized, row.standardizedLow, row.standardizedHigh,
                        row.tied() ? " 并列" : "");
            }
        }
        return group;
    }

    /**
     * 把一个排名型条目列表变成分组：过滤最小样本 → 按指标降序 → RankRule 分档 → 生成 TierEntry。
     *
     * 注意 TierEntry.offered 在这儿是「分母」（该幕总数 / 提供次数），不是「提供次数」，
     * CSV/HTML 的表头会按 scope 换成「分母/次数/占比」。
     */
    private static boolean isShopMeta(String name) {
        return SHOP_TOTAL_KEY.equals(name) || SHOP_VALID_KEY.equals(name) || SHOP_PURGE_KEY.equals(name);
    }

    /** 商店条目的中文名：卡牌走本地汉化包，遗物/药水走游戏语言包，查不到回落映射表。 */
    private static String shopNameZh(String name, String type) {
        if ("card".equals(type)) {
            return NameMapper.toChinese(name);
        }
        try {
            if ("relic".equals(type)) {
                com.megacrit.cardcrawl.relics.AbstractRelic relic =
                        com.megacrit.cardcrawl.helpers.RelicLibrary.getRelic(name);
                // 排除 Circlet 兜底：查不到的 key 会返回「头环」
                if (relic != null && !"Circlet".equals(relic.relicId)
                        && relic.name != null && !relic.name.isEmpty()) {
                    return relic.name;
                }
            } else if ("potion".equals(type)) {
                com.megacrit.cardcrawl.potions.AbstractPotion potion =
                        com.megacrit.cardcrawl.helpers.PotionHelper.getPotion(name);
                if (potion != null && potion.name != null && !potion.name.isEmpty()) {
                    return potion.name;
                }
            }
        } catch (Throwable ignored) {
            // 游戏还没初始化完 / 查不到就走映射表
        }
        return NameMapper.toChinese(name);
    }

    private static <T> List<Rate<T>> safe(List<Rate<T>> list) {
        return list == null ? java.util.Collections.<Rate<T>>emptyList() : list;
    }

    private static Object whatOf(Rate<?> rate) {
        return rate == null ? null : rate.what;
    }

    private static int countOf(Rate<?> rate) {
        return rate == null ? 0 : rate.win;
    }

    private static int totalOf(List<Rate<Object>> list, String key) {
        for (Rate<Object> rate : safe(list)) {
            if (rate != null && key.equals(rate.what)) {
                return rate.total();
            }
        }
        return 0;
    }

    private static Map<String, Rate<Card>> pickRatesByName(List<Rate<Card>> rates) {
        Map<String, Rate<Card>> map = new LinkedHashMap<String, Rate<Card>>();
        for (Rate<Card> rate : safe(rates)) {
            Object what = whatOf(rate);
            if (what instanceof Card && ((Card) what).name != null) {
                map.put(((Card) what).name, rate);
            }
        }
        return map;
    }

    /** 排序/分档的中间结构。metric 默认取原始次数，byRate=true 时取百分比。 */
    private static final class RankItem {
        final String id;
        final String nameEn;
        final String nameZh;
        final String type;
        final String imageText;
        final int offered;
        final int picked;
        final double metric;
        String context = "";

        RankItem(String id, String nameEn, String nameZh, String type, String imageText,
                 int offered, int picked) {
            this(id, nameEn, nameZh, type, imageText, offered, picked, (double) picked);
        }

        RankItem(String id, String nameEn, String nameZh, String type, String imageText,
                 int offered, int picked, boolean byRate) {
            this(id, nameEn, nameZh, type, imageText, offered, picked,
                    offered > 0 && byRate ? 100.0D * picked / offered : (double) picked);
        }

        RankItem(String id, String nameEn, String nameZh, String type, String imageText,
                 int offered, int picked, double metric) {
            this.id = id;
            this.nameEn = nameEn;
            this.nameZh = nameZh;
            this.type = type;
            this.imageText = imageText;
            this.offered = offered;
            this.picked = picked;
            this.metric = metric;
        }
    }

    private static TierListGroup rankGroup(String key,
                                          String labelEn,
                                          String labelZh,
                                          List<RankItem> items,
                                          int minSample) {
        return rankGroup(key, labelEn, labelZh, items, minSample, null);
    }

    private static TierListGroup rankGroup(String key,
                                          String labelEn,
                                          String labelZh,
                                          List<RankItem> items,
                                          int minSample,
                                          String note) {
        List<RankItem> kept = new ArrayList<RankItem>(items.size());
        for (RankItem item : items) {
            if (item == null || item.offered <= 0 || item.offered < minSample) {
                continue;
            }
            kept.add(item);
        }
        Collections.sort(kept, new Comparator<RankItem>() {
            @Override
            public int compare(RankItem a, RankItem b) {
                int metricCompare = Double.compare(b.metric, a.metric);
                if (metricCompare != 0) {
                    return metricCompare;
                }
                int offeredCompare = Integer.compare(b.offered, a.offered);
                if (offeredCompare != 0) {
                    return offeredCompare;
                }
                return a.nameEn.compareToIgnoreCase(b.nameEn);
            }
        });

        List<Double> metrics = new ArrayList<Double>(kept.size());
        for (RankItem item : kept) {
            metrics.add(item.metric);
        }
        List<Tier> tiers = RankRule.assign(metrics, RankRule.ACTIVE);

        List<TierEntry> entries = new ArrayList<TierEntry>(kept.size());
        for (int i = 0; i < kept.size(); i++) {
            RankItem item = kept.get(i);
            double rate = item.offered > 0 ? 100.0D * item.picked / item.offered : 0.0D;
            TierEntry entry = new TierEntry(item.id, item.nameEn, item.nameZh,
                    item.offered, item.picked, rate, tiers.get(i), item.type);
            entry.imageText = item.imageText;
            entry.context = item.context;
            entries.add(entry);
        }
        return new TierListGroup(key, labelEn, labelZh, entries);
    }

    private static final String[] ACT_ZH = {"第一幕", "第二幕", "第三幕", "第四幕"};

    private static String dateAt(List<YearMonth> dates, int index) {        if (dates == null || dates.isEmpty()) {
            return "";
        }
        if (index < 0 || index >= dates.size()) {
            index = 0;
        }
        return dates.get(index).toString();
    }
}
