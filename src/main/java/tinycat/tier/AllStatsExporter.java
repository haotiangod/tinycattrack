package tinycat.tier;

import tinycat.NameMapper;
import tinycat.stats.BossRelic;
import tinycat.stats.Card;
import tinycat.stats.ClassStat;
import tinycat.stats.Mean;
import tinycat.stats.Neow;
import tinycat.stats.Rate;
import tinycat.ui.MoreStatsScreen;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 按统计界面分页导出中文 Excel。
 *
 * 文件结构：
 * - 总体统计.xlsx：全角色基础、战斗与收益、删牌、当前筛选汇总、Meta 成长
 * - 第一幕卡牌选取率.xlsx / 第一幕后卡牌选取率.xlsx
 * - 卡组中的卡牌胜率.xlsx
 * - 尼奥奖励.xlsx：尼奥胜率、尼奥选取率
 * - BOSS遗物.xlsx：第一/二幕选取率、第一/二幕胜率、交换遗物胜率
 * - 遭遇战伤害.xlsx：战损、受到伤害、有无回复
 * - 遭遇战死亡率与药水.xlsx：死亡率、药水使用
 * - 遗物胜率.xlsx：遗物胜率、购买遗物胜率
 * - 各幕事件胜率.xlsx：第一/二/三幕
 * - 火堆行动.xlsx：第一/二/三/四幕
 * - 商店行动.xlsx：第一/二/三/四幕
 *
 * 不引入 Apache POI：使用项目内的 XlsxWriter 生成最小 OOXML 包。
 * 表头、标签和说明默认中文；卡牌/遗物/事件保留英文名列便于查对。
 */
public final class AllStatsExporter {
    private static final String[] CHARACTER_ZH = {"全角色汇总", "战士", "猎人", "机器人", "观者"};
    private static final String[] CHARACTER_ID = {"ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"};
    private static final String[] ACT_ZH = {"第一幕", "第二幕", "第三幕", "第四幕"};

    private static final Set<String> SUMMARY_NAMES = new HashSet<String>();
    private static final Set<String> DAMAGE_TOTAL_NAMES = new HashSet<String>();
    private static final Map<String, String> CAMPFIRE_ACTIONS = new HashMap<String, String>();
    private static final Map<String, String> NEOW_BONUS_ZH = new HashMap<String, String>();
    private static final Map<String, String> NEOW_COST_ZH = new HashMap<String, String>();

    static {
        SUMMARY_NAMES.add("战斗总计");
        SUMMARY_NAMES.add("非Boss战斗总计");
        SUMMARY_NAMES.add("事件总计");
        SUMMARY_NAMES.add("每幕总计");
        SUMMARY_NAMES.add("火堆总数");
        SUMMARY_NAMES.add("商店总数");
        SUMMARY_NAMES.add("有效商店数");
        SUMMARY_NAMES.add("商店删牌");
        SUMMARY_NAMES.add("总遭遇");
        SUMMARY_NAMES.add("总事件");
        SUMMARY_NAMES.add("问号节点");

        DAMAGE_TOTAL_NAMES.add("战斗总计");
        DAMAGE_TOTAL_NAMES.add("非Boss战斗总计");

        CAMPFIRE_ACTIONS.put("SMITH", "升级");
        CAMPFIRE_ACTIONS.put("RECALL", "回忆");
        CAMPFIRE_ACTIONS.put("REST", "睡觉");
        CAMPFIRE_ACTIONS.put("PURGE", "删牌");
        CAMPFIRE_ACTIONS.put("DIG", "挖掘");
        CAMPFIRE_ACTIONS.put("LIFT", "举重");

        NEOW_BONUS_ZH.put("THREE_CARDS", "从三张卡牌中选择一张。");
        NEOW_BONUS_ZH.put("RANDOM_COLORLESS", "选择一张无色罕见卡牌获得。");
        NEOW_BONUS_ZH.put("RANDOM_COMMON_RELIC", "获得一个随机普通遗物。");
        NEOW_BONUS_ZH.put("REMOVE_CARD", "移除一张卡牌。");
        NEOW_BONUS_ZH.put("TRANSFORM_CARD", "变化一张卡牌。");
        NEOW_BONUS_ZH.put("UPGRADE_CARD", "升级一张卡牌。");
        NEOW_BONUS_ZH.put("THREE_ENEMY_KILL", "前三场战斗中的敌人只有 1 点生命。");
        NEOW_BONUS_ZH.put("THREE_SMALL_POTIONS", "获得 3 瓶随机药水。");
        NEOW_BONUS_ZH.put("TEN_PERCENT_HP_BONUS", "获得 10% 最大生命值。");
        NEOW_BONUS_ZH.put("ONE_RANDOM_RARE_CARD", "获得一张随机稀有卡牌。");
        NEOW_BONUS_ZH.put("HUNDRED_GOLD", "获得 100 金币。");
        NEOW_BONUS_ZH.put("TWO_FIFTY_GOLD", "获得 250 金币。");
        NEOW_BONUS_ZH.put("TWENTY_PERCENT_HP_BONUS", "获得 20% 最大生命值。");
        NEOW_BONUS_ZH.put("RANDOM_COLORLESS_2", "选择一张稀有无色卡牌获得。");
        NEOW_BONUS_ZH.put("THREE_RARE_CARDS", "从三张稀有卡牌中选择一张。");
        NEOW_BONUS_ZH.put("REMOVE_TWO", "移除两张卡牌。");
        NEOW_BONUS_ZH.put("TRANSFORM_TWO_CARDS", "变化两张卡牌。");
        NEOW_BONUS_ZH.put("ONE_RARE_RELIC", "获得一个随机稀有遗物。");
        NEOW_BONUS_ZH.put("BOSS_RELIC", "失去初始遗物，获得一个随机 Boss 遗物。");
        NEOW_BONUS_ZH.put("RANDOM_COLORLESS_2_ACTUAL", "选择一张稀有无色卡牌获得。");

        NEOW_COST_ZH.put("CURSE", "获得一个诅咒。");
        NEOW_COST_ZH.put("NO_GOLD", "失去所有金币。");
        NEOW_COST_ZH.put("TEN_PERCENT_HP_LOSS", "失去 10% 最大生命值。");
        NEOW_COST_ZH.put("PERCENT_DAMAGE", "受到伤害。");
        NEOW_COST_ZH.put("TWO_STARTER_CARDS", "增加两张初始卡牌。");
        NEOW_COST_ZH.put("ONE_STARTER_CARD", "增加一张初始卡牌。");
    }

    private AllStatsExporter() {
    }

    public static void write(MoreStatsScreen screen, TierListModel model, File root) throws IOException {
        write(screen, model, root, TierListScope.ALL);
    }

