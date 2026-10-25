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
import java.util.Comparator;
import java.util.Collections;
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
 * - 第一幕 / 第二幕 / 第三、四幕 / 第一幕后卡牌选取率.xlsx（第一幕后 = 第二至四幕合并）
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

    /**
     * 一份卡牌抓率工作簿的坐标：标题、模型分组 key、范围说明。
     *
     * 四份表共用同一套列与数值口径，只有范围不同；范围说明必须写清 floor 0
     * 与一幕 Boss 的归属，否则读者会拿旧版 floor >= 17 的口径去对数字。
     */
    private static final class CardPickSpec {
        final String name;
        final String groupKey;
        final String range;

        CardPickSpec(String name, String groupKey, String range) {
            this.name = name;
            this.groupKey = groupKey;
            this.range = range;
        }
    }

    private static final CardPickSpec CARD_ACT1 = new CardPickSpec(
            "第一幕卡牌选取率", "cards_act1",
            "范围：第一幕（含 floor 0 与一幕 Boss 后的奖励屏；按每局实际 Boss 楼层判定）");
    private static final CardPickSpec CARD_ACT2 = new CardPickSpec(
            "第二幕卡牌选取率", "cards_act2",
            "范围：第二幕（按每局实际 Boss 楼层判定）");
    private static final CardPickSpec CARD_ACT3_4 = new CardPickSpec(
            "第三、四幕卡牌选取率", "cards_act3_4",
            "范围：第三、四幕（按每局实际 Boss 楼层判定）");
    private static final CardPickSpec CARD_AFTER_ACT1 = new CardPickSpec(
            "第一幕后卡牌选取率", "cards_after_act1",
            "范围：第一幕以后（第二至四幕合并，按每局实际 Boss 楼层判定）");

    /**
     * 95%CI 可信度的分档。
     *
     * 用相对半宽（CI 半宽 ÷ 点估计）而不是绝对宽度：标准化率是占比，
     * 3% 的卡和 0.1% 的卡用同一个绝对宽度衡量没有意义。
     * 20% 与 40% 是按现有三个角色第一幕导出（139 条有效样本）定的：
     * 相对半宽中位数 18.4%，约一半条目落在绿色；超过 40% 时点估计已经
     * 可能偏离 1.5 倍以上，再细排名次没有意义。
     * 零被选的卡 CI 会退化为 0 宽（看起来最“可信”），所以必须先用
     * 被选次数兜底，否则会把它们误标成绿色。
     */
    private static final int CI_MIN_OFFERED = 10;
    private static final int CI_MIN_PICKS = 3;
    private static final double CI_GREEN_MAX = 0.20D;
    private static final double CI_ORANGE_MAX = 0.40D;

    private static final String CI_COLOR_NOTE =
            "Excel 配色：绿色 = 可信（95%CI 半宽 ≤ 点估计的 20%，且出现 ≥ 10 次、被选 ≥ 3 次）；"
                    + "橙色 = 一般（半宽 ≤ 40%）；红色 = 不可信（半宽 > 40%，或出现 < 10 次、被选 < 3 次）。"
                    + "零被选条目的 CI 会退化为 0 宽，也按红色处理。";

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
                writeCardPick(model, cs.cardPicksAct1, CARD_ACT1, root);
                break;
            case CARDS_ACT2:
                writeCardPick(model, cs.cardPicksAct2, CARD_ACT2, root);
                break;
            case CARDS_ACT3_4:
                writeCardPick(model, cs.cardPicksAct3_4, CARD_ACT3_4, root);
                break;
            case CARDS_AFTER_ACT1:
                writeCardPick(model, cs.cardPicksAfterAct1, CARD_AFTER_ACT1, root);
                break;
            case CARDS_ALL_ACTS:
                writeCardPicks(model, cs, root);
                break;
            case BOSS_RELICS:
                writeBossRelics(model, cs, root);
                break;
            case NEOW:
                // 尼奥页单独导出时也要有工作簿：里面有「简单率 / 标准化率」两张表，
                // 标准化率只在这个 xlsx 里，不写就没地方看。
                writeNeow(model, cs, root);
                break;
            case CAMPFIRE:
                writeCampfire(model, cs, root);
                break;
            case SHOP:
                writeShop(model, cs, root);
                break;
            case CAMPFIRE_UPGRADE:
                writeCampfireUpgrade(model, cs, root);
                break;
            case ALL:
            default:
                writeOverview(screen, model, root);
                writeCardPicks(model, cs, root);
                writeCardWinRate(model, cs, root);
                writeNeow(model, cs, root);
                writeBossRelics(model, cs, root);
                writeDamage(model, cs, root);
                writeMortalityAndPotions(model, cs, root);
                writeRelics(model, cs, root);
                writeEvents(model, cs, root);
                writeCampfire(model, cs, root);
                writeShop(model, cs, root);
                writeCampfireUpgrade(model, cs, root);
                break;
        }
    }

    /** 四份卡牌抓率工作簿：一幕、二幕、三、四幕、一幕以后（第二至四幕合并）。 */
    private static void writeCardPicks(TierListModel model, ClassStat cs, File root) throws IOException {
        writeCardPick(model, cs.cardPicksAct1, CARD_ACT1, root);
        writeCardPick(model, cs.cardPicksAct2, CARD_ACT2, root);
        writeCardPick(model, cs.cardPicksAct3_4, CARD_ACT3_4, root);
        writeCardPick(model, cs.cardPicksAfterAct1, CARD_AFTER_ACT1, root);
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

    /**
     * 置信区间可信度：0 红、1 绿、2 橙、-1 不上色。
     *
     * point / low / high 必须同单位（这里都是百分点），因为只用到相对半宽。
     */
    private static int ciReliability(int offered, int picked, double point, double low, double high) {
        if (offered < CI_MIN_OFFERED || picked < CI_MIN_PICKS || point <= 0.0D || high <= low) {
            return XlsxWriter.RELIABILITY_LOW;
        }
        double relative = (high - low) / (2.0D * point);
        if (relative <= CI_GREEN_MAX + 1e-9D) {
            return XlsxWriter.RELIABILITY_HIGH;
        }
        if (relative <= CI_ORANGE_MAX + 1e-9D) {
            return XlsxWriter.RELIABILITY_MEDIUM;
        }
        return XlsxWriter.RELIABILITY_LOW;
    }

    private static String ciReliabilityLabel(int level) {
        return level == XlsxWriter.RELIABILITY_HIGH ? "可信"
                : level == XlsxWriter.RELIABILITY_MEDIUM ? "一般" : "不可信";
    }

    private static void writeCardPick(TierListModel model, List<Rate<Card>> rates,
                                      CardPickSpec spec, File root) throws IOException {
        String name = spec.name;
        Map<String, TierEntry> standard = cardStandard(model, spec.groupKey);
        XlsxWriter wb = new XlsxWriter();
        XlsxWriter.Sheet sheet = newSheet(wb, name, name,
                pageContext(model, spec.range)
                        + "标准化率 = Plackett-Luce 强度占比：每次「3 张牌 + 跳过」是一个选择集，"
                        + "选中某张的概率 = 它的强度 ÷ 同屏所有选项强度之和；"
                        + "剔除「同场另外两张有多强」造成的偏差；合计 100%，与选取率不是同一量纲，不能互相替代。"
                        + "四组分别拟合、各自合计 100%，不能把二幕 + 三、四幕相加当作第一幕后。"
                        + "95%CI 来自整屏 bootstrap(1000)：一次奖励屏整组重采样，"
                        + "不会把同一个决定当成多份独立证据。SKIP 与颂钵（并入 SKIP）每次奖励都在场，已计入对手。"
                        + "未校正：同一局第 2 张起平均少抓 12pp，对名次影响约 0.5~1 位。"
                        + "空白 = 该条目不在标准化表内（被诅咒/状态过滤，或颂钵并入了 SKIP）。"
                        + CI_COLOR_NOTE,
                "排名", "中文名", "英文名", "选取次数", "跳过次数", "提供次数", "选取率",
                "标准化率", "95%CI 下", "95%CI 上", "CI 可信度");
        int count = writeCardPickRows(sheet, rates, 200, model.minSample, standard);
        if (count == 0) {
            noData(sheet);
        }
        wb.write(new File(root, name + ".xlsx"));
    }

    /** 卡牌表：条目 id → 已挂好标准化率的 TierEntry（来自同一个 TierListModel，不重复拟合）。 */
    private static Map<String, TierEntry> cardStandard(TierListModel model, String groupKey) {
        Map<String, TierEntry> out = new HashMap<String, TierEntry>();
        if (model == null || model.groups == null) {
            return out;
        }
        for (TierListGroup group : model.groups) {
            if (!groupKey.equals(group.key)) {
                continue;
            }
            for (TierEntry entry : group.entries) {
                if (entry.standardized >= 0.0D) {
                    out.put(entry.id, entry);
                }
            }
        }
        return out;
    }

    private static int writeCardPickRows(XlsxWriter.Sheet sheet, List<Rate<Card>> rates,
                                         int limit, int minSample, Map<String, TierEntry> standard) {
        if (rates == null) {
            return 0;
        }
        int count = 0;
        int len = limit > 0 ? Math.min(rates.size(), limit) : rates.size();
        for (int i = 0; i < len; i++) {
            Rate<Card> rate = rates.get(i);
            if (rate == null || rate.what == null || rate.getSampleSize() < minSample) {
                continue;
            }
            count++;
            TierEntry entry = standard.get(rate.what.name);
            int level = XlsxWriter.RELIABILITY_NONE;
            XlsxWriter.Cell reliability = XlsxWriter.Cell.text("—");
            if (entry != null) {
                level = ciReliability(rate.total(), rate.win,
                        entry.standardized, entry.stdLow, entry.stdHigh);
                reliability = XlsxWriter.Cell.reliability(ciReliabilityLabel(level), level);
            }
            sheet.dataTinted(level,
                    XlsxWriter.Cell.integer(count),
                    XlsxWriter.Cell.text(rateZh(rate.what)),
                    XlsxWriter.Cell.text(rateEn(rate.what)),
                    XlsxWriter.Cell.integer(rate.win),
                    XlsxWriter.Cell.integer(rate.loss),
                    XlsxWriter.Cell.integer(rate.total()),
                    XlsxWriter.Cell.percent(ratio(rate.win, rate.total())),
                    entry == null ? XlsxWriter.Cell.text("") : XlsxWriter.Cell.percent(entry.standardized / 100.0D),
                    entry == null ? XlsxWriter.Cell.text("") : XlsxWriter.Cell.percent(entry.stdLow / 100.0D),
                    entry == null ? XlsxWriter.Cell.text("") : XlsxWriter.Cell.percent(entry.stdHigh / 100.0D),
                    reliability);
        }
        return count;
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
        String context = pageContext(model,
                "口径：一行 = 一个「奖励 + 代价」选项，不同代价算不同选项、不合并。"
                        + "出现次数 = 该选项被选中 + 被跳过的次数；简单率 = 被选 ÷ 出现。"
                        + "标准化率 = 层级 Plackett-Luce 后验均值：每局「1 选中 + 3 跳过」是一个选择集，"
                        + "选中某个的概率 = 它的强度 ÷ 同屏所有选项强度之和；用它剔除「老和强选项同场」造成的低估。"
                        + "全胜选项没有有限的最大似然解，普通 PL 的名次由「+1 假想胜场」的先验决定；"
                        + "这里给 log 强度加 log-normal 先验，尺度由 5 折交叉验证（按对局分组）选定，"
                        + "95% 可信区间与 P(第一) 来自 Laplace 后验抽样。"
                        + "「并列」= 与组首相比胜出的把握不足 95%；组内按出现次数从多到少排，样本越大越可信。"
                        + "简单率区间用 Wilson 区间。两条区间重叠时，名次差不能当成真实差异。"
                        + CI_COLOR_NOTE);
        XlsxWriter.Sheet win = newSheet(wb, "尼奥胜率", "尼奥开局选项 · 胜率", context,
                "排名", "中文名", "英文名", "胜场", "败场", "对局数", "胜率");
        int winCount = writeWinRows(win, cs.neowWinRate, 0, model.minSample);
        if (winCount == 0) {
            noData(win);
        }

        List<NeowRanker.Row> rows = NeowRanker.rank(cs, model.minSample);
        if (rows.isEmpty()) {
            XlsxWriter.Sheet empty = newSheet(wb, "简单率", "尼奥开局选项 · 简单率", context,
                    "排名", "中文名", "英文名", "出现", "被选", "简单率", "95%CI 下", "95%CI 上", "CI 可信度");
            noData(empty);
            wb.write(new File(root, "尼奥奖励.xlsx"));
            return;
        }

        // 表一：简单率（按简单率降序）
        List<NeowRanker.Row> bySimple = new ArrayList<NeowRanker.Row>(rows);
        Collections.sort(bySimple, new Comparator<NeowRanker.Row>() {
            @Override
            public int compare(NeowRanker.Row a, NeowRanker.Row b) {
                return Double.compare(b.simple, a.simple);
            }
        });
        XlsxWriter.Sheet simple = newSheet(wb, "简单率", "尼奥开局选项 · 简单率（按出现时被选比例）", context,
                "排名", "中文名", "英文名", "出现", "被选", "简单率", "95%CI 下", "95%CI 上", "CI 可信度");
        int rank = 0;
        for (NeowRanker.Row row : bySimple) {
            rank++;
            int level = ciReliability(row.offered, row.picked, row.simple, row.simpleLow, row.simpleHigh);
            simple.dataTinted(level,
                    XlsxWriter.Cell.integer(rank),
                    XlsxWriter.Cell.text(NeowShort.zh(row.neow.bonus, row.neow.cost)),
                    XlsxWriter.Cell.text(NeowShort.en(row.neow.bonus, row.neow.cost)),
                    XlsxWriter.Cell.integer(row.offered),
                    XlsxWriter.Cell.integer(row.picked),
                    XlsxWriter.Cell.percent(row.simple / 100.0D),
                    XlsxWriter.Cell.percent(row.simpleLow / 100.0D),
                    XlsxWriter.Cell.percent(row.simpleHigh / 100.0D),
                    XlsxWriter.Cell.reliability(ciReliabilityLabel(level), level));
        }

        // 表二：标准化率（按 PL 强度降序），附简单率与名次变动
        Map<NeowRanker.Row, Integer> simpleRank = new java.util.IdentityHashMap<NeowRanker.Row, Integer>();
        for (int i = 0; i < bySimple.size(); i++) {
            simpleRank.put(bySimple.get(i), i + 1);
        }
        XlsxWriter.Sheet std = newSheet(wb, "标准化率", "尼奥开局选项 · 标准化率（层级 Plackett-Luce）", context,
                "排名", "中文名", "英文名", "标准化率", "95%CI 下", "95%CI 上",
                "简单率", "简单排名", "名次变动", "出现", "并列", "P(第一)", "CI 可信度");
        rank = 0;
        for (NeowRanker.Row row : rows) {
            rank++;
            int sr = simpleRank.containsKey(row) ? simpleRank.get(row) : 0;
            int level = ciReliability(row.offered, row.picked,
                    row.standardized, row.standardizedLow, row.standardizedHigh);
            std.dataTinted(level,
                    XlsxWriter.Cell.integer(rank),
                    XlsxWriter.Cell.text(NeowShort.zh(row.neow.bonus, row.neow.cost)),
                    XlsxWriter.Cell.text(NeowShort.en(row.neow.bonus, row.neow.cost)),
                    XlsxWriter.Cell.percent(row.standardized / 100.0D),
                    XlsxWriter.Cell.percent(row.standardizedLow / 100.0D),
                    XlsxWriter.Cell.percent(row.standardizedHigh / 100.0D),
                    XlsxWriter.Cell.percent(row.simple / 100.0D),
                    XlsxWriter.Cell.integer(sr),
                    XlsxWriter.Cell.integer(sr - rank),
                    XlsxWriter.Cell.integer(row.offered),
                    XlsxWriter.Cell.text(row.tied()
                            ? "并列 " + row.tieStart + "–" + row.tieEnd : "—"),
                    XlsxWriter.Cell.percent(row.rankOne),
                    XlsxWriter.Cell.reliability(ciReliabilityLabel(level), level));
        }
        wb.write(new File(root, "尼奥奖励.xlsx"));
    }

    /**
     * 火堆选项权重：分母是该选项<b>真正可选</b>的次数。
     * 睡觉/升级几乎每次都能选；抽烟/举重/挖掘只在拥有和平烟斗/吉里亚/铲子时可选；
     * 回忆只在 1-3 幕且尚未回忆时可选，并拆「强制」（要进第四幕必须消耗一个火堆拿红钥匙）。
     */
    private static void writeCampfireOptionWeights(XlsxWriter wb, TierListModel model, ClassStat cs,
                                                   String method) {
        XlsxWriter.Sheet sheet = newSheet(wb, "火堆选项", "火堆选项权重",
                pageContext(model, method),
                "幕", "选项", "被选次数", "可选次数", "权重%", "随机基线%", "倍数", "强制回忆", "非强制回忆");
        sheet.width(1, 10.0D);
        sheet.width(4, 12.0D);
        sheet.width(5, 12.0D);
        sheet.width(6, 10.0D);
        int[] acts = {1, 2, 3, 4, 0};
        for (int i = 0; i < acts.length; i++) {
            int act = acts[i];
            String actName = act == 0 ? "四幕合计" : ACT_ZH[act - 1];
            List<ClassStat.CampfireOption> options = campfireOptionsOf(cs, act);
            if (options == null) {
                continue;
            }
            for (ClassStat.CampfireOption option : options) {
                if (option == null) {
                    continue;
                }
                sheet.data(
                        XlsxWriter.Cell.text(actName),
                        XlsxWriter.Cell.text(optionZh(option.key)),
                        XlsxWriter.Cell.integer(option.chosen),
                        XlsxWriter.Cell.integer(option.available),
                        XlsxWriter.Cell.percent(ratio(option.chosen, option.available)),
                        XlsxWriter.Cell.percent(option.baseline / 100.0D),
                        XlsxWriter.Cell.decimal(option.multiple()),
                        XlsxWriter.Cell.integer(option.forced),
                        XlsxWriter.Cell.integer(option.optional));
            }
        }
    }

    private static String optionZh(String key) {
        if ("SMITH".equals(key)) return "升级";
        if ("REST".equals(key)) return "睡觉";
        if ("RECALL".equals(key)) return "回忆";
        if ("PURGE".equals(key)) return "抽烟";
        if ("LIFT".equals(key)) return "举重";
        if ("DIG".equals(key)) return "挖掘";
        return key;
    }

    private static List<ClassStat.CampfireOption> campfireOptionsOf(ClassStat cs, int act) {
        switch (act) {
            case 1: return cs.campfireOptionsAct1;
            case 2: return cs.campfireOptionsAct2;
            case 3: return cs.campfireOptionsAct3;
            case 4: return cs.campfireOptionsAct4;
            default: return cs.campfireOptionsTotal;
        }
    }

    /**
     * 火堆构成：行动层与卡牌层共用「火堆访问次数」这同一分母。
     * 行动占比合计 = 100%（锻造 + 睡觉 + 回忆 + 抽烟 + 举重 + 挖掘）；
     * 卡牌表中的「占火堆」都落在锻造这一块里。
     */
    private static void writeCampfireMix(XlsxWriter wb, TierListModel model, ClassStat cs,
                                         String method) {
        XlsxWriter.Sheet sheet = newSheet(wb, "火堆构成", "火堆构成（行动占比）",
                pageContext(model, method),
                "幕", "行动", "原始键", "次数", "占该幕火堆", "占四幕合计");
        sheet.width(4, 14.0D);
        sheet.width(5, 14.0D);
        String[] keys = {"SMITH", "REST", "RECALL", "PURGE", "LIFT", "DIG"};
        String[] labels = {"锻造", "睡觉", "回忆", "抽烟", "举重", "挖掘"};
        for (int act = 1; act <= 4; act++) {
            List<Rate<Object>> actions = campfireActionsOf(cs, act);
            int visits = cs.campfireVisits[act];
            for (int i = 0; i < keys.length; i++) {
                int count = "SMITH".equals(keys[i]) ? cs.campfireSmiths[act]
                        : actionCount(actions, keys[i]);
                sheet.data(
                        XlsxWriter.Cell.text(ACT_ZH[act - 1]),
                        XlsxWriter.Cell.text(labels[i]),
                        XlsxWriter.Cell.text(keys[i]),
                        XlsxWriter.Cell.integer(count),
                        XlsxWriter.Cell.percent(ratio(count, visits)),
                        XlsxWriter.Cell.percent(ratio(count, cs.campfireVisits[0])));
            }
        }
        int visits = cs.campfireVisits[0];
        for (int i = 0; i < keys.length; i++) {
            int count = "SMITH".equals(keys[i]) ? cs.campfireSmiths[0]
                    : actionCount(cs.campfireActionsTotal, keys[i]);
            sheet.data(
                    XlsxWriter.Cell.text("四幕合计"),
                    XlsxWriter.Cell.text(labels[i]),
                    XlsxWriter.Cell.text(keys[i]),
                    XlsxWriter.Cell.integer(count),
                    XlsxWriter.Cell.percent(ratio(count, visits)),
                    XlsxWriter.Cell.percent(ratio(count, visits)));
        }
    }

    private static List<Rate<Object>> campfireActionsOf(ClassStat cs, int act) {
        switch (act) {
            case 1: return cs.campfireActionsAct1;
            case 2: return cs.campfireActionsAct2;
            case 3: return cs.campfireActionsAct3;
            case 4: return cs.campfireActionsAct4;
            default: return cs.campfireActionsTotal;
        }
    }

    private static int actionCount(List<Rate<Object>> actions, String key) {
        if (actions == null) {
            return 0;
        }
        for (Rate<Object> rate : actions) {
            if (rate != null && key.equals(rate.what)) {
                return rate.total();
            }
        }
        return 0;
    }

    private static void writeActionWorkbook(TierListModel model, File root, String fileName,
                                            List<List<Rate<Object>>> lists, int limit, boolean campfire) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        String method = campfire
                ? "口径：次数 = 火堆行动被选择次数；占比 = 次数 ÷ 该幕火堆行动总数；胜/败 = 出现该行动的对局结局。每幕最多 35 行"
                : "口径：次数 = 商品购买/商店删牌次数；占比 = 次数 ÷ 该幕购买总次数（商店总数/有效商店数不计入）；胜/败 = 出现该购买的对局结局。每幕最多 32 行";
        for (int act = 1; act <= 4; act++) {
            String sheetName = ACT_ZH[act - 1];
            int denominator = actionTotal(lists.get(act - 1), campfire);
            XlsxWriter.Sheet sheet = newSheet(wb, sheetName, fileName + " · " + ACT_ZH[act - 1], pageContext(model, method),
                    "排名", "名称", "原始键/英文名", "次数", "占比", "胜场", "败场", "备注");
            sheet.width(7, 38.0D);
            int count = writeActionRows(sheet, lists.get(act - 1), limit, model.minSample, campfire, denominator);
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

    private static int actionTotal(List<Rate<Object>> rates, boolean campfire) {
        if (rates == null) {
            return 0;
        }
        if (campfire) {
            for (Rate<Object> rate : rates) {
                if (rate != null && "火堆总数".equals(actionName(rate.what, true))) {
                    return rate.total();
                }
            }
        }
        int sum = 0;
        for (Rate<Object> rate : rates) {
            if (rate == null) {
                continue;
            }
            String name = actionName(rate.what, campfire);
            if (campfire && "火堆总数".equals(name)) {
                continue;
            }
            if (!campfire && ("商店总数".equals(name) || "有效商店数".equals(name))) {
                continue;
            }
            sum += rate.total();
        }
        return sum;
    }

    private static int writeActionRows(XlsxWriter.Sheet sheet, List<Rate<Object>> rates,
                                       int limit, int minSample, boolean campfire, int denominator) {
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
                        XlsxWriter.Cell.text(""),
                        XlsxWriter.Cell.summaryInteger(rate.win),
                        XlsxWriter.Cell.summaryInteger(rate.loss),
                        XlsxWriter.Cell.text(note));
            } else {
                rank++;
                String rowNote = note;
                if (campfire) {
                    rowNote = "该行动出现时的对局胜率；升级目标次数受持有量影响，见梯度图的抓取次数";
                }
                sheet.data(
                        XlsxWriter.Cell.integer(rank),
                        XlsxWriter.Cell.text(display),
                        XlsxWriter.Cell.text(raw),
                        XlsxWriter.Cell.integer(rate.total()),
                        XlsxWriter.Cell.percent(ratio(rate.total(), denominator)),
                        XlsxWriter.Cell.integer(rate.win),
                        XlsxWriter.Cell.integer(rate.loss),
                        XlsxWriter.Cell.text(rowNote));
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
        return NeowShort.fullZh(neow.bonus, neow.cost);
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
    private static void writeCampfireUpgrade(TierListModel model, ClassStat cs, File root) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        int minOwned = Math.max(1, model.minSample);
        List<List<ClassStat.CampfireShare>> lists = new ArrayList<List<ClassStat.CampfireShare>>();
        lists.add(cs.campfireChoiceDistAct1);
        lists.add(cs.campfireChoiceDistAct2);
        lists.add(cs.campfireChoiceDistAct3);
        lists.add(cs.campfireChoiceDistAct4);
        lists.add(cs.campfireChoiceDistTotal);
        String[] sheetNames = {ACT_ZH[0], ACT_ZH[1], ACT_ZH[2], ACT_ZH[3], "四幕合计"};
        String method = "口径：把「升级」拆回每张可敲的牌，与抽烟/举重/挖掘/回忆同榜竞争，按占比排序。"
                + "候选 = 该条目出现在候选里的次数；被敲 = 实际选中次数；权重% = 被敲 ÷ 候选。"
                + "随机基线% = 100 ÷ 该幕平均候选池大小（池子 = 可敲卡种 + 可用遗物行动 + 可用回忆）；倍数 = 权重 ÷ 基线。"
                + "A升级率% = 该幕锻造次数 ÷ 到该幕结束累计拥过的副本数（替补口径，不受删牌影响）。"
                + "睡觉单列：约束是血量，不进池子。候选不足 " + minOwned + " 次的条目不参与。";
        for (int i = 0; i < lists.size(); i++) {
            int act = i < 4 ? i + 1 : 0;
            XlsxWriter.Sheet sheet = newSheet(wb, sheetNames[i], "火堆偏好 · " + sheetNames[i],
                    pageContext(model, method),
                    "排名", "中文名", "英文名", "候选", "被敲", "权重%", "随机基线%", "倍数", "A升级率%", "备注");
            sheet.width(9, 52.0D);
            Map<Card, Rate<Card>> alt = altFor(cs, act);
            int count = writeUpgradeRows(sheet, lists.get(i), 0, minOwned,
                    cs.campfireUpgradeBaseline[act], alt, cs);
            if (count == 0) {
                noData(sheet);
            }
        }
        // 火堆构成（行动占比）也在这本工作簿里，和梯度图的口径一致
        writeCampfireMix(wb, model, cs, method);
        wb.write(new File(root, "火堆偏好.xlsx"));
    }

    private static int writeUpgradeRows(XlsxWriter.Sheet sheet, List<ClassStat.CampfireShare> rates,
                                        int limit, int minOwned, double baseline,
                                        Map<Card, Rate<Card>> alt, ClassStat cs) {
        if (rates == null) {
            return 0;
        }
        int count = 0;
        for (ClassStat.CampfireShare share : rates) {
            if (share == null || share.what == null || share.visits < minOwned) {
                continue;
            }
            if (limit > 0 && count >= limit) {
                break;
            }
            count++;
            String zh;
            String en;
            Rate<Card> altRate = null;
            Integer miss = null;
            if (share.card) {
                Card card = (Card) share.what;
                zh = rateZh(card);
                en = rateEn(card);
                altRate = alt.get(card);
                miss = cs.campfireUpgradeMissCard == null ? null : cs.campfireUpgradeMissCard.get(card);
            } else {
                zh = "[" + optionZh((String) share.what) + "]";
                en = (String) share.what;
            }
            double mult = baseline > 0.0D ? share.percent() / baseline : 0.0D;
            double altPercent = altRate == null ? 0.0D : altRate.percent();
            String note = miss == null ? "" : "重建缺失 " + miss + " 次，以 A 列为准";
            sheet.data(
                    XlsxWriter.Cell.integer(count),
                    XlsxWriter.Cell.text(zh),
                    XlsxWriter.Cell.text(en),
                    XlsxWriter.Cell.integer(share.visits),
                    XlsxWriter.Cell.integer(share.chosen),
                    XlsxWriter.Cell.percent(ratio(share.chosen, share.visits)),
                    XlsxWriter.Cell.percent(baseline / 100.0D),
                    XlsxWriter.Cell.decimal(mult),
                    XlsxWriter.Cell.percent(altPercent / 100.0D),
                    XlsxWriter.Cell.text(note));
        }
        return count;
    }

    private static Map<Card, Rate<Card>> altFor(ClassStat cs, int act) {
        List<Rate<Card>> list;
        switch (act) {
            case 1: list = cs.campfireUpgradeAltAct1; break;
            case 2: list = cs.campfireUpgradeAltAct2; break;
            case 3: list = cs.campfireUpgradeAltAct3; break;
            case 4: list = cs.campfireUpgradeAltAct4; break;
            default: list = cs.campfireUpgradeAltTotal; break;
        }
        Map<Card, Rate<Card>> map = new java.util.HashMap<Card, Rate<Card>>();
        if (list != null) {
            for (Rate<Card> rate : list) {
                if (rate != null && rate.what != null) {
                    map.put(rate.what, rate);
                }
            }
        }
        return map;
    }
}
