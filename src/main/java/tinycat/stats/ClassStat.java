package tinycat.stats;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.screens.stats.BossRelicChoiceStats;
import com.megacrit.cardcrawl.screens.stats.CampfireChoice;
import com.megacrit.cardcrawl.screens.stats.CardChoiceStats;
import com.megacrit.cardcrawl.screens.stats.EventStats;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 职业统计聚合类，将多个对局的数据汇总为统计指标。
 * 
 * 这是 TinyCat 最核心的统计模型，负责：
 * 1. 聚合所有对局的基础数据（胜率、时间、分数、金钱等）
 * 2. 统计卡牌选取率和胜率（按 Act1/After Act1 分别计数）
 * 3. 统计 Neow 奖励选取率和胜率
 * 4. 统计 Boss 遗物选取率和胜率（按 Act 分类，含交换遗物）
 * 5. 统计战斗遭遇的 HP 损失、死亡率和药水使用
 * 6. 统计事件胜率（按 Act 分类）
 * 7. 统计删牌来源（商店/事件/Neow/篝火/空鸟笼）
 * 8. 统计火堆和商店行动明细
 * 9. 统计元成长极限值（最大遗物数、金币等）
 * 
 * 数据流：Run 列表 -> StatCollector 内部聚合 -> ClassStat 公开字段 -> UI 渲染
 * 
 * 注意：rotate 字段为 true 表示轮换统计（全角色），false 表示单角色统计。
 */
public class ClassStat {
    public final boolean rotate;
    public long playTime = 0;
    public long fastestTime = Integer.MAX_VALUE;
    public int numVictory = 0;
    public int numDeath = 0;
    public int totalFloorsClimbed = 0;
    public int bossKilled = 0;
    public int enemyKilled = 0;
    public int bestWinStreak = 0;
    public int highestScore = 0;
    public Mean averageWinningTime;
    public Mean averageDeckSize;
    public Mean averageScore;
    public Mean averageGold;  // 平均累计获得金币（估算）
    public int goldEstimatedRuns = 0;
    public int goldExactRuns = 0;
    public int floor0HPEstimatedRuns = 0;
    public int minDeckSize = 0;
    public int maxDeckSize = 0;
    public List<Rate<Card>> cardPicksAct1;
    public List<Rate<Card>> cardPicksAfterAct1;
    public List<Rate<Card>> cardWinRate;
    public List<Rate<Neow>> neowWinRate;
    public List<Rate<Neow>> neowPickRate;
    /**
     * 尼奥选项的两两对决矩阵：`neowBeats[A].get(B)` = A 和 B 同场且 A 被选中的次数。
     * 每局是「1 选中 + 3 跳过」，等价于 3 场 A 对 B 的对决，用于 Bradley-Terry 标准化。
     */
    public final Map<Neow, Map<Neow, Integer>> neowBeats = new HashMap<>();
    public List<Rate<BossRelic>> bossRelicPickRateAct1;
    public List<Rate<BossRelic>> bossRelicPickRateAct2;
    public List<Rate<BossRelic>> bossRelicWinRateAct1;
    public List<Rate<BossRelic>> bossRelicWinRateAct2;
    public List<Rate<BossRelic>> bossRelicWinRateSwap;
    public Map<Integer, List<Mean>> averageDamageTaken = new HashMap<>();
    public Map<Integer, List<Mean>> averageActualHPLoss = new HashMap<>();
    public Map<Integer, List<Mean>> averageNoHealActualHPLoss = new HashMap<>();
    public Map<Integer, List<Mean>> averageHealActualHPLoss = new HashMap<>();
    public Map<Integer, List<Mean>> averageEventHPLoss = new HashMap<>();  // 事件战损
    public Map<Integer, List<Mean>> averageAllHPLoss = new HashMap<>();    // 每幕总战损（单层累加）
    public Map<Integer, List<Rate<String>>> encounterDeadRate = new HashMap<>();
    public Rate<String> nob = new Rate<>("nob survival rate");
    public Map<Integer, List<Mean>> averagePotionUse = new HashMap<>();
    public Map<Integer, Rate<Integer>> survivalRatePerAct = new HashMap<>();
    public List<Rate<String>> relicWinRate;
    public List<Rate<String>> relicPurchasedWinRate;

    public List<Rate<String>> eventWinRateAct1;
    public List<Rate<String>> eventWinRateAct2;
    public List<Rate<String>> eventWinRateAct3;
    
    // 删牌统计字段
    public int totalCardsPurged = 0;  // 总删牌数
    public int shopPurges = 0;  // 商店删牌（通过金币购买删牌服务，itemsPurged）
    public int neowPurges = 0;  // 开局删牌（尼奥斯）
    public int campfirePurges = 0;  // 篝火删牌
    public int emptyCagePurges = 0;  // 空鸟笼删牌（Boss 遗物强制 +2）
    public int eventPurges = 0;  // 事件删牌（Wheel of Change、The Cleric 等，包括花钱的事件）
    public Map<PurgeSource, Integer> purgesBySource = new HashMap<>();  // 按来源分类（SHOP, EVENT, NEOW, CAMPFIRE, EMPTY_CAGE）
    public Map<Integer, Integer> purgesByAct = new HashMap<>();  // 按幕分类
    
    // 问号和事件统计字段
    public int totalQuestionMarks = 0;  // 问号节点数量
    public Map<Integer, Integer> questionMarksByAct = new HashMap<>();  // 按幕分类的问号数量
    public int totalEvents = 0;  // 事件数量
    
    // 火堆行动统计字段（所有行动类型，SMITH按卡牌统计，其他按行动类型统计）
    public List<Rate<Object>> campfireActionsTotal = new ArrayList<>();  // 总统计
    public List<Rate<Object>> campfireActionsAct1 = new ArrayList<>();  // 第一幕
    public List<Rate<Object>> campfireActionsAct2 = new ArrayList<>();  // 第二幕
    public List<Rate<Object>> campfireActionsAct3 = new ArrayList<>();  // 第三幕
    public List<Rate<Object>> campfireActionsAct4 = new ArrayList<>();  // 第四幕

    // 火堆统一分母：index 0 = 四幕合计，1..4 = 各幕。
    // campfireVisits 是火堆访问次数（每次选择一个行动），campfireSmiths 是其中选升级的次数。
    public int[] campfireVisits = new int[5];
    public int[] campfireSmiths = new int[5];

    // 火堆选项权重：被选次数 ÷ 该选项真正出现的火堆次数。
    // 回忆只在 1-3 幕且尚未回忆时可选；抽烟/举重/挖掘需要对应遗物；
    // 睡觉总能选；升级要求卡组里还有可升级的未升级卡。
    public List<CampfireOption> campfireOptionsTotal = new ArrayList<>();
    public List<CampfireOption> campfireOptionsAct1 = new ArrayList<>();
    public List<CampfireOption> campfireOptionsAct2 = new ArrayList<>();
    public List<CampfireOption> campfireOptionsAct3 = new ArrayList<>();
    public List<CampfireOption> campfireOptionsAct4 = new ArrayList<>();
    /** 每次火堆的平均可选项数，以及「随机选一个」时每个选项的期望占比（100 / 平均可选项数）。 */
    public double[] campfireAvgOptions = new double[5];
    public double[] campfireOptionBaseline = new double[5];
    /** 回忆拆成「到达第四幕的对局（必须消耗一个火堆拿红钥匙）」与其余对局。 */
    public int[] campfireRecallForced = new int[5];
    public int[] campfireRecallOptional = new int[5];

    // 火堆升级权重（卡牌层）：win = 该卡被敲的次数，loss = 可选但没被敲的次数，total = 可选次数。
    // 每次锻造的分母是「当时卡组里还有未升级副本的卡牌种类数」，同名多张只算一个选项；
    // 全部副本已升级的卡会退出分母。四幕合计 = 各幕决策逐次累加。
    public List<Rate<Card>> campfireUpgradePrefTotal = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradePrefAct1 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradePrefAct2 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradePrefAct3 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradePrefAct4 = new ArrayList<>();
    public int[] campfireSmithUsable = new int[5];
    public double[] campfireUpgradeAvgOptions = new double[5];
    public double[] campfireUpgradeBaseline = new double[5];

    // A 口径（替补）：该幕锻造次数 ÷ 到该幕结束累计拥过的副本数（不扣后续移出）。
    // W 需要「当时卡组」，星盘/空鸟笼删了哪张没有日志；这类对局的 W 靠终局补差还原，
    // 一旦某次锻造的目标仍找不到，就以这一列为准 —— 它只依赖获得次数，不受删牌影响。
    public List<Rate<Card>> campfireUpgradeAltTotal = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradeAltAct1 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradeAltAct2 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradeAltAct3 = new ArrayList<>();
    public List<Rate<Card>> campfireUpgradeAltAct4 = new ArrayList<>();
    /** 重建覆盖：能定位到目标且当时未升级的锻造次数 / 失败次数（缺失或已是升级态）。 */
    public int[] campfireRebuildOk = new int[5];
    public int[] campfireRebuildMiss = new int[5];
    /** 受变化类遗物影响的对局数：星盘删 3 张、空鸟笼删 2 张都没有逐张日志。 */
    public int campfireAstrolabeRuns = 0;
    public int campfireEmptyCageRuns = 0;
    /** 重建失败的锻造按卡统计，界面在该卡后面标 *，提示以 A 口列为准。 */
    public Map<Card, Integer> campfireUpgradeMissCard = new HashMap<>();

    /**
     * 火堆决策分布：一次火堆的机会到底给了谁。
     *
     * 分母统一为「该幕火堆访问次数」，条目 = 每张被敲的卡 + 抽烟/举重/挖掘 + 非强制回忆。
     * 因为「敲某张卡」就是「选升级」的具体落点，所以各卡的占比之和正好等于升级在选项层的占比
     * （实测 664/710 = 93.5% = 升级占比），不会出现各层相加超过 100% 的重复计数。
     * 睡觉不入此表：它的约束是血量，与其他「花掉一次升级机会」的选项不是同一类制约。
     */
    public List<CampfireShare> campfireChoiceDistTotal = new ArrayList<>();
    public List<CampfireShare> campfireChoiceDistAct1 = new ArrayList<>();
    public List<CampfireShare> campfireChoiceDistAct2 = new ArrayList<>();
    public List<CampfireShare> campfireChoiceDistAct3 = new ArrayList<>();
    public List<CampfireShare> campfireChoiceDistAct4 = new ArrayList<>();

    /**
     * 火堆统一榜的一行：what 为 Card 或行动 key，占比 = chosen ÷ visits。
     *
     * 「升级」不出现在这里 —— 它被拆成每张可敲的牌各自一行，和抽烟/举重/挖掘/回忆同台竞争；
     * 睡觉也不在这里（约束是血量，单独统计）。
     */
    public static final class CampfireShare implements SampleSize {
        public final Object what;
        public final int chosen;
        public final int visits;
        public final boolean card;

        CampfireShare(Object what, int chosen, int visits, boolean card) {
            this.what = what;
            this.chosen = chosen;
            this.visits = visits;
            this.card = card;
        }

        public double percent() {
            return visits <= 0 ? 0.0D : 100.0D * chosen / visits;
        }

        @Override
        public int getSampleSize() {
            return visits;
        }
    }

    /** 一次火堆决策里某个选项的可选/被选次数。 */
    public static final class CampfireOption implements SampleSize {
        public final String key;
        public final int chosen;
        public final int available;
        /** 在这些「该选项可选」的火堆里，若随机挑一个选项，该选项的期望占比（%）。 */
        public final double baseline;
        /** 仅回忆使用：强制（该局到达第四幕）与非强制次数。 */
        public final int forced;
        public final int optional;

        CampfireOption(String key, int chosen, int available, double baseline, int forced, int optional) {
            this.key = key;
            this.chosen = chosen;
            this.available = available;
            this.baseline = baseline;
            this.forced = forced;
            this.optional = optional;
        }

        public double percent() {
            return available <= 0 ? 0.0D : 100.0D * chosen / available;
        }

        public double multiple() {
            return baseline <= 0.0D ? 0.0D : percent() / baseline;
        }

        @Override
        public int getSampleSize() {
            return available;
        }
    }
    
    // 商店行动统计字段（购买的卡牌、遗物、药水、删牌等）
    public List<Rate<Object>> shopActionsTotal = new ArrayList<>();  // 总统计
    public List<Rate<Object>> shopActionsAct1 = new ArrayList<>();  // 第一幕
    public List<Rate<Object>> shopActionsAct2 = new ArrayList<>();  // 第二幕
    public List<Rate<Object>> shopActionsAct3 = new ArrayList<>();  // 第三幕
    public List<Rate<Object>> shopActionsAct4 = new ArrayList<>();  // 第四幕
    
    public MetaScaling metaScaling = new MetaScaling();