    public static void write(MoreStatsScreen screen, TierListModel model, File root, TierListScope scope) throws IOException {
        ClassStat cs = screen.getClassStat();
        TierListScope actual = scope == null ? TierListScope.ALL : scope;
        switch (actual) {
            case CARDS_ACT1:
                writeCardPick(model, cs.cardPicksAct1, true, root);
                break;
            case CARDS_AFTER_ACT1:
                writeCardPick(model, cs.cardPicksAfterAct1, false, root);
                break;
            case BOSS_RELICS:
                writeBossRelics(model, cs, root);
                break;
            case ALL:
            default:
                writeOverview(screen, model, root);
                writeCardPick(model, cs.cardPicksAct1, true, root);
                writeCardPick(model, cs.cardPicksAfterAct1, false, root);
                writeCardWinRate(model, cs, root);
                writeNeow(model, cs, root);
                writeBossRelics(model, cs, root);
                writeDamage(model, cs, root);
                writeMortalityAndPotions(model, cs, root);
                writeRelics(model, cs, root);
                writeEvents(model, cs, root);
                writeCampfire(model, cs, root);
                writeShop(model, cs, root);
                break;
        }
    }

    private static void writeOverview(MoreStatsScreen screen, TierListModel model, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();

        XlsxWriter.Sheet basics = newSheet(wb, "全角色基础", "总体统计 · 全角色基础",
                filterContext(model, "对象：全角色汇总 + 四个基础角色；ALL 不是第 5 个角色"),
                "角色", "角色ID", "类型", "对局数", "胜场", "败场", "胜率", "总游玩时长",
                "最快胜利", "平均胜利用时", "平均卡组", "最小卡组", "最大卡组",
                "第一幕存活率", "第二幕存活率", "第三幕存活率", "第四幕存活率", "最高连胜");
        ClassStat[] ordered = orderedStats(screen);
        for (int i = 0; i < ordered.length; i++) {
            ClassStat st = ordered[i];
            if (st == null) {
                continue;
            }
            int runs = st.numVictory + st.numDeath;
            basics.data(
                    XlsxWriter.Cell.text(CHARACTER_ZH[i]),
                    XlsxWriter.Cell.text(CHARACTER_ID[i]),
                    XlsxWriter.Cell.text(i == 0 ? "汇总" : "单角色"),
                    XlsxWriter.Cell.integer(runs),
                    XlsxWriter.Cell.integer(st.numVictory),
                    XlsxWriter.Cell.integer(st.numDeath),
                    XlsxWriter.Cell.percent(ratio(st.numVictory, runs)),
                    XlsxWriter.Cell.text(duration(st.playTime)),
                    XlsxWriter.Cell.text(st.fastestTime == Integer.MAX_VALUE ? "—" : duration(st.fastestTime)),
                    XlsxWriter.Cell.text(meanDuration(st.averageWinningTime)),
                    XlsxWriter.Cell.decimal1(st.averageDeckSize == null ? 0 : st.averageDeckSize.mean()),
                    XlsxWriter.Cell.integer(st.minDeckSize),
                    XlsxWriter.Cell.integer(st.maxDeckSize),
                    XlsxWriter.Cell.percent(survival(st, 1)),
                    XlsxWriter.Cell.percent(survival(st, 2)),
                    XlsxWriter.Cell.percent(survival(st, 3)),
                    XlsxWriter.Cell.percent(survival(st, 4)),
                    XlsxWriter.Cell.integer(st.bestWinStreak));
        }

        XlsxWriter.Sheet combat = newSheet(wb, "战斗与收益", "总体统计 · 战斗与收益",
                filterContext(model, "金币为累计获得金币；无 .tinycat 追踪时按 gold_per_floor 估算；.run 只记录最终剩余金币"),
                "角色", "角色ID", "总层数", "Boss击杀", "敌人击杀", "最高分", "平均分",
                "平均累计金币", "金币精确局数", "金币估算局数", "总问号节点", "总事件");
        for (int i = 0; i < ordered.length; i++) {
            ClassStat st = ordered[i];
            if (st == null) {
                continue;
            }
            combat.data(
                    XlsxWriter.Cell.text(CHARACTER_ZH[i]),
                    XlsxWriter.Cell.text(CHARACTER_ID[i]),
                    XlsxWriter.Cell.integer(st.totalFloorsClimbed),
                    XlsxWriter.Cell.integer(st.bossKilled),
                    XlsxWriter.Cell.integer(st.enemyKilled),
                    XlsxWriter.Cell.integer(st.highestScore),
                    XlsxWriter.Cell.decimal1(st.averageScore == null ? 0 : st.averageScore.mean()),
                    XlsxWriter.Cell.decimal1(st.averageGold == null ? 0 : st.averageGold.mean()),
                    XlsxWriter.Cell.integer(st.goldExactRuns),
                    XlsxWriter.Cell.integer(st.goldEstimatedRuns),
                    XlsxWriter.Cell.integer(st.totalQuestionMarks),
                    XlsxWriter.Cell.integer(st.totalEvents));
        }

        XlsxWriter.Sheet purge = newSheet(wb, "删牌统计", "总体统计 · 删牌统计",
                filterContext(model, "删牌总数 = 商店 + 事件 + 尼奥斯 + 篝火 + 空鸟笼"),
                "角色", "角色ID", "总删牌数", "每局平均删牌", "商店删牌", "每局平均商店删牌",
                "事件删牌", "每局平均事件删牌", "篝火删牌", "尼奥斯删牌", "空鸟笼删牌",
                "第一幕删牌", "第二幕删牌", "第三幕删牌", "第四幕删牌", "未定幕删牌");
        for (int i = 0; i < ordered.length; i++) {
            ClassStat st = ordered[i];
            if (st == null) {
                continue;
            }
            int runs = st.numVictory + st.numDeath;
            purge.data(
                    XlsxWriter.Cell.text(CHARACTER_ZH[i]),
                    XlsxWriter.Cell.text(CHARACTER_ID[i]),
                    XlsxWriter.Cell.integer(st.totalCardsPurged),
                    XlsxWriter.Cell.decimal(avg(st.totalCardsPurged, runs)),
                    XlsxWriter.Cell.integer(st.shopPurges),
                    XlsxWriter.Cell.decimal(avg(st.shopPurges, runs)),
                    XlsxWriter.Cell.integer(st.eventPurges),
                    XlsxWriter.Cell.decimal(avg(st.eventPurges, runs)),
                    XlsxWriter.Cell.integer(st.campfirePurges),
                    XlsxWriter.Cell.integer(st.neowPurges),
                    XlsxWriter.Cell.integer(st.emptyCagePurges),
                    XlsxWriter.Cell.integer(purgeAct(st, 1)),
                    XlsxWriter.Cell.integer(purgeAct(st, 2)),
                    XlsxWriter.Cell.integer(purgeAct(st, 3)),
                    XlsxWriter.Cell.integer(purgeAct(st, 4)),
                    XlsxWriter.Cell.integer(purgeAct(st, 0)));
        }

        writeCurrentSummary(wb, model, screen.getClassStat());
        writeMeta(wb, model, screen.getClassStat());

        wb.write(new File(root, "总体统计.xlsx"));
    }

