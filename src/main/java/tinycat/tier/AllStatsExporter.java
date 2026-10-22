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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 ClassStat 中 TinyCat 当前所有统计维度导出为 CSV。
 *
 * 输出：
 * - summary.csv：角色/筛选/基础统计/估算标记
 * - all_stats.csv：长表，所有 Rate/Mean/Map/标量，一行一个统计项
 *
 * 这样总体统计页导出的包不再只有 Tier List，而是全部统计都有 CSV。
 */
public final class AllStatsExporter {
    private AllStatsExporter() {
    }

    public static void write(MoreStatsScreen screen, TierListModel model, File root) throws IOException {
        ClassStat cs = screen.getClassStat();
        writeSummary(screen, model, cs, new File(root, "summary.csv"));
        writeAllStats(cs, model, new File(root, "all_stats.csv"));
    }

    private static void writeSummary(MoreStatsScreen screen, TierListModel model, ClassStat cs, File file) throws IOException {
        Writer w = writer(file);
        try {
            w.write("metric,value,note\n");
            scalar(w, "schema_version", "2", "");
            scalar(w, "generated_at", model.generatedAt, "");
            scalar(w, "character_id", model.characterId, "");
            scalar(w, "character_kind", model.characterKind(),
                    model.isAggregate() ? "aggregate of all four characters" : "single character");
            scalar(w, "character_count", String.valueOf(model.characterCount()), "base-game characters");
            scalar(w, "character_en", model.characterEn, "");
            scalar(w, "character_zh", model.characterZh, "");
            scalar(w, "scope", model.scope == null ? "all" : model.scope.key, "");
            scalar(w, "date_start", model.startDate, "");
            scalar(w, "date_end", model.endDate, "");
            scalar(w, "include_abandons", String.valueOf(model.includeAbandons), "");
            scalar(w, "seed_filter", model.seedFilter.key,
                    model.seedFilter.labelEn + " / " + model.seedFilter.labelZh);
            scalar(w, "min_sample", String.valueOf(model.minSample), "");
            int runs = cs.numVictory + cs.numDeath;
            scalar(w, "run_count", String.valueOf(runs), "");
            scalar(w, "num_victory", String.valueOf(cs.numVictory), "");
            scalar(w, "num_death", String.valueOf(cs.numDeath), "");
            scalar(w, "win_rate_pct", format(runs == 0 ? 0.0 : 100.0 * cs.numVictory / runs), "");
            scalar(w, "play_time_seconds", String.valueOf(cs.playTime), "");
            scalar(w, "fastest_win_seconds", String.valueOf(cs.fastestTime == Integer.MAX_VALUE ? 0 : cs.fastestTime), "");
            scalar(w, "best_win_streak", String.valueOf(cs.bestWinStreak), "");
            scalar(w, "highest_score", String.valueOf(cs.highestScore), "");
            scalar(w, "total_floors_climbed", String.valueOf(cs.totalFloorsClimbed), "");
            scalar(w, "boss_killed", String.valueOf(cs.bossKilled), "");
            scalar(w, "enemy_killed", String.valueOf(cs.enemyKilled), "");
            scalar(w, "average_score", mean(cs.averageScore), "");
            scalar(w, "average_gold_earned", mean(cs.averageGold),
                    "cumulative earned gold; estimated from gold_per_floor unless gold-tracker exists; .run only stores final gold");
            scalar(w, "average_deck_size", mean(cs.averageDeckSize), "");
            scalar(w, "min_deck_size", String.valueOf(cs.minDeckSize), "");
            scalar(w, "max_deck_size", String.valueOf(cs.maxDeckSize), "");
            scalar(w, "average_winning_time_seconds", mean(cs.averageWinningTime), "");
            scalar(w, "total_cards_purged", String.valueOf(cs.totalCardsPurged), "");
            scalar(w, "shop_purges", String.valueOf(cs.shopPurges), "");
            scalar(w, "event_purges", String.valueOf(cs.eventPurges), "");
            scalar(w, "neow_purges", String.valueOf(cs.neowPurges), "");
            scalar(w, "campfire_purges", String.valueOf(cs.campfirePurges), "");
            scalar(w, "empty_cage_purges", String.valueOf(cs.emptyCagePurges), "");
            scalar(w, "total_question_marks", String.valueOf(cs.totalQuestionMarks), "");
            scalar(w, "total_events", String.valueOf(cs.totalEvents), "");
            scalar(w, "gold_estimated_runs", String.valueOf(cs.goldEstimatedRuns),
                    "runs whose cumulative gold was estimated");
            scalar(w, "gold_exact_runs", String.valueOf(cs.goldExactRuns),
                    "runs with gold-tracker exact cumulative gold");
            scalar(w, "floor1_hp_loss_estimated_runs", String.valueOf(cs.floor0HPEstimatedRuns),
                    "runs whose floor-1 HP loss was estimated from damage (Ironclad: -6)");
        } finally {
            w.close();
        }
    }