    /**
     * 统计收集器（内部聚合类），遍历对局列表并逐步累加各维度的统计数据。
     * 
     * 使用方式：
     * 1. new StatCollector(runs) 创建实例，构造函数自动遍历所有对局
     * 2. 调用 finalise() 完成排序和最终计算
     * 3. 通过 toClassStat() 将聚合数据导出为 ClassStat 公开字段
     * 
     * 聚合维度包括：卡牌选取、Neow奖励、Boss遗物、战斗遭遇、事件、删牌、火堆、商店等。
     */
    private static class StatCollector {
        Mean averageWinningTime = new Mean("Average Time (wins)");
        Mean averageDeckSize = new Mean("Average Deck Size");
        Mean averageScore = new Mean("Average Score");
        Mean averageGold = new Mean("Average Gold");  // 平均金钱
        Map<Card, Rate<Card>> cardPicksAct1 = new HashMap<>();
        Map<Card, Rate<Card>> cardPicksAfterAct1 = new HashMap<>();
        Map<Card, Rate<Card>> cardWinRate = new HashMap<>();
        Map<Neow, Rate<Neow>> neowWinRate = new HashMap<>();
        Map<Neow, Rate<Neow>> neowPickRate = new HashMap<>();
        Map<Neow, Map<Neow, Integer>> neowBeats = new HashMap<>();
        Map<BossRelic, Rate<BossRelic>> bossRelicPickRateAct1 = new HashMap<>();
        Map<BossRelic, Rate<BossRelic>> bossRelicPickRateAct2 = new HashMap<>();
        Map<BossRelic, Rate<BossRelic>> bossRelicWinRateAct1 = new HashMap<>();
        Map<BossRelic, Rate<BossRelic>> bossRelicWinRateAct2 = new HashMap<>();
        Map<BossRelic, Rate<BossRelic>> bossRelicWinRateSwap = new HashMap<>();
        Map<Integer, Map<String, Mean>> encounterHPLossMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};
        Map<Integer, Map<String, Mean>> encounterActualHPLossMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};
        Map<Integer, Map<String, Mean>> encounterNoHealActualHPLossMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};
        Map<Integer, Map<String, Mean>> encounterHealActualHPLossMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};
        Map<Integer, List<Mean>> avgEncounterDamage = new HashMap<>();
        Map<Integer, List<Mean>> avgEncounterActualHPLoss = new HashMap<>();
        Map<Integer, List<Mean>> avgEncounterNoHealActualHPLoss = new HashMap<>();
        Map<Integer, List<Mean>> avgEncounterHealActualHPLoss = new HashMap<>();

        // 单层战损总计：累加每一次对局的 actualHPLoss 独立记录（不是平均值），用于插入序列开头
        Map<Integer, Mean> totalActualHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("战斗总计"));
            put(2, new Mean("战斗总计"));
            put(3, new Mean("战斗总计"));
            put(4, new Mean("战斗总计"));
        }};
        Map<Integer, Mean> totalNoHealActualHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("战斗总计"));
            put(2, new Mean("战斗总计"));
            put(3, new Mean("战斗总计"));
            put(4, new Mean("战斗总计"));
        }};
        Map<Integer, Mean> totalNonBossActualHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("非Boss战斗总计"));
            put(2, new Mean("非Boss战斗总计"));
            put(3, new Mean("非Boss战斗总计"));
            put(4, new Mean("非Boss战斗总计"));
        }};
        Map<Integer, Mean> totalHealActualHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("战斗总计"));
            put(2, new Mean("战斗总计"));
            put(3, new Mean("战斗总计"));
            put(4, new Mean("战斗总计"));
        }};

        // 事件战损统计（直接累加 damage_taken，与 Python 脚本一致）
        Map<Integer, Map<String, Mean>> encounterEventHPLossMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};
        Map<Integer, List<Mean>> avgEncounterEventHPLoss = new HashMap<>();

        // 事件战损总计
        Map<Integer, Mean> totalEventHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("事件总计"));
            put(2, new Mean("事件总计"));
            put(3, new Mean("事件总计"));
            put(4, new Mean("事件总计"));
        }};

        // 每幕总战损（战斗实际 HP 变化 + 事件 damage_taken），按 run 汇总
        Map<Integer, Mean> totalAllHPLossPerAct = new HashMap<Integer, Mean>() {{
            put(1, new Mean("每幕总计"));
            put(2, new Mean("每幕总计"));
            put(3, new Mean("每幕总计"));
            put(4, new Mean("每幕总计"));
        }};

        Map<Integer, Map<String, Rate<String>>> encounterDeadRate = new HashMap<Integer, Map<String, Rate<String>>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};

        Map<Integer, Map<String, Mean>> encounterPotionUseMap = new HashMap<Integer, Map<String, Mean>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
            put(4, new HashMap<>());
        }};

        Map<Integer, List<Mean>> averagePotionUse = new HashMap<>();
        Rate<String> nob = new Rate<>("nob survival rate");
        Map<Integer, Rate<Integer>> survivalRatePerAct = new HashMap<Integer, Rate<Integer>>() {{
            put(1, new Rate<>(1));
            put(2, new Rate<>(2));
            put(3, new Rate<>(3));
            put(4, new Rate<>(4));
        }};

        Map<String, Rate<String>> relicPurchasedWinRate = new HashMap<>();
        Map<String, Rate<String>> relicWinRate = new HashMap<>();
        Map<Integer, Map<String, Rate<String>>> eventWinRate = new HashMap<Integer, Map<String, Rate<String>>>() {{
            put(1, new HashMap<>());
            put(2, new HashMap<>());
            put(3, new HashMap<>());
        }};
        
        // 删牌统计字段
        int totalCardsPurged = 0;  // 总删牌数
        int shopPurges = 0;  // 商店删牌 (itemsPurged)
        int neowPurges = 0;
        int campfirePurges = 0;
        int emptyCagePurges = 0;
        int eventPurges = 0;  // 事件删牌 (如 Wheel of Change、The Cleric 等)
        Map<PurgeSource, Integer> purgesBySource = new HashMap<PurgeSource, Integer>() {{
            put(PurgeSource.SHOP, 0);
            put(PurgeSource.EVENT, 0);
            put(PurgeSource.NEOW, 0);
            put(PurgeSource.CAMPFIRE, 0);
            put(PurgeSource.EMPTY_CAGE, 0);
            put(PurgeSource.OTHER, 0);
        }};
        Map<Integer, Integer> purgesByAct = new HashMap<Integer, Integer>() {{
            put(1, 0);
            put(2, 0);
            put(3, 0);
            put(4, 0);
        }};
                
        // 问号和事件统计字段
        int totalQuestionMarks = 0;
        Map<Integer, Integer> questionMarksByAct = new HashMap<Integer, Integer>() {{
            put(1, 0);
            put(2, 0);
            put(3, 0);
            put(4, 0);
        }};
        int totalEvents = 0;
        
        // 火堆行动统计字段（所有行动类型，SMITH按卡牌统计，其他按行动类型统计）
        Map<Object, Rate<Object>> campfireActionsTotal = new HashMap<>();
        Map<Object, Rate<Object>> campfireActionsAct1 = new HashMap<>();
        Map<Object, Rate<Object>> campfireActionsAct2 = new HashMap<>();
        Map<Object, Rate<Object>> campfireActionsAct3 = new HashMap<>();
        Map<Object, Rate<Object>> campfireActionsAct4 = new HashMap<>();

        // 火堆选项权重：index 0 = 四幕合计，1..4 = 各幕；第二个下标见 OPTION_KEYS。
        private static final String[] OPTION_KEYS = {"SMITH", "REST", "RECALL", "PURGE", "LIFT", "DIG"};
        private final int[][] optionChosen = new int[5][6];
        private final int[][] optionAvailable = new int[5][6];
        /** sum(1 / 当次可选项数)，用于算「随机选一个」的期望占比。 */
        private final double[][] optionInvOptions = new double[5][6];
        private final int[] optionVisits = new int[5];
        private final int[] recallForced = new int[5];
        private final int[] recallOptional = new int[5];
        private final int[] smithUsable = new int[5];
        private final double[] upgradeExposureSum = new double[5];
        /**
         * 每次火堆的「竞争者池」大小之和。
         *
         * 池子 = 还能敲的卡牌种类 + 可用的抽烟/举重/挖掘 + 可用的回忆。
         * 「升级」不单独算一个条目：它就是通往每张牌的入口，拆开以后每张牌各自是一个选项。
         * 睡觉不进池子 —— 它的约束是血量，和「敲哪张 / 用哪个遗物」不是同一类选择，单独统计。
         */
        private final int[] poolSizeSum = new int[5];
        /** 重建覆盖计数与受影响局数（星盘/空鸟笼）。 */
        private final int[] campfireRebuildOk = new int[5];
        private final int[] campfireRebuildMiss = new int[5];
        private int campfireAstrolabeRuns = 0;
        private int campfireEmptyCageRuns = 0;
        private final Map<Card, Integer> campfireMissCard = new HashMap<>();

        // 火堆偏好（卡牌层）：逐次锻造的「可选卡牌种类」与「实际被敲的那张」
        Map<Card, Rate<Card>> campfireUpgradePrefTotal = new HashMap<>();
        Map<Card, Rate<Card>> campfireUpgradePrefAct1 = new HashMap<>();
        Map<Card, Rate<Card>> campfireUpgradePrefAct2 = new HashMap<>();
        Map<Card, Rate<Card>> campfireUpgradePrefAct3 = new HashMap<>();
        Map<Card, Rate<Card>> campfireUpgradePrefAct4 = new HashMap<>();
        /** A 口径的分母：到该幕结束为止「累计拥过」的副本数（不扣后续移出）。index 0 = 四幕合计。 */
        private final List<Map<Card, Integer>> altOwnedByAct = new ArrayList<>();
        
        // 商店行动统计字段（购买的卡牌、遗物、药水、删牌等）
        Map<Object, Rate<Object>> shopActionsTotal = new HashMap<>();
        Map<Object, Rate<Object>> shopActionsAct1 = new HashMap<>();
        Map<Object, Rate<Object>> shopActionsAct2 = new HashMap<>();
        Map<Object, Rate<Object>> shopActionsAct3 = new HashMap<>();
        Map<Object, Rate<Object>> shopActionsAct4 = new HashMap<>();
        
        // 记录各幕遇到的商店次数（用于统计商店数量）
        // 分别记录胜利和失败的样本（使用计数器，不去重）
        int shopCountAct1Win = 0;
        int shopCountAct1Loss = 0;
        int shopCountAct2Win = 0;
        int shopCountAct2Loss = 0;
        int shopCountAct3Win = 0;
        int shopCountAct3Loss = 0;
        int shopCountAct4Win = 0;
        int shopCountAct4Loss = 0;
        
        // 记录各幕有购买行为的商店次数（用于统计有效商店数，使用计数器）
        int shopValidCountAct1Win = 0;
        int shopValidCountAct1Loss = 0;
        int shopValidCountAct2Win = 0;
        int shopValidCountAct2Loss = 0;
        int shopValidCountAct3Win = 0;
        int shopValidCountAct3Loss = 0;
        int shopValidCountAct4Win = 0;
        int shopValidCountAct4Loss = 0;
        int goldEstimatedRuns = 0;
        int goldExactRuns = 0;
        int floor0HPEstimatedRuns = 0;
        private void deckSizeStats(Run run) {
            averageDeckSize.add(run.masterDeck.size());
            averageScore.add(run.score);
            averageGold.add(run.gold);  // 累计获得金币（估算）
            if (run.goldEstimated) {
                goldEstimatedRuns++;
            } else {
                goldExactRuns++;
            }
            if (run.floor0HPEstimated) {
                floor0HPEstimatedRuns++;
            }
        }

        private void cardPickStats(Run run) {
            if (run.cardChoices == null) return;
            
            for (CardChoice c : run.cardChoices) {
                Map<Card, Rate<Card>> map = c.floor >= 17 ? cardPicksAfterAct1 : cardPicksAct1;
                Rate<Card> pickedRate = map.computeIfAbsent(c.picked, k -> new Rate<>(k));
                pickedRate.win++;
                
                for (Card skipped : c.skipped) {
                    Rate<Card> skipRate = map.computeIfAbsent(skipped, k -> new Rate<>(k));
                    skipRate.loss++;
                }
            }
        }

        private void deckStats(Run run) {
            if (run.masterDeck == null) return;
            
            for (Card c : run.masterDeck) {
                Card baseCard = Card.fromStringIgnoreUpgrades(c.name);
                Rate<Card> rate = cardWinRate.computeIfAbsent(baseCard, k -> new Rate<>(k));
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }
            }
        }

        private void neowStats(Run run) {
            if (run.neowPicked != null) {
                Rate<Neow> rate = neowWinRate.computeIfAbsent(run.neowPicked, k -> new Rate<>(k));
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }
            }

            if (run.neowPicked != null && !run.neowSkipped.isEmpty()) {
                Rate<Neow> pickRate = neowPickRate.computeIfAbsent(run.neowPicked, k -> new Rate<>(k));
                pickRate.win++;
                Map<Neow, Integer> beaten = neowBeats.computeIfAbsent(run.neowPicked,
                        k -> new HashMap<>());
                run.neowSkipped.forEach(neow -> {
                    Rate<Neow> skipRate = neowPickRate.computeIfAbsent(neow, k -> new Rate<>(k));
                    skipRate.loss++;
                    // 选中的这个选项，在同场时赢过每个被跳过的选项一次
                    beaten.merge(neow, 1, Integer::sum);
                });
            }
        }

        private void bossRelicStats(Run run) {
            if (run.bossSwapRelic != null) {
                Rate<BossRelic> rate = bossRelicWinRateSwap.computeIfAbsent(run.bossSwapRelic, k -> new Rate<>(k));
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }
            }

            for (int i = 0; i < run.bossRelicChoiceStats.size(); ++i) {
                BossRelicChoiceStats bs = run.bossRelicChoiceStats.get(i);
                int act = i == 0 ? 1 : 2;
                List<String> skipped = new ArrayList<>(bs.not_picked);
                Map<BossRelic, Rate<BossRelic>> pickRateMap = act == 1 ? bossRelicPickRateAct1 : bossRelicPickRateAct2;
                Map<BossRelic, Rate<BossRelic>> winRateMap = act == 1 ? bossRelicWinRateAct1 : bossRelicWinRateAct2;

                String picked = bs.picked;
                if (picked == null || picked.isEmpty() || picked.equals("SKIP")) {
                    picked = "SKIP";
                } else {
                    skipped.add("SKIP");
                }

                BossRelic p = new BossRelic(picked, act);
                Rate<BossRelic> pickRate = pickRateMap.computeIfAbsent(p, k -> new Rate<>(k));
                pickRate.win++;

                Rate<BossRelic> winRate = winRateMap.computeIfAbsent(p, k -> new Rate<>(k));
                if (run.isHeartKill) {
                    winRate.win++;
                } else {
                    winRate.loss++;
                }

                for (String s : skipped) {
                    BossRelic p1 = new BossRelic(s, act);
                    Rate<BossRelic> skipRate = pickRateMap.computeIfAbsent(p1, k -> new Rate<>(k));
                    skipRate.loss++;
                }
            }
        }


        private void encounterStats(Run run) {
            // 按 run 累加 per-act 战斗战损（用于正确计算平均值：sum / runCount，而非 sum / encounterCount）
            Map<Integer, Double> runCombatSum = new HashMap<>();
            Map<Integer, Double> runNoHealSum = new HashMap<>();
            Map<Integer, Double> runHealSum = new HashMap<>();
            Map<Integer, Double> runNonBossCombatSum = new HashMap<>();

            for (EncounterStats es : run.encounterStats) {
                // 跳过事件条目（以"事件:"前缀标记），事件在 eventStats 中单独处理
                if (es.enemies.startsWith("事件:")) {
                    continue;
                }
                if (es.enemies.equals("Gremlin Nob")) {
                    nob.win++;
                }
                int act = run.getActForStats(es.floor);
                if (act == 0) {
                    continue;
                }
                int potsUsed = es.potionsUsed;
                if (potsUsed >= 0) {
                    encounterPotionUseMap.get(act).putIfAbsent(es.enemies, new Mean(es.enemies));
                    encounterPotionUseMap.get(act).get(es.enemies).add(potsUsed);
                }

                Map<String, Rate<String>> ded = encounterDeadRate.get(act);
                ded.putIfAbsent(es.enemies, new Rate<>(es.enemies));
                ded.get(es.enemies).loss++;

                Map<String, Mean> m = encounterHPLossMap.get(act);
                m.putIfAbsent(es.enemies, new Mean(es.enemies));
                double damage = es.damage;
                if (damage >= 99999) {
                    damage -= 99999;
                }
                m.get(es.enemies).add(damage);

                Map<String, Mean> am = encounterActualHPLossMap.get(act);
                am.putIfAbsent(es.enemies, new Mean(es.enemies));
                am.get(es.enemies).add(es.actualHPLoss);

                // 按 run 累加 per-act 战斗战损，后续统一 add（一次/run/act），确保 Mean 的 sampleSize = runCount
                runCombatSum.merge(act, (double) es.actualHPLoss, Double::sum);
                // 非Boss战斗战损：排除 Boss
                if (!BOSS_ACT.containsKey(es.enemies)) {
                    runNonBossCombatSum.merge(act, (double) es.actualHPLoss, Double::sum);
                }

                // 分裂统计：按是否有回复血量分组
                // Ironclad: Burning Blood 自动回6，>6 才算有额外回复
                // 其他角色: >0 即有回复
                double healing = damage - es.actualHPLoss;
                int healThreshold = run.playerClass == AbstractPlayer.PlayerClass.IRONCLAD ? 6 : 0;
                boolean hasHealing = healing > healThreshold;

                if (hasHealing) {
                    Map<String, Mean> hm = encounterHealActualHPLossMap.get(act);
                    hm.putIfAbsent(es.enemies, new Mean(es.enemies));
                    hm.get(es.enemies).add(es.actualHPLoss);
                    runHealSum.merge(act, (double) es.actualHPLoss, Double::sum);
                } else {
                    Map<String, Mean> nm = encounterNoHealActualHPLossMap.get(act);
                    nm.putIfAbsent(es.enemies, new Mean(es.enemies));
                    nm.get(es.enemies).add(es.actualHPLoss);
                    runNoHealSum.merge(act, (double) es.actualHPLoss, Double::sum);
                }
            }

            // 将 per-run 战斗战损累加到总计累加器（一次/run/act，确保 sampleSize = runCount）
            for (int act : new int[]{1, 2, 3, 4}) {
                Double combatSum = runCombatSum.get(act);
                if (combatSum != null) {
                    totalActualHPLossPerAct.get(act).add(combatSum);
                }
                Double nonBossSum = runNonBossCombatSum.get(act);
                if (nonBossSum != null) {
                    totalNonBossActualHPLossPerAct.get(act).add(nonBossSum);
                }
                Double noHealSum = runNoHealSum.get(act);
                if (noHealSum != null) {
                    totalNoHealActualHPLossPerAct.get(act).add(noHealSum);
                }
                Double healSum = runHealSum.get(act);
                if (healSum != null) {
                    totalHealActualHPLossPerAct.get(act).add(healSum);
                }
            }

            if (run.killedBy != null && !run.killedBy.isEmpty()) {
                String killedBy = run.killedBy;
                if (killedBy.equals("Gremlin Nob")) {
                    nob.win--;
                    nob.loss++;
                }
                int act = run.getLastActForStats();
                Rate<String> k = encounterDeadRate.getOrDefault(act, new HashMap<>()).get(killedBy);
                if (k != null) {
                    k.win++;
                    k.loss--;
                }
            }
        }

        // Boss -> Act 映射，用于把 Boss 从“非 Boss 战损”中排除
        private static final Map<String, Integer> BOSS_ACT = new HashMap<String, Integer>() {{
            put("Hexaghost", 1); put("Slime Boss", 1); put("The Guardian", 1);
            put("Collector", 2); put("Automaton", 2); put("Champ", 2);
            put("Time Eater", 3); put("Awakened One", 3); put("Donu and Deca", 3);
        }};

        private void finaliseEncounterStats() {
            encounterHPLossMap.forEach((act, xs) -> {
                avgEncounterDamage.put(act, sortedMapValues(xs));
            });
            encounterActualHPLossMap.forEach((act, xs) -> {
                avgEncounterActualHPLoss.put(act, sortedMapValues(xs));
            });
            encounterNoHealActualHPLossMap.forEach((act, xs) -> {
                avgEncounterNoHealActualHPLoss.put(act, sortedMapValues(xs));
            });
            encounterHealActualHPLossMap.forEach((act, xs) -> {
                avgEncounterHealActualHPLoss.put(act, sortedMapValues(xs));
            });

            encounterPotionUseMap.forEach((act, xs) -> {
                averagePotionUse.put(act, sortedMapValues(xs));
            });

            // 事件战损排序
            encounterEventHPLossMap.forEach((act, xs) -> {
                avgEncounterEventHPLoss.put(act, sortedMapValues(xs));
            });
        }

        private void survivalRatePerAct(Run run) {
            Rate<Integer> act1 = survivalRatePerAct.get(1);
            Rate<Integer> act2 = survivalRatePerAct.get(2);
            Rate<Integer> act3 = survivalRatePerAct.get(3);
            Rate<Integer> act4 = survivalRatePerAct.get(4);

            if (run.isHeartKill) {
                act1.win++;
                act2.win++;
                act3.win++;
                act4.win++;
                return;
            }

            int floor = run.floorsReached;
            int act = run.getActForStats(floor);
            if (act == 0) {
                return;
            }

            switch (act) {
                case 1:
                    act1.loss++;
                    break;
                case 2:
                    act1.win++;
                    act2.loss++;
                    break;
                case 3:
                    act1.win++;
                    act2.win++;
                    act3.loss++;
                    break;
                case 4:
                    act1.win++;
                    act2.win++;
                    act3.win++;
                    act4.loss++;
                    break;
            }
        }

        private void relicStats(Run run) {
            run.relicsPurchased.forEach(r -> {
                relicPurchasedWinRate.putIfAbsent(r, new Rate<>(r));
                Rate<String> rate = relicPurchasedWinRate.get(r);
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }
            });
            run.relics.forEach(r -> {
                relicWinRate.putIfAbsent(r, new Rate<>(r));
                Rate<String> rate = relicWinRate.get(r);
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }
            });
        }

        private void runTimeStats(Run run) {
            if (run.isHeartKill) {
                averageWinningTime.add(run.playtime);
            }
        }

        private void allHPLossStats(Run run) {
            Map<Integer, Double> runAllHPLossSum = new HashMap<>();
            for (EncounterStats es : run.encounterStats) {
                if (es == null || es.enemies == null || es.enemies.startsWith("事件:")) {
                    continue;
                }
                int act = run.getActForStats(es.floor);
                if (act == 0) {
                    continue;
                }
                runAllHPLossSum.merge(act, (double) es.actualHPLoss, Double::sum);
            }
            if (run.eventStats != null) {
                for (com.megacrit.cardcrawl.screens.stats.EventStats event : run.eventStats) {
                    if (event.damage_taken > 0) {
                        int act = run.getActForStats(event.floor);
                        if (act == 0) {
                            continue;
                        }
                        runAllHPLossSum.merge(act, (double) event.damage_taken, Double::sum);
                    }
                }
            }
            // 每幕总战损按“每局每幕一个样本”统计；没有战损的幕也要记 0，
            // 否则平均值只会包含发生过战损的对局，结果会偏高。
            // Boss 后的奖励/宝箱层不算进入下一幕。
            int lastAct = run.getLastActForStats();
            for (int act = 1; act <= lastAct; act++) {
                runAllHPLossSum.putIfAbsent(act, 0.0D);
            }
            for (Map.Entry<Integer, Double> entry : runAllHPLossSum.entrySet()) {
                Mean total = totalAllHPLossPerAct.get(entry.getKey());
                if (total != null) {
                    total.add(entry.getValue());
                }
            }
        }

        private void eventStats(Run run) {
            if (run.eventStats == null) return;
            
            // 按 run 累加 per-act 事件战损（用于正确计算平均值：sum / runCount）
            Map<Integer, Double> runEventSum = new HashMap<>();
            
            for (com.megacrit.cardcrawl.screens.stats.EventStats e : run.eventStats) {
                int act = run.getActForStats(e.floor);
                if (act == 0) {
                    continue;
                }
                String event = e.event_name;
                Map<String, Rate<String>> m = eventWinRate.get(act);
                if (m == null) continue;
                
                Rate<String> rate = m.computeIfAbsent(event, k -> new Rate<>(event));
                if (run.isHeartKill) {
                    rate.win++;
                } else {
                    rate.loss++;
                }

                // 事件战损收集：直接使用 damage_taken（与 Python 脚本一致）
                int eventDamage = e.damage_taken;
                if (eventDamage > 0) {
                    Map<String, Mean> evMap = encounterEventHPLossMap.get(act);
                    if (evMap != null) {
                        evMap.putIfAbsent(event, new Mean(event));
                        evMap.get(event).add(eventDamage);
                        runEventSum.merge(act, (double) eventDamage, Double::sum);
                    }
                }
            }
            
            // 将 per-run 事件战损累加到总计累加器（一次/run/act，确保 sampleSize = runCount）
            for (int act : new int[]{1, 2, 3, 4}) {
                Double eventSum = runEventSum.get(act);
                if (eventSum != null) {
                    totalEventHPLossPerAct.get(act).add(eventSum);
                }
            }
        }
        
        /**
         * 统计删牌数据
         * 
         * 删牌来源（5 类）：
         * 1. 开局删牌（neowPurges）- 尼奥斯开局选项
         * 2. 商店删牌（shopPurges）- 通过金币购买删牌服务（itemsPurged）
         * 3. 事件删牌（eventPurges）- 如 Wheel of Change、The Cleric 等事件（包括 Face Trader 选择鸟）
         * 4. 篝火删牌（campfirePurges）- 篝火选择删牌
         * 5. Boss 遗物删牌（emptyCagePurges）- 空鸟笼 Boss 遗物强制 +2（JSON 不记录）
         * 
         * 重要说明：
         * - 开局删牌：游戏开始时尼奥斯祝福/诅咒导致的删牌
         * - 商店删牌：仅指在商店界面购买的删牌服务（items_purged 数组）
         * - 事件删牌：所有事件导致的删牌，包括需要花钱的事件（如 The Cleric 捐金币删牌）
         *   注意：Face Trader 事件选择鸟获得空鸟笼，这个行为本身算事件删牌
         * - 篝火删牌：篝火选择 PURGE 选项
         * - Boss 遗物删牌：空鸟笼（Empty Cage）是 Boss 遗物，获得时强制删除 2 张牌。
         *   这是独立的删牌来源，JSON 文件不记录，直接 +2 补偿统计
         *   不计入其他任何分类，只计入总删牌数
         */
        private void purgeStats(Run run) {
            // 1. 统计商店删牌（来自 itemsPurged）
            if (run.itemsPurged != null && run.itemsPurgedFloors != null) {
                for (int i = 0; i < run.itemsPurged.size(); i++) {
                    Integer floor = (i < run.itemsPurgedFloors.size()) ? run.itemsPurgedFloors.get(i) : null;
                        
                    // 确定幕
                    if (floor != null) {
                        int act = run.getAct(floor);
                        purgesByAct.put(act, purgesByAct.get(act) + 1);
                    }
                        
                    // 商店删牌
                    purgesBySource.put(PurgeSource.SHOP, purgesBySource.get(PurgeSource.SHOP) + 1);
                    shopPurges++;
                }
            }
                
            // 2. 统计事件删牌（来自 event_stats）
            if (run.eventStats != null) {
                for (com.megacrit.cardcrawl.screens.stats.EventStats event : run.eventStats) {
                    if (event.cards_removed != null) {
                        for (String ignored : event.cards_removed) {
                            // 确定幕
                            int act = run.getAct(event.floor);
                            purgesByAct.put(act, purgesByAct.get(act) + 1);
                                
                            // 所有事件导致的删牌都算事件删牌
                            purgesBySource.put(PurgeSource.EVENT, purgesBySource.get(PurgeSource.EVENT) + 1);
                            eventPurges++;  // 统计事件删牌
                        }
                    }
                }
            }
                
            // 3. 统计开局删牌（来自 neow_bonus_log）
            if (run.neowBonusLog != null && run.neowBonusLog.cardsRemoved != null) {
                for (String ignored : run.neowBonusLog.cardsRemoved) {
                    purgesBySource.put(PurgeSource.NEOW, purgesBySource.get(PurgeSource.NEOW) + 1);
                    neowPurges++;
                }
            }
                
            // 4. 统计篝火删牌（来自 campfire_choices，key="PURGE"）
            if (run.campfireChoices != null) {
                for (com.megacrit.cardcrawl.screens.stats.CampfireChoice choice : run.campfireChoices) {
                    if ("PURGE".equals(choice.key) && choice.data != null) {
                        // 确定幕
                        int act = run.getAct(choice.floor);
                        purgesByAct.put(act, purgesByAct.get(act) + 1);
                            
                        // 篝火删牌
                        purgesBySource.put(PurgeSource.CAMPFIRE, purgesBySource.get(PurgeSource.CAMPFIRE) + 1);
                        campfirePurges++;
                    }
                }
            }
                
            // 5. 统计总删牌数（直接统计所有 cards_removed 的总数 + 篝火删牌 + 空鸟笼强制删牌）
            if (run.itemsPurged != null) {
                totalCardsPurged += run.itemsPurged.size();
            }
            if (run.eventStats != null) {
                for (com.megacrit.cardcrawl.screens.stats.EventStats event : run.eventStats) {
                    if (event.cards_removed != null) {
                        totalCardsPurged += event.cards_removed.size();
                    }
                }
            }
            if (run.neowBonusLog != null && run.neowBonusLog.cardsRemoved != null) {
                totalCardsPurged += run.neowBonusLog.cardsRemoved.size();
            }
            // 加上篝火删牌
            if (run.campfireChoices != null) {
                for (com.megacrit.cardcrawl.screens.stats.CampfireChoice choice : run.campfireChoices) {
                    if ("PURGE".equals(choice.key) && choice.data != null) {
                        totalCardsPurged++;
                    }
                }
            }
            
            // 空鸟笼 Boss 遗物：强制删除 2 张牌 (不被 JSON 记录，需要手动 +2)
            if (run.hasEmptyCage()) {
                totalCardsPurged += 2;
                emptyCagePurges += 2;
                purgesBySource.put(PurgeSource.EMPTY_CAGE, purgesBySource.get(PurgeSource.EMPTY_CAGE) + 2);
            }
        }
                
        /**
         * 统计问号节点和事件数量
         * 直接遍历 path_taken 统计玩家实际走过的问号
         */
        private void questionMarkAndEventStats(Run run) {
            // 直接遍历 path_taken 统计问号，和Python脚本逻辑一致
            if (run.runData.path_taken != null) {
                for (int floor_idx = 0; floor_idx < run.runData.path_taken.size(); floor_idx++) {
                    String node_type = run.runData.path_taken.get(floor_idx);
                    if ("?".equals(node_type) || "EVENT".equals(node_type)) {
                        int floor = floor_idx + 1;  // 索引从0开始，楼层从1开始
                        int act = run.getAct(floor);
                        totalQuestionMarks += 1;
                        questionMarksByAct.put(act, questionMarksByAct.getOrDefault(act, 0) + 1);
                    }
                }
            }
            
            // 统计事件数量
            if (run.eventStats != null) {
                totalEvents += run.eventStats.size();
            }
        }
        
        /**
         * 统计火堆行动（所有类型：SMITH升级、RECALL回忆、REST睡觉、PURGE删牌、DIG挖掘、LIFT举重）
         * SMITH 按卡牌统计，其他按行动类型统计
         */
        private void campfireActionStats(Run run) {
            if (run.campfireChoices != null) {
                for (com.megacrit.cardcrawl.screens.stats.CampfireChoice choice : run.campfireChoices) {
                    String actionKey = choice.key;
                    if (actionKey == null) continue;
                    
                    // 确定当前火堆选择发生的楼层属于哪一幕
                    int act = run.getAct(choice.floor);
                    
                    // 根据行动类型决定统计 key
                    Object statKey;
                    if ("SMITH".equals(actionKey) && choice.data != null) {
                        // SMITH 行动：按卡牌统计
                        statKey = Card.fromStringIgnoreUpgrades(choice.data);
                    } else {
                        // 其他行动：按行动类型统计
                        statKey = actionKey;
                    }
                    
                    // 总统计
                    Rate<Object> totalRate = campfireActionsTotal.computeIfAbsent(statKey, k -> new Rate<>(k));
                    if (run.isHeartKill) {
                        totalRate.win++;
                    } else {
                        totalRate.loss++;
                    }
                    
                    // 按幕统计
                    Map<Object, Rate<Object>> actMap = null;
                    switch (act) {
                        case 1: actMap = campfireActionsAct1; break;
                        case 2: actMap = campfireActionsAct2; break;
                        case 3: actMap = campfireActionsAct3; break;
                        case 4: actMap = campfireActionsAct4; break;
                    }
                    
                    if (actMap != null) {
                        Rate<Object> actRate = actMap.computeIfAbsent(statKey, k -> new Rate<>(k));
                        if (run.isHeartKill) {
                            actRate.win++;
                        } else {
                            actRate.loss++;
                        }
                    }
                }
            }
        }
        
        /**
         * 火堆选项权重 + 火堆升级权重（卡牌层）。
         *
         * 卡牌层回答「这次火堆，卡组里有哪几张还没升级、实际敲了哪张」：
         * 每次锻造的分母 = 当时卡组里还有未升级副本的卡牌<b>种类</b>数，分子 = 实际被敲的那一张。
         * 同名多张只算一个选项（敲第 1 张和第 2 张意义相同），全部副本升完即退出分母。
         * 因此 percent() 可以直接横向比较「有这些候选时，哪张更值得敲」。
         *
         * 选项层回答「这次火堆为什么没敲卡」，用同一批火堆访问：
         * 被选次数 ÷ 该选项真正可选的火堆次数。睡觉始终可选；升级要求还有可升级的未升级卡；
         * 回忆只在 1-3 幕且尚未回忆时可选；抽烟/举重/挖掘要求当时已拥有和平烟斗/吉里亚/铲子。
         * 回忆另拆「强制」：到达第四幕的对局必须消耗一个火堆拿红钥匙，这是进入第四幕的固定成本。
         *
         * 口径边界：
         * - 只统计火堆升级；磨刀石/战锤/课程/事件/蛋的升级不在 .run 里逐张记录，少数目标会显得「还没升级」。
         * - Pandora's Box / Astrolabe 的变化来源没有逐张记录，用最终卡组补差；
         *   Astrolabe 补进来的牌按已升级处理，空鸟笼删掉的 2 张无法扣减。
         * - 初始卡组 + 抓牌 + 商店 + 事件 + 变化 + 火堆删牌按楼层重放；事件里的升级（cards_upgraded）也计入。
         */
        private void campfireOpportunityStats(Run run) {
            if (run.campfireChoices == null || run.campfireChoices.isEmpty()) {
                return;
            }
            ReplayModel model = buildReplay(run, knownCards(run));
            if (model.timeline.isEmpty()) {
                return;
            }

            // Pass A：按时间线走一遍，得到「能追踪到的最终卡组」，再和 master_deck 比对补差。
            DeckState afterRun = initialState(run);
            for (DeckEvent event : model.timeline) {
                applyDeckEffect(event, afterRun);
            }
            Map<Card, Integer> deficit = new LinkedHashMap<>();
            for (Map.Entry<Card, Integer> entry : finalDeckCounts(run).entrySet()) {
                int tracked = afterRun.copies(entry.getKey());
                if (entry.getValue() > tracked) {
                    deficit.put(entry.getKey(), entry.getValue() - tracked);
                }
            }
            if (model.supplementFloor > 0 && !deficit.isEmpty()) {
                model.timeline.add(DeckEvent.deficit(model.supplementFloor,
                        deficit, model.astrolabeResultsUpgraded));
                Collections.sort(model.timeline);
            }

            // 反向补差：重建卡组比终局卡组多出来的副本，是星盘/空鸟笼/潘多拉删掉却没记哪张的牌。
            // 不扣掉它们会一直留在候选里，把分母撑大（实测平均候选数会多 0.4 张）。
            Map<Card, Integer> surplus = new LinkedHashMap<>();
            Map<Card, Integer> finalCounts = finalDeckCounts(run);
            for (Card card : afterRun.cards.keySet()) {
                int tracked = afterRun.copies(card);
                Integer actual = finalCounts.get(card);
                int kept = actual == null ? 0 : actual;
                if (tracked > kept) {
                    surplus.put(card, tracked - kept);
                }
            }
            if (model.supplementFloor > 0 && !surplus.isEmpty()) {
                model.timeline.add(DeckEvent.surplus(model.supplementFloor, surplus));
                Collections.sort(model.timeline);
            }

            // Pass B：逐次火堆记录选项与卡牌暴露，再应用该次选择。
            DeckState deck = initialState(run);
            if (altOwnedByAct.isEmpty()) {
                for (int i = 0; i <= 4; i++) {
                    altOwnedByAct.add(new HashMap<Card, Integer>());
                }
            }
            // A 口径的分子分母来源：ever 只增不减（累计拥过），幕末快照一次。
            Map<Card, Integer> ever = new HashMap<>();
            for (Card card : deck.cards.keySet()) {
                if (deck.copies(card) > 0) {
                    ever.put(card, deck.copies(card));
                }
            }
            if (run.ownsRelicAt("Astrolabe", Integer.MAX_VALUE)) {
                campfireAstrolabeRuns++;
            }
            if (run.ownsRelicAt("Empty Cage", Integer.MAX_VALUE)) {
                campfireEmptyCageRuns++;
            }
            boolean recallUsed = false;
            boolean reachedAct4 = run.getLastActForStats() >= 4;
            int lifts = 0;
            int prevAct = 1;
            for (DeckEvent event : model.timeline) {
                int eventAct = actOf(run, event.floor);
                while (prevAct < eventAct) {
                    snapshotOwned(altOwnedByAct.get(prevAct), ever);
                    prevAct++;
                }
                if (event.kind != KIND_CAMPFIRE) {
                    if (event.kind == KIND_ADD) {
                        if (event.card != null) {
                            int had = ever.containsKey(event.card) ? ever.get(event.card) : 0;
                            ever.put(event.card, had + 1);
                        }
                    } else if (event.kind == KIND_DEFICIT && event.deficit != null) {
                        for (Map.Entry<Card, Integer> d : event.deficit.entrySet()) {
                            int had = ever.containsKey(d.getKey()) ? ever.get(d.getKey()) : 0;
                            ever.put(d.getKey(), had + d.getValue());
                        }
                    }
                    applyDeckEffect(event, deck);
                    continue;
                }
                CampfireChoice choice = event.choice;
                int act = actOf(run, choice.floor);
                int option = optionIndex(choice.key);

                boolean[] available = new boolean[OPTION_KEYS.length];
                // 六个选项都只在「这次火堆真的出现在屏幕上」时才算可选：
                // 咖啡杯（Coffee Dripper）禁用睡觉，锤子（Fusion Hammer）禁用升级；
                // 没有可升级的卡时升级也不出现。被禁用的选项不能算「可选却没选」，否则分母被撑大。
                available[OPT_REST] = !run.ownsRelicAt("Coffee Dripper", choice.floor);
                available[OPT_SMITH] = !run.ownsRelicAt("Fusion Hammer", choice.floor)
                        && deck.hasUpgradable();
                available[OPT_RECALL] = act <= 3 && !recallUsed;
                available[OPT_PURGE] = run.ownsRelicAt("Peace Pipe", choice.floor);
                available[OPT_LIFT] = lifts < 3 && run.ownsRelicAt("Girya", choice.floor);
                available[OPT_DIG] = run.ownsRelicAt("Shovel", choice.floor);
                if (option >= 0 && !available[option]) {
                    // 遗物楼层漏记时以实际选择为准，避免出现「被选 > 可选」。
                    available[option] = true;
                }
                int optionCount = 0;
                for (boolean value : available) {
                    if (value) {
                        optionCount++;
                    }
                }
                if (option >= 0) {
                    optionVisits[act]++;
                    optionVisits[0]++;
                    // 竞争者池：可敲卡种 + 可用遗物行动 + 可用回忆（不含睡觉，也不含「升级」本身）
                    int pool = available[OPT_SMITH] ? deck.upgradableKinds() : 0;
                    for (int i = 0; i < OPTION_KEYS.length; i++) {
                        if (i == OPT_SMITH || i == OPT_REST) {
                            continue;
                        }
                        if (available[i]) {
                            pool++;
                        }
                    }
                    poolSizeSum[act] += pool;
                    poolSizeSum[0] += pool;
                    for (int i = 0; i < OPTION_KEYS.length; i++) {
                        if (!available[i]) {
                            continue;
                        }
                        optionAvailable[act][i]++;
                        optionAvailable[0][i]++;
                        optionInvOptions[act][i] += 1.0D / Math.max(1, optionCount);
                        optionInvOptions[0][i] += 1.0D / Math.max(1, optionCount);
                    }
                    optionChosen[act][option]++;
                    optionChosen[0][option]++;
                    if (option == OPT_RECALL) {
                        // 只有第三幕的回忆是「必须」的：要进第四幕，红钥匙必须在第三幕结束前拿到，
                        // 而第三幕的最后一个火堆是最后机会。第一、二幕回忆属于「提前拿」，
                        // 玩家本可以留到第三幕，是主动选择而非被迫，所以不算强制。
                        if (act == 3 && reachedAct4) {
                            recallForced[act]++;
                            recallForced[0]++;
                        } else {
                            recallOptional[act]++;
                            recallOptional[0]++;
                        }
                    }
                }

                if (option == OPT_SMITH) {
                    recordSmith(deck, choice, act);
                } else if (option == OPT_PURGE) {
                    deck.remove(upgradePrefCard(choice.data));
                } else if (option == OPT_LIFT) {
                    lifts++;
                } else if (option == OPT_RECALL) {
                    recallUsed = true;
                }
            }
            for (int a = prevAct; a <= 4; a++) {
                snapshotOwned(altOwnedByAct.get(a), ever);
            }
            if (!altOwnedByAct.get(0).isEmpty() || ever.isEmpty()) {
                Map<Card, Integer> total = altOwnedByAct.get(0);
                for (Map.Entry<Card, Integer> entry : ever.entrySet()) {
                    Integer had = total.get(entry.getKey());
                    if (had == null || had < entry.getValue()) {
                        total.put(entry.getKey(), entry.getValue());
                    }
                }
            }
        }

        /** 幕末快照：该幕结束时「累计拥过」的副本数。 */
        private static void snapshotOwned(Map<Card, Integer> target, Map<Card, Integer> ever) {
            for (Map.Entry<Card, Integer> entry : ever.entrySet()) {
                if (entry.getValue() != null && entry.getValue() > 0) {
                    target.put(entry.getKey(), entry.getValue());
                }
            }
        }

        /** 一次可用的锻造：分母是当时的未升级卡牌种类，分子是实际被敲的那张。 */
        private void recordSmith(DeckState deck, CampfireChoice choice, int act) {
            Card target = upgradePrefCard(choice.data);
            if (target == null || !deck.contains(target)) {
                // 目标不在重建卡组里（星盘/空鸟笼删牌无逐张日志）：这次锻造只能靠 A 列估算。
                campfireRebuildMiss[act]++;
                campfireRebuildMiss[0]++;
                if (target != null) {
                    Integer had = campfireMissCard.get(target);
                    campfireMissCard.put(target, had == null ? 1 : had + 1);
                }
                return;
            }
            if (deck.unupgraded(target) <= 0) {
                campfireRebuildMiss[act]++;
                campfireRebuildMiss[0]++;
                Integer had = campfireMissCard.get(target);
                campfireMissCard.put(target, had == null ? 1 : had + 1);
                deck.upgrade(target);
                return;
            }
            campfireRebuildOk[act]++;
            campfireRebuildOk[0]++;
            smithUsable[act]++;
            smithUsable[0]++;

            int exposure = 0;
            for (Map.Entry<Card, int[]> entry : deck.cards.entrySet()) {
                Card card = entry.getKey();
                if (isUpgradeImmune(card.name) || deck.unupgraded(card) <= 0) {
                    continue;
                }
                exposure++;
                addExposure(upgradePrefMapInstance(act), card);
                addExposure(campfireUpgradePrefTotal, card);
            }
            Rate<Card> chosenAct = upgradePrefMapInstance(act).get(target);
            Rate<Card> chosenTotal = campfireUpgradePrefTotal.get(target);
            if (chosenAct == null || chosenTotal == null) {
                deck.upgrade(target);
                return;
            }
            chosenAct.win++;
            chosenAct.loss--;
            chosenTotal.win++;
            chosenTotal.loss--;
            upgradeExposureSum[act] += exposure;
            upgradeExposureSum[0] += exposure;
            deck.upgrade(target);
        }

        private static void addExposure(Map<Card, Rate<Card>> map, Card card) {
            Rate<Card> rate = map.get(card);
            if (rate == null) {
                rate = new Rate<>(card);
                map.put(card, rate);
            }
            rate.loss++;
        }

        /** 初始卡组 + 尼奥的获得/删除/变化/升级。 */
        private static DeckState initialState(Run run) {
            DeckState deck = new DeckState();
            addStartingDeck(run, deck);
            NeowBonusLog neow = run.neowBonusLog;
            if (neow != null) {
                for (String raw : safeList(neow.cardsObtained)) {
                    deck.add(upgradePrefCard(raw), isUpgradedId(raw));
                }
                for (String raw : safeList(neow.cardsRemoved)) {
                    deck.remove(upgradePrefCard(raw));
                }
                for (String raw : safeList(neow.cardsTransformed)) {
                    deck.remove(upgradePrefCard(raw));
                }
                for (String raw : safeList(neow.cardsUpgraded)) {
                    deck.upgrade(upgradePrefCard(raw));
                }
            }
            return deck;
        }

        private static void addStartingDeck(Run run, DeckState deck) {
            AbstractPlayer.PlayerClass pc = run.playerClass;
            if (pc == AbstractPlayer.PlayerClass.IRONCLAD) {
                deck.add("Strike", 5);
                deck.add("Defend", 4);
                deck.add("Bash", 1);
            } else if (pc == AbstractPlayer.PlayerClass.THE_SILENT) {
                deck.add("Strike", 5);
                deck.add("Defend", 5);
                deck.add("Neutralize", 1);
                deck.add("Survivor", 1);
            } else if (pc == AbstractPlayer.PlayerClass.DEFECT) {
                deck.add("Strike", 4);
                deck.add("Defend", 4);
                deck.add("Zap", 1);
                deck.add("Dualcast", 1);
            } else if (pc == AbstractPlayer.PlayerClass.WATCHER) {
                deck.add("Strike", 4);
                deck.add("Defend", 4);
                deck.add("Eruption", 1);
                deck.add("Vigilance", 1);
            }
            if (run.ascensionLevel >= 10) {
                deck.add("Ascender's Bane", 1);
            }
        }

        /** 已知卡名集合，用于区分商店里的卡牌与遗物/药水。 */
        private static Set<String> knownCards(Run run) {
            Set<String> known = new HashSet<>();
            for (Card card : run.masterDeck) {
                Card base = upgradePrefCard(card.name);
                if (base != null) {
                    known.add(base.name);
                }
            }
            for (CardChoiceStats choice : run.runData.card_choices) {
                if (choice != null) {
                    addKnown(known, choice.picked);
                }
            }
            for (EventStats event : run.eventStats) {
                if (event == null) {
                    continue;
                }
                for (String raw : safeList(event.cards_obtained)) {
                    addKnown(known, raw);
                }
            }
            if (run.neowBonusLog != null) {
                for (String raw : safeList(run.neowBonusLog.cardsObtained)) {
                    addKnown(known, raw);
                }
            }
            return known;
        }

        private static Map<Card, Integer> finalDeckCounts(Run run) {
            Map<Card, Integer> counts = new HashMap<>();
            for (Card card : run.masterDeck) {
                Card base = upgradePrefCard(card.name);
                if (base == null) {
                    continue;
                }
                Integer old = counts.get(base);
                counts.put(base, old == null ? 1 : old + 1);
            }
            return counts;
        }

        /**
         * 把本局的卡牌变化整理成按楼层排序的时间线。campfire_choices 也进时间线，
         * 这样每次火堆之前应用的是同一份卡组状态。
         */
        private ReplayModel buildReplay(Run run, Set<String> knownCards) {
            List<DeckEvent> timeline = new ArrayList<>();
            int seq = 0;

            for (CardChoiceStats choice : run.runData.card_choices) {
                if (choice == null || choice.picked == null) {
                    continue;
                }
                Card card = upgradePrefCard(choice.picked);
                if (card == null || "SKIP".equals(card.name) || "Singing Bowl".equals(card.name)) {
                    continue;
                }
                timeline.add(DeckEvent.add(choice.floor, seq++, card, isUpgradedId(choice.picked)));
            }

            for (int i = 0; i < run.itemsPurchased.size(); i++) {
                String raw = run.itemsPurchased.get(i);
                if (!isCardName(raw, knownCards)) {
                    continue;
                }
                int floor = i < run.itemPurchaseFloors.size() ? run.itemPurchaseFloors.get(i) : 1;
                timeline.add(DeckEvent.add(floor, seq++, upgradePrefCard(raw), isUpgradedId(raw)));
            }

            if (run.itemsPurged != null) {
                for (int i = 0; i < run.itemsPurged.size(); i++) {
                    int floor = i < run.itemsPurgedFloors.size() ? run.itemsPurgedFloors.get(i) : 1;
                    timeline.add(DeckEvent.remove(floor, seq++, upgradePrefCard(run.itemsPurged.get(i))));
                }
            }

            for (EventStats event : run.eventStats) {
                if (event == null) {
                    continue;
                }
                int floor = event.floor <= 0 ? 1 : event.floor;
                for (String raw : safeList(event.cards_obtained)) {
                    timeline.add(DeckEvent.add(floor, seq++, upgradePrefCard(raw), isUpgradedId(raw)));
                }
                for (String raw : safeList(event.cards_removed)) {
                    timeline.add(DeckEvent.remove(floor, seq++, upgradePrefCard(raw)));
                }
                for (String raw : safeList(event.cards_transformed)) {
                    timeline.add(DeckEvent.remove(floor, seq++, upgradePrefCard(raw)));
                }
                for (String raw : safeList(event.cards_upgraded)) {
                    timeline.add(DeckEvent.upgrade(floor, seq++, upgradePrefCard(raw)));
                }
            }

            for (CampfireChoice choice : run.campfireChoices) {
                if (choice != null) {
                    timeline.add(DeckEvent.campfire(choice.floor <= 0 ? 1 : choice.floor, seq++, choice));
                }
            }

            int supplementFloor = 0;
            boolean astrolabe = false;
            if (run.bossRelicChoiceStats != null) {
                for (int i = 0; i < run.bossRelicChoiceStats.size() && i < 2; i++) {
                    BossRelicChoiceStats choice = run.bossRelicChoiceStats.get(i);
                    if (choice == null || choice.picked == null) {
                        continue;
                    }
                    int bossFloor = i == 0 ? run.act1BossFloor : run.act2BossFloor;
                    if ("Pandora's Box".equals(choice.picked)) {
                        if (bossFloor > 0) {
                            timeline.add(DeckEvent.pandora(bossFloor, seq++));
                        }
                        if (supplementFloor == 0) {
                            supplementFloor = Math.max(1, bossFloor);
                        }
                    } else if ("Astrolabe".equals(choice.picked)) {
                        astrolabe = true;
                        if (supplementFloor == 0) {
                            supplementFloor = Math.max(1, bossFloor);
                        }
                    } else if ("Tiny House".equals(choice.picked)
                            || "Orrery".equals(choice.picked)
                            || "Calling Bell".equals(choice.picked)) {
                        if (supplementFloor == 0) {
                            supplementFloor = Math.max(1, bossFloor);
                        }
                    }
                }
            }

            Collections.sort(timeline);
            return new ReplayModel(timeline, supplementFloor, astrolabe);
        }

        private static void applyDeckEffect(DeckEvent event, DeckState deck) {
            switch (event.kind) {
                case KIND_ADD:
                    deck.add(event.card, event.upgraded);
                    break;
                case KIND_REMOVE:
                    deck.remove(event.card);
                    break;
                case KIND_UPGRADE:
                    deck.upgrade(event.card);
                    break;
                case KIND_PANDORA:
                    deck.removeAll(upgradePrefCard("Strike"));
                    deck.removeAll(upgradePrefCard("Defend"));
                    break;
                case KIND_CAMPFIRE:
                    if ("SMITH".equals(event.choice.key)) {
                        deck.upgrade(upgradePrefCard(event.choice.data));
                    } else if ("PURGE".equals(event.choice.key)) {
                        deck.remove(upgradePrefCard(event.choice.data));
                    }
                    break;
                case KIND_DEFICIT:
                    for (Map.Entry<Card, Integer> entry : event.deficit.entrySet()) {
                        for (int i = 0; i < entry.getValue(); i++) {
                            deck.add(entry.getKey(), event.upgraded);
                        }
                    }
                    break;
                case KIND_SURPLUS:
                    // 反向补差：这些副本在重建里还在、终局卡组却没有，说明被星盘/空鸟笼/潘多拉删掉了
                    for (Map.Entry<Card, Integer> entry : event.deficit.entrySet()) {
                        for (int i = 0; i < entry.getValue(); i++) {
                            deck.remove(entry.getKey());
                        }
                    }
                    break;
                default:
                    break;
            }
        }

        private static int optionIndex(String key) {
            if (key == null) {
                return -1;
            }
            for (int i = 0; i < OPTION_KEYS.length; i++) {
                if (OPTION_KEYS[i].equals(key)) {
                    return i;
                }
            }
            return -1;
        }

        private static boolean isUpgradedId(String raw) {
            return raw != null && raw.contains("+");
        }

        private static boolean isUpgradeImmune(String name) {
            return name != null && UPGRADE_IMMUNE.contains(name);
        }

        private static void addKnown(Set<String> knownCards, String raw) {
            Card card = upgradePrefCard(raw);
            if (card != null) {
                knownCards.add(card.name);
            }
        }

        private boolean isCardName(String raw, Set<String> knownCards) {
            Card card = upgradePrefCard(raw);
            if (card == null) {
                return false;
            }
            if (knownCards.contains(card.name)) {
                return true;
            }
            try {
                if (CardLibrary.getCard(card.name) != null) {
                    return true;
                }
            } catch (Throwable ignored) {
                // 游戏尚未初始化 CardLibrary（例如纯数据校验）时退回 knownCards
            }
            return false;
        }

        private static List<String> safeList(List<String> list) {
            return list == null ? java.util.Collections.<String>emptyList() : list;
        }

        private static Card upgradePrefCard(String raw) {
            if (raw == null) {
                return null;
            }
            String base = raw.split("\\+")[0];
            if (base.isEmpty()) {
                return null;
            }
            if (base.startsWith("Strike_")) {
                base = "Strike";
            } else if (base.startsWith("Defend_")) {
                base = "Defend";
            } else if ("AscendersBane".equals(base)) {
                base = "Ascender's Bane";
            }
            return Card.fromStringIgnoreUpgrades(base);
        }

        private static int actOf(int act) {
            return act < 1 ? 1 : (act > 4 ? 4 : act);
        }

        private static int actOf(Run run, int floor) {
            return actOf(run.getAct(floor));
        }

        private Map<Card, Rate<Card>> upgradePrefMapInstance(int act) {
            switch (act) {
                case 1: return campfireUpgradePrefAct1;
                case 2: return campfireUpgradePrefAct2;
                case 3: return campfireUpgradePrefAct3;
                case 4: return campfireUpgradePrefAct4;
                default: return campfireUpgradePrefTotal;
            }
        }

        private static final int OPT_SMITH = 0;
        private static final int OPT_REST = 1;
        private static final int OPT_RECALL = 2;
        private static final int OPT_PURGE = 3;
        private static final int OPT_LIFT = 4;
        private static final int OPT_DIG = 5;

        /** 不能升级的诅咒/状态/进阶之灾。 */
        private static final Set<String> UPGRADE_IMMUNE = new HashSet<>(java.util.Arrays.asList(
                "Ascender's Bane", "Clumsy", "Curse of the Bell", "CurseOfTheBell", "Decay", "Doubt",
                "Injury", "Necronomicurse", "Normality", "Pain", "Parasite", "Pride", "Regret",
                "Shame", "Void", "Writhe", "Burn", "Dazed", "Slimed", "Wound"));

        private static final int KIND_ADD = 0;
        private static final int KIND_REMOVE = 1;
        private static final int KIND_UPGRADE = 2;
        private static final int KIND_PANDORA = 3;
        private static final int KIND_CAMPFIRE = 4;
        private static final int KIND_DEFICIT = 5;
        /** 反向补差：重建卡组里比终局卡组多出来的副本（星盘/空鸟笼/潘多拉删掉但没记哪张）。 */
        private static final int KIND_SURPLUS = 6;

        private static final int ORDER_DECK = 0;
        private static final int ORDER_CAMPFIRE = 1;
        private static final int ORDER_DEFICIT = 2;
        private static final int ORDER_SURPLUS = 3;

        private static final class ReplayModel {
            final List<DeckEvent> timeline;
            final int supplementFloor;
            final boolean astrolabeResultsUpgraded;

            ReplayModel(List<DeckEvent> timeline, int supplementFloor, boolean astrolabeResultsUpgraded) {
                this.timeline = timeline;
                this.supplementFloor = supplementFloor;
                this.astrolabeResultsUpgraded = astrolabeResultsUpgraded;
            }
        }

        private static final class DeckEvent implements Comparable<DeckEvent> {
            final int floor;
            final int order;
            final int seq;
            final int kind;
            final Card card;
            final boolean upgraded;
            final CampfireChoice choice;
            final Map<Card, Integer> deficit;

            private DeckEvent(int floor, int order, int seq, int kind, Card card, boolean upgraded,
                              CampfireChoice choice, Map<Card, Integer> deficit) {
                this.floor = Math.max(1, floor);
                this.order = order;
                this.seq = seq;
                this.kind = kind;
                this.card = card;
                this.upgraded = upgraded;
                this.choice = choice;
                this.deficit = deficit;
            }

            static DeckEvent add(int floor, int seq, Card card, boolean upgraded) {
                return new DeckEvent(floor, ORDER_DECK, seq, KIND_ADD, card, upgraded, null, null);
            }

            static DeckEvent remove(int floor, int seq, Card card) {
                return new DeckEvent(floor, ORDER_DECK, seq, KIND_REMOVE, card, false, null, null);
            }

            static DeckEvent upgrade(int floor, int seq, Card card) {
                return new DeckEvent(floor, ORDER_DECK, seq, KIND_UPGRADE, card, false, null, null);
            }

            static DeckEvent pandora(int floor, int seq) {
                return new DeckEvent(floor, ORDER_DECK, seq, KIND_PANDORA, null, false, null, null);
            }

            static DeckEvent campfire(int floor, int seq, CampfireChoice choice) {
                return new DeckEvent(floor, ORDER_CAMPFIRE, seq, KIND_CAMPFIRE, null, false, choice, null);
            }

            static DeckEvent deficit(int floor, Map<Card, Integer> deficit, boolean upgraded) {
                return new DeckEvent(floor, ORDER_DEFICIT, Integer.MAX_VALUE, KIND_DEFICIT,
                        null, upgraded, null, deficit);
            }

            static DeckEvent surplus(int floor, Map<Card, Integer> surplus) {
                return new DeckEvent(floor, ORDER_SURPLUS, Integer.MAX_VALUE, KIND_SURPLUS,
                        null, false, null, surplus);
            }

            @Override
            public int compareTo(DeckEvent other) {
                if (floor != other.floor) {
                    return Integer.compare(floor, other.floor);
                }
                if (order != other.order) {
                    return Integer.compare(order, other.order);
                }
                return Integer.compare(seq, other.seq);
            }
        }

        /** 逐次火堆使用的卡组状态：每个卡名的副本数与其中已升级的副本数。 */
        private static final class DeckState {
            final Map<Card, int[]> cards = new HashMap<>();

            void add(String name, int count) {
                for (int i = 0; i < count; i++) {
                    add(upgradePrefCard(name), false);
                }
            }

            void add(Card card, boolean upgraded) {
                if (card == null) {
                    return;
                }
                int[] values = cards.get(card);
                if (values == null) {
                    values = new int[2];
                    cards.put(card, values);
                }
                values[0]++;
                if (upgraded) {
                    values[1]++;
                }
            }

            void remove(Card card) {
                if (card == null) {
                    return;
                }
                int[] values = cards.get(card);
                if (values == null || values[0] <= 0) {
                    return;
                }
                values[0]--;
                if (values[1] > values[0]) {
                    values[1] = values[0];
                }
                if (values[0] == 0) {
                    cards.remove(card);
                }
            }

            void removeAll(Card card) {
                if (card != null) {
                    cards.remove(card);
                }
            }

            void upgrade(Card card) {
                if (card == null) {
                    return;
                }
                int[] values = cards.get(card);
                if (values == null || values[0] <= 0) {
                    return;
                }
                if (values[1] < values[0]) {
                    values[1]++;
                }
            }

            boolean contains(Card card) {
                return card != null && cards.containsKey(card);
            }

            int copies(Card card) {
                int[] values = card == null ? null : cards.get(card);
                return values == null ? 0 : values[0];
            }

            /** 未升级副本数；灼热攻击可以反复敲，所以只要还有副本就仍算可选。 */
            int unupgraded(Card card) {
                int[] values = card == null ? null : cards.get(card);
                if (values == null) {
                    return 0;
                }
                if ("Searing Blow".equals(card.name)) {
                    return values[0];
                }
                return values[0] - values[1];
            }

            boolean hasUpgradable() {
                for (Card card : cards.keySet()) {
                    if (!isUpgradeImmune(card.name) && unupgraded(card) > 0) {
                        return true;
                    }
                }
                return false;
            }

            /** 还能敲的卡牌种类数（同名多张算 1 种），即「升级」这个入口拆开后的选项个数。 */
            int upgradableKinds() {
                int n = 0;
                for (Card card : cards.keySet()) {
                    if (!isUpgradeImmune(card.name) && unupgraded(card) > 0) {
                        n++;
                    }
                }
                return n;
            }
        }

        /**
         * 移除卡牌名称中的升级后缀（+1, +2, +3）并进行名称标准化
         * 例如："Tactician+1" -> "Tactician"
         *       "Adaptation+1" -> "Rushdown" （游戏版本更新后的名称映射）
         */
        private String normalizeCardName(String cardName) {
            if (cardName == null) {
                return null;
            }
            // 先移除末尾的 +1, +2, +3 等升级标记
            String baseName = cardName.replaceAll("\\+[0-9]+$", "");
            // 再应用 Utils 中的卡牌名称标准化映射（如 Adaptation -> Rushdown）
            return tinycat.Utils.normalizeCardName(baseName);
        }
        
        /**
         * 统计商店行动（购买的卡牌、遗物、药水、删牌等）
         */
        private void shopActionStats(Run run) {
            // 0. 统计遇到的所有商店（从 shop_contents）
            if (run.shopContents != null) {
                for (ShopContent shop : run.shopContents) {
                    if (shop.floor != null) {
                        int act = run.getAct(shop.floor);
                        // 根据胜负增加计数器（不去重，每次遇到都+1）
                        if (run.isHeartKill) {
                            // 胜利样本
                            switch (act) {
                                case 1: shopCountAct1Win++; break;
                                case 2: shopCountAct2Win++; break;
                                case 3: shopCountAct3Win++; break;
                                case 4: shopCountAct4Win++; break;
                            }
                        } else {
                            // 失败样本
                            switch (act) {
                                case 1: shopCountAct1Loss++; break;
                                case 2: shopCountAct2Loss++; break;
                                case 3: shopCountAct3Loss++; break;
                                case 4: shopCountAct4Loss++; break;
                            }
                        }
                    }
                }
            }
            
            // 收集本局所有有效商店楼层（购买物品 + 删牌，去重）
            Set<Integer> validShopFloorsInThisRun = new HashSet<>();
            
            // 1. 收集购买物品的楼层（卡牌、遗物、药水）
            if (run.itemsPurchased != null && run.itemPurchaseFloors != null) {
                for (Integer floor : run.itemPurchaseFloors) {
                    if (floor != null) {
                        validShopFloorsInThisRun.add(floor);
                    }
                }
                
                // 统计具体购买的物品
                for (int i = 0; i < run.itemsPurchased.size(); i++) {
                    String itemKey = run.itemsPurchased.get(i);
                    Integer floor = (i < run.itemPurchaseFloors.size()) ? run.itemPurchaseFloors.get(i) : null;
                    
                    // 确定幕
                    int act = 1; // 默认为第一幕
                    if (floor != null) {
                        act = run.getAct(floor);
                    }
                    
                    // 药水在 items_purchased 里有两种写法：ID（PowerPotion）和显示名（Swift Potion）。
                    // 不去空格会拆成两条统计、且映射表只认 ID，所以先归一化成 ID 形式。
                    String purchaseName = itemKey;
                    if (purchaseName != null && purchaseName.endsWith(" Potion")) {
                        purchaseName = purchaseName.replace(" ", "");
                    }
                    // 对卡牌名称进行标准化处理（移除升级后缀）
                    String normalizedKey = normalizeCardName(purchaseName);
                    Object statKey = (normalizedKey != null) ? normalizedKey : purchaseName;
                    
                    // 总统计
                    Rate<Object> totalRate = shopActionsTotal.computeIfAbsent(statKey, k -> new Rate<>(k));
                    if (run.isHeartKill) {
                        totalRate.win++;
                    } else {
                        totalRate.loss++;
                    }
                    
                    // 按幕统计
                    Map<Object, Rate<Object>> actMap = null;
                    switch (act) {
                        case 1: actMap = shopActionsAct1; break;
                        case 2: actMap = shopActionsAct2; break;
                        case 3: actMap = shopActionsAct3; break;
                        case 4: actMap = shopActionsAct4; break;
                    }
                    
                    if (actMap != null) {
                        Rate<Object> actRate = actMap.computeIfAbsent(statKey, k -> new Rate<>(k));
                        if (run.isHeartKill) {
                            actRate.win++;
                        } else {
                            actRate.loss++;
                        }
                    }
                }
            }
            
            // 2. 统计商店删牌（只统计次数，不统计具体卡牌）
            if (run.purchasedPurges > 0 && run.itemsPurgedFloors != null) {
                // 收集删牌的楼层（也计入有效商店）
                for (Integer floor : run.itemsPurgedFloors) {
                    if (floor != null) {
                        validShopFloorsInThisRun.add(floor);
                    }
                }
                
                // 统计删牌发生的幕（使用 itemsPurgedFloors 来确定幕）
                Map<Integer, Integer> purgeCountByAct = new HashMap<>();
                
                for (Integer floor : run.itemsPurgedFloors) {
                    if (floor != null) {
                        int act = run.getAct(floor);
                        purgeCountByAct.put(act, purgeCountByAct.getOrDefault(act, 0) + 1);
                    }
                }
                
                // 添加总统计
                String purgeKey = "商店删牌";
                Rate<Object> totalPurgeRate = shopActionsTotal.computeIfAbsent(purgeKey, k -> new Rate<>(k));
                if (run.isHeartKill) {
                    totalPurgeRate.win += run.purchasedPurges;
                } else {
                    totalPurgeRate.loss += run.purchasedPurges;
                }
                
                // 按幕统计
                for (Map.Entry<Integer, Integer> entry : purgeCountByAct.entrySet()) {
                    int act = entry.getKey();
                    int count = entry.getValue();
                    
                    Map<Object, Rate<Object>> actMap = null;
                    switch (act) {
                        case 1: actMap = shopActionsAct1; break;
                        case 2: actMap = shopActionsAct2; break;
                        case 3: actMap = shopActionsAct3; break;
                        case 4: actMap = shopActionsAct4; break;
                    }
                    
                    if (actMap != null) {
                        Rate<Object> actPurgeRate = actMap.computeIfAbsent(purgeKey, k -> new Rate<>(k));
                        if (run.isHeartKill) {
                            actPurgeRate.win += count;
                        } else {
                            actPurgeRate.loss += count;
                        }
                    }
                }
            }
            
            // 3. 统一统计有效商店数（购买物品 + 删牌的所有楼层，去重后按胜负计数）
            for (Integer floor : validShopFloorsInThisRun) {
                int act = run.getAct(floor);
                if (run.isHeartKill) {
                    switch (act) {
                        case 1: shopValidCountAct1Win++; break;
                        case 2: shopValidCountAct2Win++; break;
                        case 3: shopValidCountAct3Win++; break;
                        case 4: shopValidCountAct4Win++; break;
                    }
                } else {
                    switch (act) {
                        case 1: shopValidCountAct1Loss++; break;
                        case 2: shopValidCountAct2Loss++; break;
                        case 3: shopValidCountAct3Loss++; break;
                        case 4: shopValidCountAct4Loss++; break;
                    }
                }
            }
        }
        
        private int runCount = 0;

        void collect(Run run) {
            runCount++;
            cardPickStats(run);
            deckSizeStats(run);
            deckStats(run);
            neowStats(run);
            bossRelicStats(run);
            encounterStats(run);
            survivalRatePerAct(run);
            relicStats(run);
            runTimeStats(run);
            eventStats(run);
            allHPLossStats(run);
            purgeStats(run);  // 统计删牌数据
            questionMarkAndEventStats(run);  // 统计问号和事件
            campfireActionStats(run);  // 统计火堆行动
            campfireOpportunityStats(run);  // 火堆选项权重 + 卡牌升级权重（逐次重放卡组）
            shopActionStats(run);  // 统计商店行动
        }

        /**
         * 默认排序：按胜率/选取率降序（用于卡牌选取率、致死率等）
         */
        private <A> List<A> sortedMapValues(Map<?, A> map) {
            // 使用 LinkedList 以保持排序稳定性，避免 ArrayList.add(0, element) 的性能问题
            return map.values().stream().sorted().collect(Collectors.toCollection(java.util.LinkedList::new));
        }
        
        /**
         * 次数优先排序：按次数降序（用于火堆行动、商店行动）
         */
        @SuppressWarnings("unchecked")
        private <A> List<A> sortedMapValuesByCount(Map<?, A> map) {
            return map.values().stream()
                .map(v -> (Rate<Object>) v)
                .sorted(Rate.COUNT_FIRST_COMPARATOR)
                .map(r -> (A) r)
                .collect(Collectors.toCollection(java.util.LinkedList::new));
        }

        void finalise(ClassStat cs) {
            cs.cardPicksAct1 = sortedMapValues(cardPicksAct1);
            cs.cardPicksAfterAct1 = sortedMapValues(cardPicksAfterAct1);
            cs.cardWinRate = sortedMapValues(cardWinRate);
            cs.neowPickRate = sortedMapValues(neowPickRate);
            cs.neowBeats.putAll(neowBeats);
            cs.neowWinRate = sortedMapValues(neowWinRate);

            cs.bossRelicPickRateAct1 = sortedMapValues(bossRelicPickRateAct1);
            cs.bossRelicPickRateAct2 = sortedMapValues(bossRelicPickRateAct2);

            cs.bossRelicWinRateAct1 = sortedMapValues(bossRelicWinRateAct1);
            cs.bossRelicWinRateAct2 = sortedMapValues(bossRelicWinRateAct2);
            cs.bossRelicWinRateSwap = sortedMapValues(bossRelicWinRateSwap);

            finaliseEncounterStats();
            cs.averageDamageTaken = avgEncounterDamage;
            cs.averageActualHPLoss = avgEncounterActualHPLoss;
            cs.averageNoHealActualHPLoss = avgEncounterNoHealActualHPLoss;
            cs.averageHealActualHPLoss = avgEncounterHealActualHPLoss;
            cs.averageEventHPLoss = avgEncounterEventHPLoss;
            cs.averagePotionUse = averagePotionUse;

            // 插入战斗总计到各序列开头
            insertHPLossTotal(cs);

            Map<Integer, List<Mean>> allHpLoss = new HashMap<>();
            for (int act : new int[]{1, 2, 3, 4}) {
                List<Mean> one = new ArrayList<>();
                one.add(totalAllHPLossPerAct.get(act));
                allHpLoss.put(act, one);
            }
            cs.averageAllHPLoss = allHpLoss;

            cs.nob = nob;
            encounterDeadRate.forEach((k, v) -> {
                cs.encounterDeadRate.put(k, sortedMapValues(v));
            });

            cs.survivalRatePerAct = survivalRatePerAct;
            cs.relicWinRate = sortedMapValues(relicWinRate);
            cs.relicPurchasedWinRate = sortedMapValues(relicPurchasedWinRate);

            cs.averageDeckSize = averageDeckSize;
            cs.averageWinningTime = averageWinningTime;
            cs.averageScore = averageScore;
            cs.averageGold = averageGold;  // 赋值累计获得金币（估算）
            cs.goldEstimatedRuns = goldEstimatedRuns;
            cs.goldExactRuns = goldExactRuns;
            cs.floor0HPEstimatedRuns = floor0HPEstimatedRuns;

            cs.eventWinRateAct1 = sortedMapValues(eventWinRate.get(1));
            cs.eventWinRateAct2 = sortedMapValues(eventWinRate.get(2));
            cs.eventWinRateAct3 = sortedMapValues(eventWinRate.get(3));
            
            // 转移删牌统计数据
            // 总删牌数 = 商店 + 事件 + 开局 + 篝火 + 空鸟笼
            cs.totalCardsPurged = shopPurges + eventPurges + neowPurges + campfirePurges + emptyCagePurges;
            cs.shopPurges = shopPurges;  // 转移商店删牌
            cs.neowPurges = neowPurges;
            cs.campfirePurges = campfirePurges;
            cs.emptyCagePurges = emptyCagePurges;
            cs.eventPurges = eventPurges;  // 转移事件删牌
            cs.purgesBySource = purgesBySource;
            cs.purgesByAct = purgesByAct;
            
            // 转移问号和事件统计数据
            cs.totalQuestionMarks = totalQuestionMarks;
            cs.questionMarksByAct = questionMarksByAct;
            cs.totalEvents = totalEvents;
            
            // 转移火堆行动统计数据（将 Map 转换为排序后的 List）
            // 火堆行动使用次数优先排序
            cs.campfireActionsTotal = sortedMapValuesByCount(campfireActionsTotal);
            cs.campfireActionsAct1 = sortedMapValuesByCount(campfireActionsAct1);
            cs.campfireActionsAct2 = sortedMapValuesByCount(campfireActionsAct2);
            cs.campfireActionsAct3 = sortedMapValuesByCount(campfireActionsAct3);
            cs.campfireActionsAct4 = sortedMapValuesByCount(campfireActionsAct4);

            // 火堆统一分母：行动占比、占锻造、占火堆都从这两个计数出发。
            fillCampfireCounts(cs, 1, campfireActionsAct1);
            fillCampfireCounts(cs, 2, campfireActionsAct2);
            fillCampfireCounts(cs, 3, campfireActionsAct3);
            fillCampfireCounts(cs, 4, campfireActionsAct4);
            for (int act = 1; act <= 4; act++) {
                cs.campfireVisits[0] += cs.campfireVisits[act];
                cs.campfireSmiths[0] += cs.campfireSmiths[act];
            }

            // 为火堆行动添加总数统计（强制放在第一个）
            insertCampfireTotal(cs);

            // 火堆偏好（卡牌层）：按权重降序（win=被敲次数，loss=可选但没敲的次数）
            cs.campfireUpgradePrefTotal = sortedMapValues(campfireUpgradePrefTotal);
            cs.campfireUpgradePrefAct1 = sortedMapValues(campfireUpgradePrefAct1);
            cs.campfireUpgradePrefAct2 = sortedMapValues(campfireUpgradePrefAct2);
            cs.campfireUpgradePrefAct3 = sortedMapValues(campfireUpgradePrefAct3);
            cs.campfireUpgradePrefAct4 = sortedMapValues(campfireUpgradePrefAct4);

            // 火堆选项权重：固定 6 行，顺序与 OPTION_KEYS 一致
            cs.campfireOptionsTotal = buildCampfireOptions(0);
            cs.campfireOptionsAct1 = buildCampfireOptions(1);
            cs.campfireOptionsAct2 = buildCampfireOptions(2);
            cs.campfireOptionsAct3 = buildCampfireOptions(3);
            cs.campfireOptionsAct4 = buildCampfireOptions(4);

            // 火堆决策分布：把「升级」拆回每张卡，与遗物行动、非强制回忆同台竞争
            cs.campfireChoiceDistTotal = buildChoiceDist(0);
            cs.campfireChoiceDistAct1 = buildChoiceDist(1);
            cs.campfireChoiceDistAct2 = buildChoiceDist(2);
            cs.campfireChoiceDistAct3 = buildChoiceDist(3);
            cs.campfireChoiceDistAct4 = buildChoiceDist(4);

            // A 口径（替补）：锻造次数 ÷ 累计拥过副本数，不受删牌影响
            cs.campfireUpgradeAltTotal = buildAltRates(0);
            cs.campfireUpgradeAltAct1 = buildAltRates(1);
            cs.campfireUpgradeAltAct2 = buildAltRates(2);
            cs.campfireUpgradeAltAct3 = buildAltRates(3);
            cs.campfireUpgradeAltAct4 = buildAltRates(4);

            for (int act = 0; act <= 4; act++) {
                cs.campfireSmithUsable[act] = smithUsable[act];
                cs.campfireRecallForced[act] = recallForced[act];
                cs.campfireRecallOptional[act] = recallOptional[act];
                cs.campfireRebuildOk[act] = campfireRebuildOk[act];
                cs.campfireRebuildMiss[act] = campfireRebuildMiss[act];
                // 「升级」被拆成每张牌以后，池子 = 可敲卡种 + 可用遗物行动 + 可用回忆；
                // 卡牌和遗物共用这一个基线，倍数才能放在同一张榜上比。
                double poolAvg = optionVisits[act] > 0
                        ? (double) poolSizeSum[act] / optionVisits[act] : 0.0D;
                cs.campfireUpgradeAvgOptions[act] = poolAvg;
                cs.campfireUpgradeBaseline[act] = poolAvg > 0.0D ? 100.0D / poolAvg : 0.0D;
                // 睡觉另算：分母是火堆访问次数，不进竞争者池
                double screenOptions = optionVisits[act] > 0
                        ? (double) optionAvailableSum(act) / optionVisits[act] : 0.0D;
                cs.campfireAvgOptions[act] = screenOptions;
                cs.campfireOptionBaseline[act] = poolAvg > 0.0D ? 100.0D / poolAvg : 0.0D;
            }
            cs.campfireAstrolabeRuns = campfireAstrolabeRuns;
            cs.campfireEmptyCageRuns = campfireEmptyCageRuns;
            cs.campfireUpgradeMissCard = campfireMissCard;
            
            // 转移商店行动统计数据（将 Map 转换为排序后的 List）
            // 商店行动使用次数优先排序
            cs.shopActionsTotal = sortedMapValuesByCount(shopActionsTotal);
            cs.shopActionsAct1 = sortedMapValuesByCount(shopActionsAct1);
            cs.shopActionsAct2 = sortedMapValuesByCount(shopActionsAct2);
            cs.shopActionsAct3 = sortedMapValuesByCount(shopActionsAct3);
            cs.shopActionsAct4 = sortedMapValuesByCount(shopActionsAct4);
            
            // 为商店行动添加总数统计（强制放在第一个）
            insertShopTotal(cs);
        }
        
        /** 统计一幕的火堆访问次数与锻造次数；SMITH 的 key 是卡牌，其他行动 key 是字符串。 */
        private static void fillCampfireCounts(ClassStat cs, int act, Map<Object, Rate<Object>> actions) {
            int visits = 0;
            int smiths = 0;
            for (Rate<Object> rate : actions.values()) {
                if (rate == null) {
                    continue;
                }
                int count = rate.total();
                visits += count;
                if (rate.what instanceof Card) {
                    smiths += count;
                }
            }
            cs.campfireVisits[act] = visits;
            cs.campfireSmiths[act] = smiths;
        }

        private int optionAvailableSum(int act) {
            int sum = 0;
            for (int i = 0; i < OPTION_KEYS.length; i++) {
                sum += optionAvailable[act][i];
            }
            return sum;
        }

        /** 固定输出 6 个火堆选项；baseline 为在“可用选项数相同”前提下随机选择的期望权重。 */
        private List<ClassStat.CampfireOption> buildCampfireOptions(int act) {
            List<ClassStat.CampfireOption> rows = new ArrayList<>(OPTION_KEYS.length);
            // 统一基线：100 ÷ 平均池子大小。卡牌和遗物共用它，倍数才能同榜比较。
            double poolAvg = optionVisits[act] > 0 ? (double) poolSizeSum[act] / optionVisits[act] : 0.0D;
            double baseline = poolAvg > 0.0D ? 100.0D / poolAvg : 0.0D;
            for (int i = 0; i < OPTION_KEYS.length; i++) {
                int available = optionAvailable[act][i];
                int forced = i == OPT_RECALL ? recallForced[act] : 0;
                int optional = i == OPT_RECALL ? recallOptional[act] : 0;
                rows.add(new ClassStat.CampfireOption(OPTION_KEYS[i], optionChosen[act][i],
                        available, baseline, forced, optional));
            }
            return rows;
        }

        /**
         * 火堆统一榜：把「升级」拆回每张可敲的牌，和抽烟/举重/挖掘/回忆放在同一张榜上按占比排序。
         *
         * - 每张牌一行：chosen = 被敲次数，visits = 它出现在候选里的火堆次数。
         * - 每个行动一行：chosen = 被选次数，visits = 它真正可选的次数。
         * - 「升级」本身不进来（它就是通往每张牌的入口），睡觉也不进来（约束是血量，单独统计）。
         * - 所有行的分母虽然来源不同，但都是「该条目出现时被选中的比例」，可以直接横向比。
         */
        private List<ClassStat.CampfireShare> buildChoiceDist(int act) {
            List<ClassStat.CampfireShare> rows = new ArrayList<ClassStat.CampfireShare>();
            for (Rate<Card> rate : upgradePrefMapInstance(act).values()) {
                if (rate == null || rate.what == null || rate.total() <= 0) {
                    continue;
                }
                rows.add(new ClassStat.CampfireShare(rate.what, rate.win, rate.total(), true));
            }
            for (ClassStat.CampfireOption option : buildCampfireOptions(act)) {
                if ("SMITH".equals(option.key) || "REST".equals(option.key) || option.available <= 0) {
                    continue;
                }
                rows.add(new ClassStat.CampfireShare(option.key, option.chosen, option.available, false));
            }
            Collections.sort(rows, (a, b) -> Double.compare(b.percent(), a.percent()));
            return rows;
        }

        /**
         * A 口径（替补）：该幕锻造次数 ÷ 到该幕结束「累计拥过」的副本数。
         * 只依赖获得记录、不依赖当时的卡组快照，所以星盘/空鸟笼删掉哪张都不影响它。
         * 适合在重建失败（某次锻造的目标仍找不到）时读，代价是它回答的是「最终被敲率」，
         * 不是「每次火堆被选中的概率」。
         */
        private List<Rate<Card>> buildAltRates(int act) {
            Map<Card, Integer> owned = act < altOwnedByAct.size() ? altOwnedByAct.get(act) : null;
            if (owned == null || owned.isEmpty()) {
                return new ArrayList<>();
            }
            Map<Card, Rate<Card>> pref = upgradePrefMapInstance(act);
            Map<Card, Rate<Card>> out = new HashMap<>();
            for (Map.Entry<Card, Integer> entry : owned.entrySet()) {
                Card card = entry.getKey();
                int total = entry.getValue() == null ? 0 : entry.getValue();
                if (total <= 0 || isUpgradeImmune(card.name)) {
                    continue;
                }
                Rate<Card> source = pref.get(card);
                int win = source == null ? 0 : Math.min(source.win, total);
                Rate<Card> alt = new Rate<>(card);
                alt.win = win;
                alt.loss = total - win;
                out.put(card, alt);
            }
            return sortedMapValues(out);
        }

        /**
         * 为火堆行动添加总数统计（将各幕的总数汇总，强制插入到第一位置）
         */
        private void insertCampfireTotal(ClassStat cs) {
            // 计算各幕火堆行动的总数（分别统计胜场和负场）
            int winAct1 = campfireActionsAct1.values().stream().mapToInt(r -> r.win).sum();
            int lossAct1 = campfireActionsAct1.values().stream().mapToInt(r -> r.loss).sum();
            int winAct2 = campfireActionsAct2.values().stream().mapToInt(r -> r.win).sum();
            int lossAct2 = campfireActionsAct2.values().stream().mapToInt(r -> r.loss).sum();
            int winAct3 = campfireActionsAct3.values().stream().mapToInt(r -> r.win).sum();
            int lossAct3 = campfireActionsAct3.values().stream().mapToInt(r -> r.loss).sum();
            int winAct4 = campfireActionsAct4.values().stream().mapToInt(r -> r.win).sum();
            int lossAct4 = campfireActionsAct4.values().stream().mapToInt(r -> r.loss).sum();
            
            // 创建总数统计项
            Rate<Object> totalAct1Rate = new Rate<>("火堆总数");
            totalAct1Rate.win = winAct1;
            totalAct1Rate.loss = lossAct1;
            
            Rate<Object> totalAct2Rate = new Rate<>("火堆总数");
            totalAct2Rate.win = winAct2;
            totalAct2Rate.loss = lossAct2;
            
            Rate<Object> totalAct3Rate = new Rate<>("火堆总数");
            totalAct3Rate.win = winAct3;
            totalAct3Rate.loss = lossAct3;
            
            Rate<Object> totalAct4Rate = new Rate<>("火堆总数");
            totalAct4Rate.win = winAct4;
            totalAct4Rate.loss = lossAct4;
            
            // 插入到各幕列表的第一个位置
            cs.campfireActionsAct1.add(0, totalAct1Rate);
            cs.campfireActionsAct2.add(0, totalAct2Rate);
            cs.campfireActionsAct3.add(0, totalAct3Rate);
            cs.campfireActionsAct4.add(0, totalAct4Rate);
        }
        
        /**
         * 为商店行动添加总数统计（统计遇到的商店次数，强制插入到第一位置）
         * 注意：现在使用 shop_contents 统计真正的商店总数（包括未购买的商店）
         * 格式：win=胜利样本遇到的商店次数，loss=失败样本遇到的商店次数
         */
        private void insertShopTotal(ClassStat cs) {
            // 1. 商店总数：统计各幕遇到的商店次数（直接使用计数器）
            int totalWinAct1 = shopCountAct1Win;
            int totalLossAct1 = shopCountAct1Loss;
            
            int totalWinAct2 = shopCountAct2Win;
            int totalLossAct2 = shopCountAct2Loss;
            
            int totalWinAct3 = shopCountAct3Win;
            int totalLossAct3 = shopCountAct3Loss;
            
            int totalWinAct4 = shopCountAct4Win;
            int totalLossAct4 = shopCountAct4Loss;
            
            // 2. 有效商店数：统计各幕有购买行为的商店次数（使用计数器）
            int validWinAct1 = shopValidCountAct1Win;
            int validLossAct1 = shopValidCountAct1Loss;
            
            int validWinAct2 = shopValidCountAct2Win;
            int validLossAct2 = shopValidCountAct2Loss;
            
            int validWinAct3 = shopValidCountAct3Win;
            int validLossAct3 = shopValidCountAct3Loss;
            
            int validWinAct4 = shopValidCountAct4Win;
            int validLossAct4 = shopValidCountAct4Loss;
            
            // 创建商店总数统计项
            Rate<Object> totalAct1Rate = new Rate<>("商店总数");
            totalAct1Rate.win = totalWinAct1;
            totalAct1Rate.loss = totalLossAct1;
            
            Rate<Object> totalAct2Rate = new Rate<>("商店总数");
            totalAct2Rate.win = totalWinAct2;
            totalAct2Rate.loss = totalLossAct2;
            
            Rate<Object> totalAct3Rate = new Rate<>("商店总数");
            totalAct3Rate.win = totalWinAct3;
            totalAct3Rate.loss = totalLossAct3;
            
            Rate<Object> totalAct4Rate = new Rate<>("商店总数");
            totalAct4Rate.win = totalWinAct4;
            totalAct4Rate.loss = totalLossAct4;
            
            // 创建有效商店数统计项
            Rate<Object> validAct1Rate = new Rate<>("有效商店数");
            validAct1Rate.win = validWinAct1;
            validAct1Rate.loss = validLossAct1;
            
            Rate<Object> validAct2Rate = new Rate<>("有效商店数");
            validAct2Rate.win = validWinAct2;
            validAct2Rate.loss = validLossAct2;
            
            Rate<Object> validAct3Rate = new Rate<>("有效商店数");
            validAct3Rate.win = validWinAct3;
            validAct3Rate.loss = validLossAct3;
            
            Rate<Object> validAct4Rate = new Rate<>("有效商店数");
            validAct4Rate.win = validWinAct4;
            validAct4Rate.loss = validLossAct4;
            
            // 插入到各幕列表的第一个位置（商店总数 -> 有效商店数 -> 具体物品）
            cs.shopActionsAct1.add(0, totalAct1Rate);
            cs.shopActionsAct1.add(1, validAct1Rate);
            
            cs.shopActionsAct2.add(0, totalAct2Rate);
            cs.shopActionsAct2.add(1, validAct2Rate);
            
            cs.shopActionsAct3.add(0, totalAct3Rate);
            cs.shopActionsAct3.add(1, validAct3Rate);
            
            cs.shopActionsAct4.add(0, totalAct4Rate);
            cs.shopActionsAct4.add(1, validAct4Rate);
        }
        
        /**
         * 为战损统计添加战斗总计（累加每一次对局的独立 actualHPLoss 记录，插入序列开头）
         */
        private void insertHPLossTotal(ClassStat cs) {
            for (int act : new int[]{1, 2, 3, 4}) {
                // 平均实际战损：插入战斗总计和非Boss战斗总计到序列开头
                List<Mean> actualList = avgEncounterActualHPLoss.get(act);
                if (actualList != null && !actualList.isEmpty()) {
                    actualList.add(0, totalActualHPLossPerAct.get(act));
                    actualList.add(1, totalNonBossActualHPLossPerAct.get(act));
                }
                // 拆分战损（无回血）：插入总计到序列开头
                List<Mean> noHealList = avgEncounterNoHealActualHPLoss.get(act);
                if (noHealList != null && !noHealList.isEmpty()) {
                    noHealList.add(0, totalNoHealActualHPLossPerAct.get(act));
                }
                // 拆分战损（有回血）：插入总计到序列开头
                List<Mean> healList = avgEncounterHealActualHPLoss.get(act);
                if (healList != null && !healList.isEmpty()) {
                    healList.add(0, totalHealActualHPLossPerAct.get(act));
                }
            }
        }
    }

    public ClassStat(List<Run> runs, boolean rotate) {
        this.rotate = rotate;
        if (rotate) {
            WinStreak streak = WinStreak.getWinStreak(runs, null);
            if (streak != null) {
                bestWinStreak = streak.streak;
            }
        } else if (!runs.isEmpty()) {
            AbstractPlayer.PlayerClass playerClass = runs.get(0).playerClass;
            WinStreak streak = WinStreak.getWinStreak(runs, playerClass);
            if (streak != null) {
                bestWinStreak = streak.streak;
            }
        } else {
            bestWinStreak = 0;
        }
if (!runs.isEmpty()) {
            minDeckSize = Integer.MAX_VALUE;
        }

        
        StatCollector collector = new StatCollector();

        for (Run run : runs) {
            collector.collect(run);
            this.metaScaling.addRun(run);

            playTime += run.playtime;
            if (run.isHeartKill) {
                fastestTime = Math.min(fastestTime, run.playtime);
            }
            if (run.isHeartKill) {
                ++numVictory;
            } else {
                ++numDeath;
            }
            int deckSize = run.masterDeck.size();
            minDeckSize = Math.min(minDeckSize, deckSize);
            maxDeckSize = Math.max(maxDeckSize, deckSize);
            totalFloorsClimbed += run.floorsReached;
            bossKilled += run.bossesKilled;
            enemyKilled += run.enemiesKilled;
            highestScore = Math.max(highestScore, run.score);
        }

        collector.finalise(this);
    }
}