    private static void writeCurrentSummary(XlsxWriter wb, TierListModel model, ClassStat cs) {
        XlsxWriter.Sheet sheet = newSheet(wb, "当前筛选汇总", "总体统计 · 当前筛选汇总",
                filterContext(model, "当前选择：" + model.characterZh + "；数值口径与统计界面一致"),
                "指标", "数值", "说明");
        sheet.width(0, 22.0D).width(1, 24.0D).width(2, 60.0D);

        int runs = cs.numVictory + cs.numDeath;
        summaryLine(sheet, "导出时间", XlsxWriter.Cell.text(model.generatedAt), "");
        summaryLine(sheet, "导出格式", XlsxWriter.Cell.text("Excel (.xlsx)，中文表头"), "");
        summaryLine(sheet, "统计范围", XlsxWriter.Cell.text(model.scope == null ? "全部统计" : model.scope.labelZh), "按统计界面分页导出");
        summaryLine(sheet, "角色", XlsxWriter.Cell.text(model.characterZh), "");
        summaryLine(sheet, "角色ID", XlsxWriter.Cell.text(model.characterId), "");
        summaryLine(sheet, "角色类型", XlsxWriter.Cell.text(model.isAggregate()
                ? "全角色汇总（不是第 5 个角色）" : "单角色"), "");
        summaryLine(sheet, "基础角色数", XlsxWriter.Cell.integer(4), "基础角色 4 个；ALL 是汇总");
        summaryLine(sheet, "开始日期", XlsxWriter.Cell.text(empty(model.startDate)), "");
        summaryLine(sheet, "结束日期", XlsxWriter.Cell.text(empty(model.endDate)), "");
        summaryLine(sheet, "包含放弃局", XlsxWriter.Cell.text(model.includeAbandons ? "是" : "否"), "");
        summaryLine(sheet, "种子局筛选", XlsxWriter.Cell.text(model.seedFilter.labelZh), "");
        summaryLine(sheet, "最小样本", XlsxWriter.Cell.integer(model.minSample), "");
        summaryLine(sheet, "对局数", XlsxWriter.Cell.integer(runs), "");
        summaryLine(sheet, "胜利", XlsxWriter.Cell.integer(cs.numVictory), "");
        summaryLine(sheet, "失败", XlsxWriter.Cell.integer(cs.numDeath), "");
        summaryLine(sheet, "胜率", XlsxWriter.Cell.percent(ratio(cs.numVictory, runs)), "胜利 ÷ 对局数");
        summaryLine(sheet, "总游玩时长", XlsxWriter.Cell.text(duration(cs.playTime)), "");
        summaryLine(sheet, "最快胜利", XlsxWriter.Cell.text(cs.fastestTime == Integer.MAX_VALUE ? "—" : duration(cs.fastestTime)), "");
        summaryLine(sheet, "最高连胜", XlsxWriter.Cell.integer(cs.bestWinStreak), "");
        summaryLine(sheet, "最高分", XlsxWriter.Cell.integer(cs.highestScore), "");
        summaryLine(sheet, "总层数", XlsxWriter.Cell.integer(cs.totalFloorsClimbed), "");
        summaryLine(sheet, "Boss击杀", XlsxWriter.Cell.integer(cs.bossKilled), "");
        summaryLine(sheet, "敌人击杀", XlsxWriter.Cell.integer(cs.enemyKilled), "");
        summaryLine(sheet, "平均分", XlsxWriter.Cell.decimal1(cs.averageScore == null ? 0 : cs.averageScore.mean()), "");
        summaryLine(sheet, "平均累计金币",
                XlsxWriter.Cell.decimal1(cs.averageGold == null ? 0 : cs.averageGold.mean()),
                "累计获得金币；无追踪状态文件时按 gold_per_floor 估算；.run 只记录最终剩余金币");
        summaryLine(sheet, "金币精确局数", XlsxWriter.Cell.integer(cs.goldExactRuns),
                "由 TinyCatTrackerTest 写入的 .tinycat 提供精确累计金币");
        summaryLine(sheet, "金币估算局数", XlsxWriter.Cell.integer(cs.goldEstimatedRuns),
                "没有精确状态文件，按 gold_per_floor 正增量估算");
        summaryLine(sheet, "平均卡组", XlsxWriter.Cell.decimal1(cs.averageDeckSize == null ? 0 : cs.averageDeckSize.mean()), "");
        summaryLine(sheet, "最小卡组", XlsxWriter.Cell.integer(cs.minDeckSize), "");
        summaryLine(sheet, "最大卡组", XlsxWriter.Cell.integer(cs.maxDeckSize), "");
        summaryLine(sheet, "平均胜利用时", XlsxWriter.Cell.text(meanDuration(cs.averageWinningTime)), "");
        summaryLine(sheet, "总删牌数", XlsxWriter.Cell.integer(cs.totalCardsPurged),
                "商店 + 事件 + 尼奥斯 + 篝火 + 空鸟笼");
        summaryLine(sheet, "商店删牌", XlsxWriter.Cell.integer(cs.shopPurges), "");
        summaryLine(sheet, "事件删牌", XlsxWriter.Cell.integer(cs.eventPurges), "");
        summaryLine(sheet, "尼奥斯删牌", XlsxWriter.Cell.integer(cs.neowPurges), "");
        summaryLine(sheet, "篝火删牌", XlsxWriter.Cell.integer(cs.campfirePurges), "");
        summaryLine(sheet, "空鸟笼删牌", XlsxWriter.Cell.integer(cs.emptyCagePurges), "");
        summaryLine(sheet, "总问号节点", XlsxWriter.Cell.integer(cs.totalQuestionMarks), "");
        summaryLine(sheet, "总事件", XlsxWriter.Cell.integer(cs.totalEvents), "");
        summaryLine(sheet, "首层战损估算局数", XlsxWriter.Cell.integer(cs.floor0HPEstimatedRuns),
                "没有 .tinycat 追踪时按第 1 层伤害估算（战士 -6）");
    }