    private static void writeAllStats(ClassStat cs, TierListModel model, File file) throws IOException {
        Writer w = writer(file);
        try {
            w.write("category,table,act,key,name_en,name_zh,win,loss,total,percent,mean,samples,value,note\n");
            // 导出上下文：让 all_stats.csv 单独打开时也不会把全角色汇总误认为第 5 个角色
            scalarRow(w, "summary", "character_id", model.characterId, "");
            scalarRow(w, "summary", "character_kind", model.characterKind(), "");
            scalarRow(w, "summary", "character_count", model.characterCount(), "");
            scalarRow(w, "summary", "seed_filter", model.seedFilter.key, "");
            // 基础
            scalarRow(w, "summary", "run_count", cs.numVictory + cs.numDeath, "");
            scalarRow(w, "summary", "num_victory", cs.numVictory, "");
            scalarRow(w, "summary", "num_death", cs.numDeath, "");
            scalarRow(w, "summary", "win_rate_pct", 100.0 * cs.numVictory / Math.max(1, cs.numVictory + cs.numDeath), "");
            scalarRow(w, "summary", "play_time_seconds", cs.playTime, "");
            scalarRow(w, "summary", "fastest_win_seconds", cs.fastestTime == Integer.MAX_VALUE ? 0 : cs.fastestTime, "");
            scalarRow(w, "summary", "best_win_streak", cs.bestWinStreak, "");
            scalarRow(w, "summary", "highest_score", cs.highestScore, "");
            scalarRow(w, "summary", "total_floors_climbed", cs.totalFloorsClimbed, "");
            scalarRow(w, "summary", "boss_killed", cs.bossKilled, "");
            scalarRow(w, "summary", "enemy_killed", cs.enemyKilled, "");
            scalarRow(w, "summary", "average_score", cs.averageScore == null ? 0 : cs.averageScore.mean(), "");
            scalarRow(w, "summary", "average_gold_earned", cs.averageGold == null ? 0 : cs.averageGold.mean(),
                    "cumulative earned gold; estimated unless gold-tracker exact; .run has final gold only");
            scalarRow(w, "summary", "average_deck_size", cs.averageDeckSize == null ? 0 : cs.averageDeckSize.mean(), "");
            scalarRow(w, "summary", "min_deck_size", cs.minDeckSize, "");
            scalarRow(w, "summary", "max_deck_size", cs.maxDeckSize, "");
            scalarRow(w, "summary", "average_winning_time_seconds", cs.averageWinningTime == null ? 0 : cs.averageWinningTime.mean(), "");
            scalarRow(w, "summary", "gold_estimated_runs", cs.goldEstimatedRuns, "");
            scalarRow(w, "summary", "gold_exact_runs", cs.goldExactRuns, "");
            scalarRow(w, "summary", "floor1_hp_loss_estimated_runs", cs.floor0HPEstimatedRuns, "");

            // 卡牌
            rateList(w, "cards", "card_picks_act1", 1, cs.cardPicksAct1, "");
            rateList(w, "cards", "card_picks_after_act1", 2, cs.cardPicksAfterAct1, "");
            rateList(w, "cards", "card_win_rate", 0, cs.cardWinRate, "win rate when card is in final deck");
            // Neow
            rateList(w, "neow", "neow_win_rate", 0, cs.neowWinRate, "");
            rateList(w, "neow", "neow_pick_rate", 0, cs.neowPickRate, "");
            // Boss 遗物
            rateList(w, "boss_relic", "boss_relic_pick_act1", 1, cs.bossRelicPickRateAct1, "");
            rateList(w, "boss_relic", "boss_relic_pick_act2", 2, cs.bossRelicPickRateAct2, "");
            rateList(w, "boss_relic", "boss_relic_win_act1", 1, cs.bossRelicWinRateAct1, "");
            rateList(w, "boss_relic", "boss_relic_win_act2", 2, cs.bossRelicWinRateAct2, "");
            rateList(w, "boss_relic", "boss_relic_win_swap", 0, cs.bossRelicWinRateSwap, "");

            // 战斗/战损
            meanMap(w, "encounter", "average_damage_taken", cs.averageDamageTaken,
                    "floor-1 component is estimated from damage; Ironclad -6");
            meanMap(w, "encounter", "average_actual_hp_loss", cs.averageActualHPLoss,
                    "floor-1 component is estimated from damage; Ironclad -6");
            meanMap(w, "encounter", "average_no_heal_hp_loss", cs.averageNoHealActualHPLoss, "");
            meanMap(w, "encounter", "average_heal_hp_loss", cs.averageHealActualHPLoss, "");
            meanMap(w, "encounter", "average_event_hp_loss", cs.averageEventHPLoss, "");
            meanMap(w, "encounter", "average_all_hp_loss", cs.averageAllHPLoss,
                    "combat actual HP loss + event damage_taken, one sample per run/act");
            rateMap(w, "encounter", "encounter_mortality_rate", cs.encounterDeadRate, "");
            if (cs.nob != null) {
                rateRow(w, "encounter", "nob_survival_rate", 0, cs.nob, "");
            }
            meanMap(w, "encounter", "average_potions_used", cs.averagePotionUse, "");

            // 存活率
            if (cs.survivalRatePerAct != null) {
                for (int act = 1; act <= 4; act++) {
                    Rate<Integer> r = cs.survivalRatePerAct.get(act);
                    if (r != null) {
                        rateRow(w, "survival", "survival_rate_per_act", act, r, "");
                    }
                }
            }
            rateList(w, "relic", "relic_win_rate", 0, cs.relicWinRate, "");
            rateList(w, "relic", "relic_purchased_win_rate", 0, cs.relicPurchasedWinRate, "");
            rateList(w, "event", "event_win_rate_act1", 1, cs.eventWinRateAct1, "");
            rateList(w, "event", "event_win_rate_act2", 2, cs.eventWinRateAct2, "");
            rateList(w, "event", "event_win_rate_act3", 3, cs.eventWinRateAct3, "");

            // 删牌
            scalarRow(w, "purge", "total_cards_purged", cs.totalCardsPurged, "");
            scalarRow(w, "purge", "shop_purges", cs.shopPurges, "");
            scalarRow(w, "purge", "event_purges", cs.eventPurges, "");
            scalarRow(w, "purge", "neow_purges", cs.neowPurges, "");
            scalarRow(w, "purge", "campfire_purges", cs.campfirePurges, "");
            scalarRow(w, "purge", "empty_cage_purges", cs.emptyCagePurges, "");
            if (cs.purgesBySource != null) {
                for (Map.Entry<?, Integer> e : cs.purgesBySource.entrySet()) {
                    String key = String.valueOf(e.getKey());
                    scalarKeyRow(w, "purge", "purges_by_source", 0, key, e.getValue(), "");
                }
            }
            if (cs.purgesByAct != null) {
                for (Map.Entry<Integer, Integer> e : cs.purgesByAct.entrySet()) {
                    scalarKeyRow(w, "purge", "purges_by_act", e.getKey(), String.valueOf(e.getKey()), e.getValue(), "");
                }
            }

            // 问号/事件
            scalarRow(w, "event", "total_question_marks", cs.totalQuestionMarks, "");
            scalarRow(w, "event", "total_events", cs.totalEvents, "");
            if (cs.questionMarksByAct != null) {
                for (Map.Entry<Integer, Integer> e : cs.questionMarksByAct.entrySet()) {
                    scalarKeyRow(w, "event", "question_marks_by_act", e.getKey(), String.valueOf(e.getKey()), e.getValue(), "");
                }
            }

            // 火堆/商店
            rateList(w, "campfire", "campfire_actions_total", 0, cs.campfireActionsTotal, "");
            rateList(w, "campfire", "campfire_actions_act1", 1, cs.campfireActionsAct1, "");
            rateList(w, "campfire", "campfire_actions_act2", 2, cs.campfireActionsAct2, "");
            rateList(w, "campfire", "campfire_actions_act3", 3, cs.campfireActionsAct3, "");
            rateList(w, "campfire", "campfire_actions_act4", 4, cs.campfireActionsAct4, "");
            rateList(w, "shop", "shop_actions_total", 0, cs.shopActionsTotal, "");
            rateList(w, "shop", "shop_actions_act1", 1, cs.shopActionsAct1, "");
            rateList(w, "shop", "shop_actions_act2", 2, cs.shopActionsAct2, "");
            rateList(w, "shop", "shop_actions_act3", 3, cs.shopActionsAct3, "");
            rateList(w, "shop", "shop_actions_act4", 4, cs.shopActionsAct4, "");

            // Meta scaling
            if (cs.metaScaling != null) {
                scalarRow(w, "meta", "max_relics", cs.metaScaling.maxRelic, "");
                scalarRow(w, "meta", "max_gold_earned", cs.metaScaling.maxGold, "cumulative earned gold estimate");
                scalarRow(w, "meta", "max_removes", cs.metaScaling.maxRemoves, "");
                scalarRow(w, "meta", "max_ritual_dagger", cs.metaScaling.maxDagger, "");
                scalarRow(w, "meta", "max_hp", cs.metaScaling.maxHP, "");
                scalarRow(w, "meta", "max_searing_blow", cs.metaScaling.maxSearingBlow, "");
                scalarRow(w, "meta", "max_potions_created", cs.metaScaling.maxAlch, "");
                scalarRow(w, "meta", "max_genetic_algorithm", cs.metaScaling.maxAlgo, "");
                scalarRow(w, "meta", "max_lessons_learned", cs.metaScaling.maxLesson, "");
            }
        } finally {
            w.close();
        }
    }

