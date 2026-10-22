package tinycat.stats;

import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.screens.stats.BossRelicChoiceStats;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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
                run.neowSkipped.forEach(neow -> {
                    Rate<Neow> skipRate = neowPickRate.computeIfAbsent(neow, k -> new Rate<>(k));
                    skipRate.loss++;
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
                int act = run.getAct(es.floor);
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
                int act = run.getAct(run.floorsReached);
                Rate<String> k = encounterDeadRate.getOrDefault(act, new HashMap<>()).get(killedBy);
                if (k != null) {
                    k.win++;
                    k.loss--;
                }
            }
        }

        // Boss -> 正确 Act 映射（与 Python analyze_hp_loss.py 一致）
        private static final Map<String, Integer> BOSS_ACT = new HashMap<String, Integer>() {{
            put("Hexaghost", 1); put("Slime Boss", 1); put("The Guardian", 1);
            put("Collector", 2); put("Automaton", 2); put("Champ", 2);
            put("Time Eater", 3); put("Awakened One", 3); put("Donu and Deca", 3);
        }};

        /**
         * Boss Act 矫正：如果 Boss 出现在错误 Act 中，
         * 正确 Act 已有该 Boss → 丢弃错误数据；没有 → 归入正确 Act。
         * 与 Python analyze_hp_loss.py 的 BOSS_ACT 矫正逻辑一致。
         */
        private void correctBossAct() {
            // 对 encounterActualHPLossMap 进行 Boss Act 矫正
            for (int act : new int[]{1, 2, 3, 4}) {
                Map<String, Mean> actMap = encounterActualHPLossMap.get(act);
                if (actMap == null) continue;

                List<String> toRemove = new ArrayList<>();
                List<Map.Entry<String, Mean>> toMove = new ArrayList<>();

                for (Map.Entry<String, Mean> entry : actMap.entrySet()) {
                    String name = entry.getKey();
                    Integer correctAct = BOSS_ACT.get(name);
                    if (correctAct != null && correctAct != act) {
                        Map<String, Mean> correctActMap = encounterActualHPLossMap.get(correctAct);
                        if (correctActMap != null && correctActMap.containsKey(name)) {
                            toRemove.add(name);
                        } else {
                            toMove.add(entry);
                        }
                    }
                }
                for (String name : toRemove) {
                    actMap.remove(name);
                }
                for (Map.Entry<String, Mean> entry : toMove) {
                    actMap.remove(entry.getKey());
                    Integer correctAct = BOSS_ACT.get(entry.getKey());
                    encounterActualHPLossMap.get(correctAct).put(entry.getKey(), entry.getValue());
                }
            }
        }

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
            int act = run.getAct(floor);

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
                runAllHPLossSum.merge(run.getAct(es.floor), (double) es.actualHPLoss, Double::sum);
            }
            if (run.eventStats != null) {
                for (com.megacrit.cardcrawl.screens.stats.EventStats event : run.eventStats) {
                    if (event.damage_taken > 0) {
                        runAllHPLossSum.merge(run.getAct(event.floor), (double) event.damage_taken, Double::sum);
                    }
                }
            }
            // 每幕总战损按“每局每幕一个样本”统计；没有战损的幕也要记 0，
            // 否则平均值只会包含发生过战损的对局，结果会偏高。
            int lastAct = Math.min(4, Math.max(1, run.getAct(Math.max(1, run.floorsReached))));
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
                int act = run.getAct(e.floor);
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
                    
                    // 调试：统计第二幕的 REST 行动
                    if ("REST".equals(actionKey) && act == 2) {
                        System.out.println("DEBUG: 第二幕 REST, floor=" + choice.floor + ", run.isHeartKill=" + run.isHeartKill);
                    }
                    
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
                    
                    // 对卡牌名称进行标准化处理（移除升级后缀）
                    String normalizedKey = normalizeCardName(itemKey);
                    Object statKey = (normalizedKey != null) ? normalizedKey : itemKey;
                    
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
            cs.neowWinRate = sortedMapValues(neowWinRate);

            cs.bossRelicPickRateAct1 = sortedMapValues(bossRelicPickRateAct1);
            cs.bossRelicPickRateAct2 = sortedMapValues(bossRelicPickRateAct2);

            cs.bossRelicWinRateAct1 = sortedMapValues(bossRelicWinRateAct1);
            cs.bossRelicWinRateAct2 = sortedMapValues(bossRelicWinRateAct2);
            cs.bossRelicWinRateSwap = sortedMapValues(bossRelicWinRateSwap);

            // Boss Act 矫正（与 Python analyze_hp_loss.py 一致）
            correctBossAct();

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
            
            // 为火堆行动添加总数统计（强制放在第一个）
            insertCampfireTotal(cs);
            
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