    private static void writeMeta(XlsxWriter wb, TierListModel model, ClassStat cs) {
        XlsxWriter.Sheet sheet = newSheet(wb, "Meta成长", "总体统计 · Meta 成长",
                filterContext(model, "取当前筛选下各对局的最大值；最大金币为累计获得金币的估算/追踪值"),
                "指标", "数值", "说明");
        sheet.width(0, 22.0D).width(1, 14.0D).width(2, 48.0D);
        if (cs.metaScaling == null) {
            noData(sheet);
            return;
        }
        metaLine(sheet, "最大遗物数", cs.metaScaling.maxRelic, "");
        metaLine(sheet, "最大累计金币", cs.metaScaling.maxGold, "无 .tinycat 时按 gold_per_floor 估算");
        metaLine(sheet, "最多删牌数", cs.metaScaling.maxRemoves, "");
        metaLine(sheet, "仪式匕首最大成长", cs.metaScaling.maxDagger, "");
        metaLine(sheet, "最大生命值", cs.metaScaling.maxHP, "");
        metaLine(sheet, "灼热攻击最大强化", cs.metaScaling.maxSearingBlow, "");
        metaLine(sheet, "最多生成药水", cs.metaScaling.maxAlch, "");
        metaLine(sheet, "遗传算法最大成长", cs.metaScaling.maxAlgo, "");
        metaLine(sheet, "学习课最大成长", cs.metaScaling.maxLesson, "");
    }

    private static void writeCardPick(TierListModel model, List<Rate<Card>> rates, boolean act1, File root) throws IOException {
        String name = act1 ? "第一幕卡牌选取率" : "第一幕后卡牌选取率";
        XlsxWriter wb = new XlsxWriter();
        XlsxWriter.Sheet sheet = newSheet(wb, name, name,
                pageContext(model, act1 ? "范围：第一幕（floor < 17）" : "范围：第一幕后（floor ≥ 17）"),
                "排名", "中文名", "英文名", "选取次数", "跳过次数", "提供次数", "选取率");
        int count = writePickRows(sheet, rates, 200, model.minSample);
        if (count == 0) {
            noData(sheet);
        }
        wb.write(new File(root, name + ".xlsx"));
    }