    private static void rateList(Writer w, String category, String table, int act,
                                 List<? extends Rate<?>> rates, String note) throws IOException {
        if (rates == null) {
            return;
        }
        for (Rate<?> rate : rates) {
            if (rate != null) {
                rateRow(w, category, table, act, rate, note);
            }
        }
    }

    private static void rateMap(Writer w, String category, String table,
                                Map<Integer, ? extends List<? extends Rate<?>>> map, String note) throws IOException {
        if (map == null) {
            return;
        }
        for (Map.Entry<Integer, ? extends List<? extends Rate<?>>> e : map.entrySet()) {
            rateList(w, category, table, e.getKey(), e.getValue(), note);
        }
    }

    private static void meanMap(Writer w, String category, String table, Map<Integer, List<Mean>> map, String note) throws IOException {
        if (map == null) {
            return;
        }
        for (Map.Entry<Integer, List<Mean>> e : map.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            for (Mean mean : e.getValue()) {
                if (mean != null && mean.getSampleSize() > 0) {
                    meanRow(w, category, table, e.getKey(), mean, e.getKey() == 1 ? note : "");
                }
            }
        }
    }

    private static void rateRow(Writer w, String category, String table, int act, Rate<?> rate, String note) throws IOException {
        String key = keyOf(rate.what);
        row(w, category, table, act, key, key, NameMapper.toChinese(key),
                String.valueOf(rate.win), String.valueOf(rate.loss), String.valueOf(rate.total()),
                format(rate.percent()), "", String.valueOf(rate.total()), "", note);
    }