    private static void writeCardWinRate(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        XlsxWriter.Sheet sheet = newSheet(wb, "卡组中的卡牌胜率", "卡组中的卡牌胜率（前 200）",
                pageContext(model, "口径：对局结束时卡组中拥有该卡牌的对局胜率"),
                "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
        int count = writeWinRows(sheet, cs.cardWinRate, 200, model.minSample);
        if (count == 0) {
            noData(sheet);
        }
        wb.write(new File(root, "卡组中的卡牌胜率.xlsx"));
    }

    private static void writeNeow(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String context = pageContext(model, "口径：胜率为选择该尼奥奖励的对局胜率；选取率为选取 ÷ 提供");
        XlsxWriter.Sheet win = newSheet(wb, "尼奥胜率", "尼奥奖励 · 胜率", context,
                "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
        int winCount = writeWinRows(win, cs.neowWinRate, 0, model.minSample);
        if (winCount == 0) {
            noData(win);
        }

        XlsxWriter.Sheet pick = newSheet(wb, "尼奥选取率", "尼奥奖励 · 选取率", context,
                "排名", "中文名", "英文名", "选取次数", "跳过次数", "提供次数", "选取率");
        int pickCount = writePickRows(pick, cs.neowPickRate, 0, model.minSample);
        if (pickCount == 0) {
            noData(pick);
        }
        wb.write(new File(root, "尼奥奖励.xlsx"));
    }

    private static void writeBossRelics(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String context = pageContext(model, "口径：选取率 = 选取 ÷ 提供；胜率按选到该遗物的对局统计；SKIP = 跳过");
        String[] pickSheets = {"第一幕选取率", "第二幕选取率"};
        List<List<Rate<BossRelic>>> pickLists = new ArrayList<List<Rate<BossRelic>>>();
        pickLists.add(cs.bossRelicPickRateAct1);
        pickLists.add(cs.bossRelicPickRateAct2);
        for (int i = 0; i < pickSheets.length; i++) {
            XlsxWriter.Sheet sheet = newSheet(wb, pickSheets[i], "BOSS 遗物 · " + pickSheets[i], context,
                    "排名", "中文名", "英文名", "选取次数", "跳过次数", "提供次数", "选取率");
            int count = writePickRows(sheet, pickLists.get(i), 0, model.minSample);
            if (count == 0) {
                noData(sheet);
            }
        }

        String[] winSheets = {"第一幕胜率", "第二幕胜率", "交换遗物胜率"};
        List<List<Rate<BossRelic>>> winLists = new ArrayList<List<Rate<BossRelic>>>();
        winLists.add(cs.bossRelicWinRateAct1);
        winLists.add(cs.bossRelicWinRateAct2);
        winLists.add(cs.bossRelicWinRateSwap);
        for (int i = 0; i < winSheets.length; i++) {
            String extra = i == 2 ? "交换遗物：Neow 开局交换 Boss 遗物后的对局胜率" : "按选到该 Boss 遗物的对局统计";
            XlsxWriter.Sheet sheet = newSheet(wb, winSheets[i], "BOSS 遗物 · " + winSheets[i],
                    pageContext(model, extra),
                    "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
            int count = writeWinRows(sheet, winLists.get(i), 0, model.minSample);
            if (count == 0) {
                noData(sheet);
            }
        }
        wb.write(new File(root, "BOSS遗物.xlsx"));
    }

    private static void writeDamage(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String context = pageContext(model,
                "口径：战损 = 本层结束 HP − 本层开始 HP（负数表示回血）；受到伤害 = 战斗内总伤害。"
                        + "第 1 层无追踪状态文件时按伤害估算（战士 -6）；已排除宝箱层与 Boss 后奖励层");

        XlsxWriter.Sheet loss = newSheet(wb, "战损", "遭遇战平均战损（实际 HP 变化）", context,
                "幕", "排名", "名称", "英文名", "平均战损", "样本数", "备注");
        loss.width(6, 36.0D);
        int lossCount = writeMeanRows(loss, cs.averageActualHPLoss, model.minSample,
                "该遭遇的平均实际 HP 变化");
        if (lossCount == 0) {
            noData(loss);
        }

        XlsxWriter.Sheet damage = newSheet(wb, "受到伤害", "遭遇战平均受到伤害（战斗内伤害）", context,
                "幕", "排名", "名称", "英文名", "平均受到伤害", "样本数", "备注");
        damage.width(6, 36.0D);
        int damageCount = writeMeanRows(damage, cs.averageDamageTaken, model.minSample,
                "战斗内造成的总伤害，不扣除回血");
        if (damageCount == 0) {
            noData(damage);
        }

        XlsxWriter.Sheet split = newSheet(wb, "有无回复", "遭遇战战损分类（无回复 / 有回复）", context,
                "幕", "排名", "名称", "英文名",
                "无回复平均战损", "无回复样本", "有回复平均战损", "有回复样本", "备注");
        split.width(8, 38.0D);
        int splitCount = writeSplitRows(split, cs.averageNoHealActualHPLoss, cs.averageHealActualHPLoss,
                model.minSample);
        if (splitCount == 0) {
            noData(split);
        }
        wb.write(new File(root, "遭遇战伤害.xlsx"));
    }

    private static void writeMortalityAndPotions(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String context = pageContext(model,
                "口径：死亡率 = 该敌人导致的死亡局数 ÷ 遭遇次数；总遭遇行为该幕汇总；问号节点只统计数量");
        XlsxWriter.Sheet mortality = newSheet(wb, "死亡率", "遭遇战死亡率", context,
                "幕", "排名", "名称", "英文名", "死亡次数", "遭遇次数", "死亡率", "备注");
        mortality.width(7, 38.0D);
        int count = writeMortalityRows(mortality, cs, model.minSample);
        if (count == 0) {
            noData(mortality);
        }

        XlsxWriter.Sheet potions = newSheet(wb, "药水使用", "遭遇战平均药水使用", context,
                "幕", "排名", "名称", "英文名", "平均药水使用", "样本数", "备注");
        potions.width(6, 36.0D);
        int potionCount = writeMeanRows(potions, cs.averagePotionUse, model.minSample,
                "该遭遇中平均使用的药水数量");
        if (potionCount == 0) {
            noData(potions);
        }
        wb.write(new File(root, "遭遇战死亡率与药水.xlsx"));
    }

    private static void writeRelics(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        XlsxWriter.Sheet owned = newSheet(wb, "遗物胜率", "遗物胜率",
                pageContext(model, "口径：对局结束时卡组/遗物栏拥有该遗物的对局胜率"),
                "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
        int ownedCount = writeWinRows(owned, cs.relicWinRate, 0, model.minSample);
        if (ownedCount == 0) {
            noData(owned);
        }

        XlsxWriter.Sheet purchased = newSheet(wb, "购买遗物胜率", "购买遗物胜率",
                pageContext(model, "口径：在商店购买过该遗物的对局胜率"),
                "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
        int purchasedCount = writeWinRows(purchased, cs.relicPurchasedWinRate, 0, model.minSample);
        if (purchasedCount == 0) {
            noData(purchased);
        }
        wb.write(new File(root, "遗物胜率.xlsx"));
    }

    private static void writeEvents(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        List<List<Rate<String>>> lists = new ArrayList<List<Rate<String>>>();
        lists.add(cs.eventWinRateAct1);
        lists.add(cs.eventWinRateAct2);
        lists.add(cs.eventWinRateAct3);
        for (int act = 1; act <= 3; act++) {
            String sheetName = ACT_ZH[act - 1];
            XlsxWriter.Sheet sheet = newSheet(wb, sheetName, ACT_ZH[act - 1] + "事件胜率",
                    pageContext(model, "口径：事件出现时该对局最终是否通关；问号节点只统计数量"),
                    "排名", "中文名", "英文名", "胜场", "败场", "遭遇次数", "胜率", "备注");
            sheet.width(7, 40.0D);
            int count = writeEventRows(sheet, lists.get(act - 1), cs.questionMarksByAct, act, model.minSample);
            if (count == 0) {
                noData(sheet);
            }
        }
        wb.write(new File(root, "各幕事件胜率.xlsx"));
    }

    private static void writeCampfire(TierListModel model, ClassStat cs, File root) throws IOException {
        List<List<Rate<Object>>> lists = new ArrayList<List<Rate<Object>>>();
        lists.add(cs.campfireActionsAct1);
        lists.add(cs.campfireActionsAct2);
        lists.add(cs.campfireActionsAct3);
        lists.add(cs.campfireActionsAct4);
        writeActionWorkbook(model, root, "火堆行动", lists, 35, true);
    }

    private static void writeShop(TierListModel model, ClassStat cs, File root) throws IOException {
        List<List<Rate<Object>>> lists = new ArrayList<List<Rate<Object>>>();
        lists.add(cs.shopActionsAct1);
        lists.add(cs.shopActionsAct2);
        lists.add(cs.shopActionsAct3);
        lists.add(cs.shopActionsAct4);
        writeActionWorkbook(model, root, "商店行动", lists, 32, false);
    }

    private static void writeActionWorkbook(TierListModel model, File root, String fileName,
                                            List<List<Rate<Object>>> lists, int limit, boolean campfire) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String method = campfire
                ? "口径：次数 = 火堆行动被选择次数；胜率 = 出现该行动的对局通关比例；每幕最多 35 行"
                : "口径：次数 = 商品购买/商店删牌次数；胜率 = 发生该购买的对局通关比例；每幕最多 32 行";
        for (int act = 1; act <= 4; act++) {
            String sheetName = ACT_ZH[act - 1];
            XlsxWriter.Sheet sheet = newSheet(wb, sheetName, fileName + " · " + ACT_ZH[act - 1], pageContext(model, method),
                    "排名", "名称", "原始键/英文名", "次数", "胜场", "败场", "胜率", "备注");
            sheet.width(7, 38.0D);
            int count = writeActionRows(sheet, lists.get(act - 1), limit, model.minSample, campfire);
            if (count == 0) {
                noData(sheet);
            }
        }
        wb.write(new File(root, fileName + ".xlsx"));
    }

    private static int writePickRows(XlsxWriter.Sheet sheet, List<? extends Rate<?>> rates,
                                     int limit, int minSample) {
        if (rates == null) {
            return 0;
        }
        int count = 0;
        int len = limit > 0 ? Math.min(rates.size(), limit) : rates.size();
        for (int i = 0; i < len; i++) {
            Rate<?> rate = rates.get(i);
            if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                continue;
            }
            count++;
            sheet.data(
                    XlsxWriter.Cell.integer(count),
                    XlsxWriter.Cell.text(rateZh(rate.what)),
                    XlsxWriter.Cell.text(rateEn(rate.what)),
                    XlsxWriter.Cell.integer(rate.win),
                    XlsxWriter.Cell.integer(rate.loss),
                    XlsxWriter.Cell.integer(rate.total()),
                    XlsxWriter.Cell.percent(ratio(rate.win, rate.total())));
        }
        return count;
    }

    private static int writeWinRows(XlsxWriter.Sheet sheet, List<? extends Rate<?>> rates,
                                    int limit, int minSample) {
        if (rates == null) {
            return 0;
        }
        int count = 0;
        int len = limit > 0 ? Math.min(rates.size(), limit) : rates.size();
        for (int i = 0; i < len; i++) {
            Rate<?> rate = rates.get(i);
            if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                continue;
            }
            count++;
            sheet.data(
                    XlsxWriter.Cell.integer(count),
                    XlsxWriter.Cell.text(rateZh(rate.what)),
                    XlsxWriter.Cell.text(rateEn(rate.what)),
                    XlsxWriter.Cell.integer(rate.win),
                    XlsxWriter.Cell.integer(rate.loss),
                    XlsxWriter.Cell.integer(rate.total()),
                    XlsxWriter.Cell.percent(ratio(rate.win, rate.total())));
        }
        return count;
    }

    private static int writeMeanRows(XlsxWriter.Sheet sheet, Map<Integer, List<Mean>> map,
                                     int minSample, String note) {
        if (map == null) {
            return 0;
        }
        int count = 0;
        for (int act = 1; act <= 4; act++) {
            List<Mean> list = map.get(act);
            if (list == null || list.isEmpty()) {
                continue;
            }
            int rank = 0;
            for (Mean mean : list) {
                if (mean == null || mean.what == null || mean.getSampleSize() < minSample) {
                    continue;
                }
                count++;
                boolean summary = DAMAGE_TOTAL_NAMES.contains(mean.what);
                String rowNote = summary
                        ? ("战斗总计".equals(mean.what)
                        ? "该幕全部战斗合计，按发生过战斗的对局平均"
                        : "该幕非 Boss 战斗合计，按发生过战斗的对局平均")
                        : note;
                if (summary) {
                    sheet.data(
                            XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                            null,
                            XlsxWriter.Cell.summaryText(mean.what),
                            XlsxWriter.Cell.text(""),
                            XlsxWriter.Cell.summaryDecimal(mean.mean()),
                            XlsxWriter.Cell.summaryInteger(mean.getSampleSize()),
                            XlsxWriter.Cell.text(rowNote));
                } else {
                    rank++;
                    sheet.data(
                            XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                            XlsxWriter.Cell.integer(rank),
                            XlsxWriter.Cell.text(meanZh(mean.what)),
                            XlsxWriter.Cell.text(mean.what),
                            XlsxWriter.Cell.decimal(mean.mean()),
                            XlsxWriter.Cell.integer(mean.getSampleSize()),
                            XlsxWriter.Cell.text(rowNote));
                }
            }
        }
        return count;
    }

    private static int writeSplitRows(XlsxWriter.Sheet sheet,
                                      Map<Integer, List<Mean>> noHealMap,
                                      Map<Integer, List<Mean>> healMap,
                                      int minSample) {
        if (noHealMap == null && healMap == null) {
            return 0;
        }
        int count = 0;
        for (int act = 1; act <= 4; act++) {
            List<Mean> noHealList = noHealMap == null ? null : noHealMap.get(act);
            List<Mean> healList = healMap == null ? null : healMap.get(act);
            if ((noHealList == null || noHealList.isEmpty()) && (healList == null || healList.isEmpty())) {
                continue;
            }
            Map<String, Mean> healByName = new HashMap<String, Mean>();
            if (healList != null) {
                for (Mean mean : healList) {
                    if (mean != null && mean.what != null) {
                        healByName.put(mean.what, mean);
                    }
                }
            }
            Set<String> seen = new HashSet<String>();
            int rank = 0;
            if (noHealList != null) {
                for (Mean noHeal : noHealList) {
                    if (noHeal == null || noHeal.what == null || noHeal.getSampleSize() < minSample) {
                        continue;
                    }
                    Mean heal = healByName.get(noHeal.what);
                    if (heal != null && heal.getSampleSize() <= 0) {
                        heal = null;
                    }
                    seen.add(noHeal.what);
                    count++;
                    boolean summary = DAMAGE_TOTAL_NAMES.contains(noHeal.what);
                    String rowNote = summary
                            ? "该幕合计，无回复/有回复分别按对应对局平均"
                            : "同一遭遇在无回复/有回复对局中的平均实际战损";
                    if (summary) {
                        sheet.data(
                                XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                                null,
                                XlsxWriter.Cell.summaryText(noHeal.what),
                                XlsxWriter.Cell.text(""),
                                XlsxWriter.Cell.summaryDecimal(noHeal.mean()),
                                XlsxWriter.Cell.summaryInteger(noHeal.getSampleSize()),
                                heal == null ? null : XlsxWriter.Cell.summaryDecimal(heal.mean()),
                                heal == null ? null : XlsxWriter.Cell.summaryInteger(heal.getSampleSize()),
                                XlsxWriter.Cell.text(rowNote));
                    } else {
                        rank++;
                        sheet.data(
                                XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                                XlsxWriter.Cell.integer(rank),
                                XlsxWriter.Cell.text(meanZh(noHeal.what)),
                                XlsxWriter.Cell.text(noHeal.what),
                                XlsxWriter.Cell.decimal(noHeal.mean()),
                                XlsxWriter.Cell.integer(noHeal.getSampleSize()),
                                heal == null ? null : XlsxWriter.Cell.decimal(heal.mean()),
                                heal == null ? null : XlsxWriter.Cell.integer(heal.getSampleSize()),
                                XlsxWriter.Cell.text(rowNote));
                    }
                }
            }
            if (healList != null) {
                for (Mean heal : healList) {
                    if (heal == null || heal.what == null || heal.getSampleSize() < minSample) {
                        continue;
                    }
                    if (seen.contains(heal.what)) {
                        continue;
                    }
                    seen.add(heal.what);
                    count++;
                    boolean summary = DAMAGE_TOTAL_NAMES.contains(heal.what);
                    String rowNote = summary
                            ? "该幕有回复对局合计"
                            : "该遭遇只在有回复对局中出现";
                    if (summary) {
                        sheet.data(
                                XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                                null,
                                XlsxWriter.Cell.summaryText(heal.what),
                                XlsxWriter.Cell.text(""),
                                null,
                                null,
                                XlsxWriter.Cell.summaryDecimal(heal.mean()),
                                XlsxWriter.Cell.summaryInteger(heal.getSampleSize()),
                                XlsxWriter.Cell.text(rowNote));
                    } else {
                        rank++;
                        sheet.data(
                                XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                                XlsxWriter.Cell.integer(rank),
                                XlsxWriter.Cell.text(meanZh(heal.what)),
                                XlsxWriter.Cell.text(heal.what),
                                null,
                                null,
                                XlsxWriter.Cell.decimal(heal.mean()),
                                XlsxWriter.Cell.integer(heal.getSampleSize()),
                                XlsxWriter.Cell.text(rowNote));
                    }
                }
            }
        }
        return count;
    }

    private static int writeMortalityRows(XlsxWriter.Sheet sheet, ClassStat cs, int minSample) {
        if (cs.encounterDeadRate == null) {
            return 0;
        }
        int count = 0;
        for (int act = 1; act <= 4; act++) {
            List<Rate<String>> rates = cs.encounterDeadRate.get(act);
            int questionMarks = questionMarks(cs, act);
            if ((rates == null || rates.isEmpty()) && questionMarks <= 0) {
                continue;
            }
            int totalEncounters = 0;
            int totalDeaths = 0;
            if (rates != null) {
                for (Rate<String> rate : rates) {
                    if (rate != null) {
                        totalEncounters += rate.total();
                        totalDeaths += rate.win;
                    }
                }
            }

            if (questionMarks > 0 && questionMarks >= minSample) {
                count++;
                sheet.data(
                        XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                        null,
                        XlsxWriter.Cell.summaryText("问号节点"),
                        XlsxWriter.Cell.text(""),
                        null,
                        XlsxWriter.Cell.summaryInteger(questionMarks),
                        null,
                        XlsxWriter.Cell.text("该幕问号节点数量（非遭遇）"));
            }
            if (totalEncounters > 0 && totalEncounters >= minSample) {
                count++;
                sheet.data(
                        XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                        null,
                        XlsxWriter.Cell.summaryText("总遭遇"),
                        XlsxWriter.Cell.text(""),
                        XlsxWriter.Cell.summaryInteger(totalDeaths),
                        XlsxWriter.Cell.summaryInteger(totalEncounters),
                        XlsxWriter.Cell.summaryPercent(ratio(totalDeaths, totalEncounters)),
                        XlsxWriter.Cell.text("该幕全部遭遇汇总（死亡率）"));
            }
            if (rates != null) {
                int rank = 0;
                for (Rate<String> rate : rates) {
                    if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                        continue;
                    }
                    count++;
                    rank++;
                    sheet.data(
                            XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                            XlsxWriter.Cell.integer(rank),
                            XlsxWriter.Cell.text(meanZh(rate.what)),
                            XlsxWriter.Cell.text(rate.what),
                            XlsxWriter.Cell.integer(rate.win),
                            XlsxWriter.Cell.integer(rate.total()),
                            XlsxWriter.Cell.percent(ratio(rate.win, rate.total())),
                            XlsxWriter.Cell.text("该敌人导致的死亡局数 ÷ 遭遇次数"));
                }
            }
        }
        return count;
    }

    private static int writeEventRows(XlsxWriter.Sheet sheet, List<Rate<String>> rates,
                                      Map<Integer, Integer> questionMarksByAct, int act, int minSample) {
        int count = 0;
        int questionMarks = questionMarks(questionMarksByAct, act);
        int totalEncounters = 0;
        int totalLosses = 0;
        if (rates != null) {
            for (Rate<String> rate : rates) {
                if (rate != null) {
                    totalEncounters += rate.total();
                    totalLosses += rate.loss;
                }
            }
        }

        if (questionMarks > 0 && questionMarks >= minSample) {
            count++;
            sheet.data(
                    null,
                    XlsxWriter.Cell.summaryText("问号节点"),
                    XlsxWriter.Cell.text(""),
                    null,
                    null,
                    XlsxWriter.Cell.summaryInteger(questionMarks),
                    null,
                    XlsxWriter.Cell.text("该幕问号节点数量（非事件）"));
        }
        if (totalEncounters > 0 && totalEncounters >= minSample) {
            count++;
            sheet.data(
                    null,
                    XlsxWriter.Cell.summaryText("总事件"),
                    XlsxWriter.Cell.text(""),
                    XlsxWriter.Cell.summaryInteger(totalEncounters - totalLosses),
                    XlsxWriter.Cell.summaryInteger(totalLosses),
                    XlsxWriter.Cell.summaryInteger(totalEncounters),
                    XlsxWriter.Cell.summaryPercent(ratio(totalEncounters - totalLosses, totalEncounters)),
                    XlsxWriter.Cell.text("该幕全部事件的对局胜率汇总"));
        }

        int rank = 0;
        if (rates != null) {
            for (Rate<String> rate : rates) {
                if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                    continue;
                }
                count++;
                rank++;
                sheet.data(
                        XlsxWriter.Cell.integer(rank),
                        XlsxWriter.Cell.text(meanZh(rate.what)),
                        XlsxWriter.Cell.text(rate.what),
                        XlsxWriter.Cell.integer(rate.win),
                        XlsxWriter.Cell.integer(rate.loss),
                        XlsxWriter.Cell.integer(rate.total()),
                        XlsxWriter.Cell.percent(ratio(rate.win, rate.total())),
                        XlsxWriter.Cell.text("事件出现时该对局最终通关的比例"));
            }
        }
        return count;
    }

    private static int writeActionRows(XlsxWriter.Sheet sheet, List<Rate<Object>> rates,
                                       int limit, int minSample, boolean campfire) {
        if (rates == null) {
            return 0;
        }
        int count = 0;
        int rank = 0;
        int len = limit > 0 ? Math.min(rates.size(), limit) : rates.size();
        for (int i = 0; i < len; i++) {
            Rate<Object> rate = rates.get(i);
            if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                continue;
            }
            count++;
            String display = actionName(rate.what, campfire);
            String raw = rawActionName(rate.what);
            String note;
            boolean summary = isActionSummary(display, campfire);
            if (summary) {
                note = actionSummaryNote(display);
            } else {
                note = campfire
                        ? "该行动出现时的对局胜率"
                        : "该商品被购买时对应对局的胜率";
            }
            if (summary) {
                sheet.data(
                        null,
                        XlsxWriter.Cell.summaryText(display),
                        XlsxWriter.Cell.text(raw),
                        XlsxWriter.Cell.summaryInteger(rate.total()),
                        XlsxWriter.Cell.summaryInteger(rate.win),
                        XlsxWriter.Cell.summaryInteger(rate.loss),
                        XlsxWriter.Cell.summaryPercent(ratio(rate.win, rate.total())),
                        XlsxWriter.Cell.text(note));
            } else {
                rank++;
                sheet.data(
                        XlsxWriter.Cell.integer(rank),
                        XlsxWriter.Cell.text(display),
                        XlsxWriter.Cell.text(raw),
                        XlsxWriter.Cell.integer(rate.total()),
                        XlsxWriter.Cell.integer(rate.win),
                        XlsxWriter.Cell.integer(rate.loss),
                        XlsxWriter.Cell.percent(ratio(rate.win, rate.total())),
                        XlsxWriter.Cell.text(note));
            }
        }
        return count;
    }

    private static XlsxWriter.Sheet newSheet(XlsxWriter wb, String sheetName, String title,
                                             String context, String... headers) {
        XlsxWriter.Sheet sheet = wb.addSheet(sheetName);
        sheet.title(title, headers.length);
        sheet.context(context, headers.length);
        sheet.blank();
        sheet.header(headers);
        return sheet;
    }

    private static void noData(XlsxWriter.Sheet sheet) {
        sheet.data(XlsxWriter.Cell.text("无数据（当前筛选下没有可用样本）"));
    }

    private static void summaryLine(XlsxWriter.Sheet sheet, String label, XlsxWriter.Cell value, String note) {
        sheet.data(XlsxWriter.Cell.text(label), value, XlsxWriter.Cell.text(note));
    }

    private static void metaLine(XlsxWriter.Sheet sheet, String label, int value, String note) {
        sheet.data(XlsxWriter.Cell.text(label), XlsxWriter.Cell.integer(value), XlsxWriter.Cell.text(note));
    }

    private static ClassStat[] orderedStats(MoreStatsScreen screen) {
        ClassStat[] result = new ClassStat[5];
        ClassStat[] source = screen.classStats;
        if (source != null && source.length >= 5) {
            result[0] = source[4];
            result[1] = source[0];
            result[2] = source[1];
            result[3] = source[2];
            result[4] = source[3];
        } else {
            result[0] = screen.getClassStat();
        }
        return result;
    }

    private static String filterContext(TierListModel model, String extra) {
        StringBuilder text = new StringBuilder(256);
        text.append("日期：").append(empty(model.startDate)).append(" ~ ").append(empty(model.endDate))
                .append(" ｜ 种子局：").append(model.seedFilter.labelZh)
                .append(" ｜ 放弃局：").append(model.includeAbandons ? "包含" : "排除")
                .append(" ｜ 最小样本：").append(model.minSample)
                .append(" ｜ 生成时间：").append(model.generatedAt)
                .append(" ｜ 范围：A20 / 当前存档位 / 非每日与特殊局");
        if (extra != null && !extra.isEmpty()) {
            text.append(" ｜ ").append(extra);
        }
        return text.toString();
    }

    private static String pageContext(TierListModel model, String extra) {
        return "角色：" + model.characterZh
                + (model.isAggregate() ? "（全角色汇总，不是第 5 个角色）" : "")
                + " ｜ 对局：" + model.runCount + " ｜ " + filterContext(model, extra);
    }

    private static String rateZh(Object what) {
        if (what instanceof Card) {
            return cardZh((Card) what);
        }
        if (what instanceof Neow) {
            return neowZh((Neow) what);
        }
        if (what instanceof BossRelic) {
            String name = ((BossRelic) what).name;
            return "SKIP".equals(name) ? "跳过" : NameMapper.toChinese(name);
        }
        if (what instanceof String) {
            return meanZh((String) what);
        }
        return what == null ? "" : String.valueOf(what);
    }

    private static String rateEn(Object what) {
        if (what instanceof Card) {
            return ((Card) what).toString();
        }
        if (what instanceof BossRelic) {
            String name = ((BossRelic) what).name;
            return "SKIP".equals(name) ? "Skip" : name;
        }
        return what == null ? "" : String.valueOf(what);
    }

    private static String cardZh(Card card) {
        String base = NameMapper.toChinese(card.name);
        if (card.upgrades == 1) {
            return base + "+";
        }
        if (card.upgrades > 1) {
            return base + "+" + card.upgrades;
        }
        return base;
    }

    private static String meanZh(String name) {
        if (name == null) {
            return "";
        }
        if (SUMMARY_NAMES.contains(name)) {
            return name;
        }
        if (name.startsWith("事件:")) {
            return "事件:" + NameMapper.toChinese(name.substring(3));
        }
        return NameMapper.toChinese(name);
    }

    private static String neowZh(Neow neow) {
        String bonus = NEOW_BONUS_ZH.get(neow.bonus);
        if (bonus == null) {
            bonus = NameMapper.toChinese(neow.bonus);
        }
        if (neow.cost == null || "NONE".equals(neow.cost)) {
            return bonus;
        }
        String cost = NEOW_COST_ZH.get(neow.cost);
        if (cost == null) {
            cost = NameMapper.toChinese(neow.cost);
        }
        return bonus + cost;
    }

    private static String actionName(Object what, boolean campfire) {
        if (what instanceof Card) {
            return cardZh((Card) what);
        }
        String key = String.valueOf(what);
        if (campfire) {
            String action = CAMPFIRE_ACTIONS.get(key);
            return action == null ? key : action;
        }
        return meanZh(key);
    }

    private static String rawActionName(Object what) {
        return what instanceof Card ? ((Card) what).toString() : String.valueOf(what);
    }

    private static boolean isActionSummary(String name, boolean campfire) {
        if (campfire) {
            return "火堆总数".equals(name);
        }
        return "商店总数".equals(name) || "有效商店数".equals(name) || "商店删牌".equals(name);
    }

    private static String actionSummaryNote(String name) {
        if ("火堆总数".equals(name)) {
            return "该幕火堆行动总次数（非行动类型）";
        }
        if ("商店总数".equals(name)) {
            return "该幕遇到的商店次数（非购买次数）";
        }
        if ("有效商店数".equals(name)) {
            return "该幕有购买或删牌行为的商店次数";
        }
        if ("商店删牌".equals(name)) {
            return "该幕商店购买删牌次数";
        }
        return "";
    }

    private static int purgeAct(ClassStat cs, int act) {
        if (cs.purgesByAct == null) {
            return 0;
        }
        Integer value = cs.purgesByAct.get(act);
        return value == null ? 0 : value;
    }

    private static int questionMarks(ClassStat cs, int act) {
        return questionMarks(cs.questionMarksByAct, act);
    }

    private static int questionMarks(Map<Integer, Integer> map, int act) {
        if (map == null) {
            return 0;
        }
        Integer value = map.get(act);
        return value == null ? 0 : value;
    }

    private static double survival(ClassStat cs, int act) {
        if (cs.survivalRatePerAct == null) {
            return 0.0D;
        }
        Rate<Integer> rate = cs.survivalRatePerAct.get(act);
        return rate == null ? 0.0D : ratio(rate.win, rate.total());
    }

    private static double ratio(int numerator, int denominator) {
        return denominator <= 0 ? 0.0D : (double) numerator / (double) denominator;
    }

    private static double avg(int value, int runs) {
        return runs <= 0 ? 0.0D : (double) value / (double) runs;
    }

    private static String duration(long seconds) {
        if (seconds <= 0L) {
            return "0:00:00";
        }
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long secs = seconds % 60L;
        return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, secs);
    }

    private static String meanDuration(Mean mean) {
        if (mean == null || mean.getSampleSize() <= 0) {
            return "—";
        }
        return duration((long) mean.mean());
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