    private static void meanRow(Writer w, String category, String table, int act, Mean mean, String note) throws IOException {
        String key = mean.what == null ? "" : mean.what;
        row(w, category, table, act, key, key, NameMapper.toChinese(key),
                "", "", "", "", format(mean.mean()), String.valueOf(mean.getSampleSize()), "", note);
    }

    private static void scalarRow(Writer w, String category, String key, Object value, String note) throws IOException {
        row(w, category, "", 0, key, key, NameMapper.toChinese(key),
                "", "", "", "", "", "", String.valueOf(value), note);
    }

    private static void scalarKeyRow(Writer w, String category, String table, int act, String key, Object value, String note) throws IOException {
        row(w, category, table, act, key, key, NameMapper.toChinese(key),
                "", "", "", "", "", "", String.valueOf(value), note);
    }

    private static void row(Writer w, String category, String table, int act,
                            String key, String nameEn, String nameZh,
                            String win, String loss, String total, String percent,
                            String mean, String samples, String value, String note) throws IOException {
        w.write(csv(category));
        w.write(',');
        w.write(csv(table));
        w.write(',');
        w.write(act <= 0 ? "" : String.valueOf(act));
        w.write(',');
        w.write(csv(key));
        w.write(',');
        w.write(csv(nameEn));
        w.write(',');
        w.write(csv(nameZh));
        w.write(',');
        w.write(win);
        w.write(',');
        w.write(loss);
        w.write(',');
        w.write(total);
        w.write(',');
        w.write(percent);
        w.write(',');
        w.write(mean);
        w.write(',');
        w.write(samples);
        w.write(',');
        w.write(value);
        w.write(',');
        w.write(csv(note));
        w.write('\n');
    }

    private static String keyOf(Object what) {
        if (what instanceof Card) {
            return ((Card) what).name;
        }
        if (what instanceof BossRelic) {
            return ((BossRelic) what).name;
        }
        if (what instanceof Neow) {
            return what.toString();
        }
        return what == null ? "" : String.valueOf(what);
    }

    private static String mean(Mean mean) {
        return mean == null ? "" : format(mean.mean());
    }

    private static String format(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static void scalar(Writer w, String key, String value, String note) throws IOException {
        w.write(csv(key));
        w.write(',');
        w.write(csv(value));
        w.write(',');
        w.write(csv(note));
        w.write('\n');
    }

    private static Writer writer(File file) throws IOException {
        Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        w.write('\uFEFF');
        return w;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0
                || escaped.indexOf('\n') >= 0 || escaped.indexOf('\r') >= 0) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
