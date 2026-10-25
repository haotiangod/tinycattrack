package tinycat.tier;

import tinycat.YearMonth;
import tinycat.stats.EncounterStats;
import tinycat.stats.Run;
import tinycat.stats.SeedFilter;
import tinycat.tinycat;
import tinycat.ui.MoreStatsScreen;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 用每场遭遇的实际 HP 变化（战后 HP − 战前 HP）构建怪物危险度梯度。
 * Boss 单独成 T0；非 Boss（含精英）按幕内平均战损降序等人数分为 T1-T4。
 * 事件战和第四幕固定战不计入。
 */
public final class MonsterTierBuilder {
    public static final int BOSS_MIN_SAMPLE = 5;
    public static final int SINGLE_CHARACTER_MIN_SAMPLE = 10;
    public static final int AGGREGATE_MIN_SAMPLE = 30;

    private static final String[] CHARACTER_IDS = {
            "ALL", "IRONCLAD", "THE_SILENT", "DEFECT", "WATCHER"
    };
    private static final String[] CHARACTER_EN = {
            "All characters (aggregate)", "Ironclad", "Silent", "Defect", "Watcher"
    };
    private static final String[] CHARACTER_ZH = {
            "全角色汇总", "战士", "猎人", "机器人", "观者"
    };
    private static final String[] ACT_EN = {"Act 1", "Act 2", "Act 3"};
    private static final String[] ACT_ZH = {"第一幕", "第二幕", "第三幕"};

    private static final Set<String> BOSS_NAMES = new HashSet<String>();

    static {
        Collections.addAll(BOSS_NAMES,
                "Hexaghost", "Slime Boss", "The Guardian",
                "Collector", "Automaton", "Champ",
                "Time Eater", "Awakened One", "Donu and Deca");
    }

    private MonsterTierBuilder() {
    }

    public static MonsterTierModel build(MoreStatsScreen screen) {
        if (screen == null) {
            throw new IllegalStateException("statistics screen is not initialized");
        }
        int classIndex = screen.classDropdown == null ? 0 : screen.classDropdown.getSelectedIndex();
        if (classIndex < 0 || classIndex >= CHARACTER_IDS.length) {
            classIndex = 0;
        }
        String startDate = dateAt(screen.startDates,
                screen.startDateDropdown == null ? 0 : screen.startDateDropdown.getSelectedIndex());
        String endDate = dateAt(screen.endDates,
                screen.endDateDropDown == null ? 0 : screen.endDateDropDown.getSelectedIndex());
        if (startDate.isEmpty() || endDate.isEmpty()) {
            throw new IllegalStateException("statistics date range is not ready");
        }
        YearMonth start = parseDate(startDate);
        YearMonth end = parseDate(endDate);
        boolean includeAbandons = screen.abandonDropdown == null
                || screen.abandonDropdown.getSelectedIndex() == 0;
        SeedFilter seedFilter = screen.currentSeedFilter();
        if (seedFilter == null) {
            seedFilter = SeedFilter.ALL;
        }
        List<Run> runs = selectRuns(classIndex, start, end, includeAbandons, seedFilter);
        int bossMinSample = BOSS_MIN_SAMPLE;
        int nonBossMinSample = classIndex == 0
                ? AGGREGATE_MIN_SAMPLE : SINGLE_CHARACTER_MIN_SAMPLE;

        Map<Integer, Map<String, Sample>> bossSamples = new HashMap<Integer, Map<String, Sample>>();
        Map<Integer, Map<String, Sample>> nonBossSamples = new HashMap<Integer, Map<String, Sample>>();
        for (int act = 1; act <= 3; act++) {
            bossSamples.put(act, new HashMap<String, Sample>());
            nonBossSamples.put(act, new HashMap<String, Sample>());
        }

        for (Run run : runs) {
            collect(run, bossSamples, nonBossSamples);
        }

        List<MonsterTierGroup> groups = new ArrayList<MonsterTierGroup>(3);
        int lowSampleTotal = 0;
        for (int act = 1; act <= 3; act++) {
            Map<String, Sample> bossMap = bossSamples.get(act);
            Map<String, Sample> nonBossMap = nonBossSamples.get(act);
            int bossSampleTotal = sampleTotal(bossMap);
            int nonBossSampleTotal = sampleTotal(nonBossMap);
            int bossNetHpLoss = netHpLoss(bossMap);
            int nonBossNetHpLoss = netHpLoss(nonBossMap);

            List<MonsterTierEntry> bosses = new ArrayList<MonsterTierEntry>();
            List<MonsterTierEntry> nonBosses = new ArrayList<MonsterTierEntry>();
            // 不再有"样本不足附录"：所有条目都参与分档，只在条目上用 lowSample 标注。
            List<MonsterTierEntry> lowSampleEntries = new ArrayList<MonsterTierEntry>();
            int flagged = 0;

            int bossRank = 0;
            for (Sample sample : sortedSamples(bossMap)) {
                boolean low = sample.size() < bossMinSample;
                bossRank++;
                bosses.add(entry(sample, MonsterTier.T0, bossRank, 0,
                        bossSampleTotal, bossNetHpLoss, low));
                if (low) {
                    flagged++;
                }
            }

            flagged += appendNonBossTiers(sortedSamples(nonBossMap), nonBosses,
                    nonBossSampleTotal, nonBossNetHpLoss, nonBossMinSample);

            lowSampleTotal += flagged;
            groups.add(new MonsterTierGroup(act, ACT_EN[act - 1], ACT_ZH[act - 1],
                    bosses, nonBosses, lowSampleEntries,
                    bossSampleTotal, nonBossSampleTotal,
                    bossNetHpLoss, nonBossNetHpLoss));
        }

        String generatedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
        return new MonsterTierModel(generatedAt,
                CHARACTER_IDS[classIndex],
                CHARACTER_EN[classIndex],
                CHARACTER_ZH[classIndex],
                classIndex == 0,
                startDate,
                endDate,
                includeAbandons,
                seedFilter,
                bossMinSample,
                nonBossMinSample,
                runs.size(),
                lowSampleTotal,
                groups);
    }

    private static List<Run> selectRuns(int classIndex,
                                        YearMonth start,
                                        YearMonth end,
                                        boolean includeAbandons,
                                        SeedFilter seedFilter) {
        switch (classIndex) {
            case 1:
                return tinycat.runHistoryManager.getIroncladRuns(start, end, includeAbandons, seedFilter);
            case 2:
                return tinycat.runHistoryManager.getSilentRuns(start, end, includeAbandons, seedFilter);
            case 3:
                return tinycat.runHistoryManager.getDefectRuns(start, end, includeAbandons, seedFilter);
            case 4:
                return tinycat.runHistoryManager.getWatcherRuns(start, end, includeAbandons, seedFilter);
            default:
                return tinycat.runHistoryManager.getAllRuns(start, end, includeAbandons, seedFilter);
        }
    }

    private static void collect(Run run,
                                Map<Integer, Map<String, Sample>> bossSamples,
                                Map<Integer, Map<String, Sample>> nonBossSamples) {
        if (run == null || run.encounterStats == null) {
            return;
        }
        for (EncounterStats encounter : run.encounterStats) {
            if (encounter == null || encounter.enemies == null || encounter.enemies.isEmpty()) {
                continue;
            }
            int act = run.getActForStats(encounter.floor);
            if (act < 1 || act > 3) {
                continue;
            }
            String node = run.getPathNode(encounter.floor);
            String name = EncounterStats.getNameReplacementMap()
                    .getOrDefault(encounter.enemies, encounter.enemies);
            if ("B".equals(node)) {
                addSample(bossSamples.get(act), name, encounter.actualHPLoss, "boss");
            } else if ("M".equals(node) || "E".equals(node)) {
                addSample(nonBossSamples.get(act), name, encounter.actualHPLoss,
                        "E".equals(node) ? "elite" : "normal");
            } else if (node.isEmpty() && BOSS_NAMES.contains(name)) {
                // 老版本/第三方 RunHistory 可能没有 path_per_floor，至少保留可确认的 Boss。
                addSample(bossSamples.get(act), name, encounter.actualHPLoss, "boss");
            }
        }
    }

    private static void addSample(Map<String, Sample> samples,
                                  String name,
                                  int actualHpLoss,
                                  String nodeType) {
        Sample sample = samples.get(name);
        if (sample == null) {
            sample = new Sample(name);
            samples.put(name, sample);
        }
        sample.add(actualHpLoss, nodeType);
    }

    private static List<Sample> sortedSamples(Map<String, Sample> samples) {
        List<Sample> list = new ArrayList<Sample>(samples.values());
        Collections.sort(list, new Comparator<Sample>() {
            @Override
            public int compare(Sample a, Sample b) {
                int meanCompare = Double.compare(b.mean(), a.mean());
                if (meanCompare != 0) {
                    return meanCompare;
                }
                int countCompare = Integer.compare(b.size(), a.size());
                if (countCompare != 0) {
                    return countCompare;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
        return list;
    }

    /** 等人数分 T1-T4；样本不足的条目照样分档，只是打上 lowSample 标记。返回被标记的数量。 */
    private static int appendNonBossTiers(List<Sample> sorted,
                                           List<MonsterTierEntry> target,
                                           int categorySamples,
                                           int categoryNetHpLoss,
                                           int minSample) {
        int total = sorted.size();
        int base = total / 4;
        int extra = total % 4;
        int index = 0;
        int flagged = 0;
        MonsterTier[] tiers = {MonsterTier.T1, MonsterTier.T2, MonsterTier.T3, MonsterTier.T4};
        for (int tierIndex = 0; tierIndex < tiers.length; tierIndex++) {
            int count = base + (tierIndex < extra ? 1 : 0);
            for (int rank = 1; rank <= count; rank++) {
                Sample sample = sorted.get(index++);
                boolean low = sample.size() < minSample;
                target.add(entry(sample, tiers[tierIndex], rank, index,
                        categorySamples, categoryNetHpLoss, low));
                if (low) {
                    flagged++;
                }
            }
        }
        return flagged;
    }

    private static void sortLowSample(List<MonsterTierEntry> entries) {
        Collections.sort(entries, new Comparator<MonsterTierEntry>() {
            @Override
            public int compare(MonsterTierEntry a, MonsterTierEntry b) {
                int meanCompare = Double.compare(b.meanHpLoss, a.meanHpLoss);
                if (meanCompare != 0) {
                    return meanCompare;
                }
                int countCompare = Integer.compare(b.sampleCount, a.sampleCount);
                if (countCompare != 0) {
                    return countCompare;
                }
                return a.nameEn.compareToIgnoreCase(b.nameEn);
            }
        });
    }

    private static int sampleTotal(Map<String, Sample> samples) {
        int total = 0;
        for (Sample sample : samples.values()) {
            total += sample.size();
        }
        return total;
    }

    private static int netHpLoss(Map<String, Sample> samples) {
        int total = 0;
        for (Sample sample : samples.values()) {
            total += sample.totalHpLoss();
        }
        return total;
    }

    private static MonsterTierEntry entry(Sample sample,
                                          MonsterTier tier,
                                          int tierRank,
                                          int nonBossRank,
                                          int categorySamples,
                                          int categoryNetHpLoss,
                                          boolean lowSample) {
        double[] values = sample.values();
        double mean = mean(values);
        double std = stddev(values, mean);
        double halfWidth = values.length <= 1 ? 0.0D : 1.96D * std / Math.sqrt(values.length);
        int totalHpLoss = sample.totalHpLoss();
        double sampleShare = categorySamples <= 0
                ? 0.0D : sample.size() * 100.0D / categorySamples;
        double lossShare = categoryNetHpLoss <= 0
                ? Double.NaN : totalHpLoss * 100.0D / categoryNetHpLoss;
        return new MonsterTierEntry(
                sample.name,
                sample.name,
                MonsterNames.displayName(sample.name),
                sample.type(),
                values.length,
                sampleShare,
                totalHpLoss,
                lossShare,
                mean,
                percentile(values, 0.50D),
                percentile(values, 0.90D),
                mean - halfWidth,
                mean + halfWidth,
                tier,
                tierRank,
                nonBossRank,
                lowSample);
    }

    private static double mean(double[] values) {
        if (values.length == 0) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    private static double stddev(double[] values, double mean) {
        if (values.length <= 1) {
            return 0.0D;
        }
        double sum = 0.0D;
        for (double value : values) {
            double diff = value - mean;
            sum += diff * diff;
        }
        return Math.sqrt(sum / (values.length - 1));
    }

    private static double percentile(double[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0.0D;
        }
        double position = (sorted.length - 1) * fraction;
        int lower = (int) Math.floor(position);
        int upper = (int) Math.ceil(position);
        if (lower == upper) {
            return sorted[lower];
        }
        double weight = position - lower;
        return sorted[lower] * (1.0D - weight) + sorted[upper] * weight;
    }

    private static YearMonth parseDate(String value) {
        String[] parts = value.split("-");
        if (parts.length != 2) {
            throw new IllegalStateException("invalid statistics date: " + value);
        }
        try {
            return new YearMonth(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("invalid statistics date: " + value, e);
        }
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

    private static final class Sample {
        private final String name;
        private final List<Integer> raw = new ArrayList<Integer>();
        private final Set<String> nodeTypes = new HashSet<String>();

        private Sample(String name) {
            this.name = name;
        }

        private void add(int actualHpLoss, String nodeType) {
            raw.add(actualHpLoss);
            nodeTypes.add(nodeType);
        }

        private int size() {
            return raw.size();
        }

        private double mean() {
            return MonsterTierBuilder.mean(values());
        }

        private int totalHpLoss() {
            int total = 0;
            for (Integer value : raw) {
                total += value;
            }
            return total;
        }

        private String type() {
            if (nodeTypes.size() > 1) {
                return "mixed";
            }
            return nodeTypes.isEmpty() ? "normal" : nodeTypes.iterator().next();
        }

        private double[] values() {
            double[] result = new double[raw.size()];
            for (int i = 0; i < raw.size(); i++) {
                result[i] = raw.get(i);
            }
            java.util.Arrays.sort(result);
            return result;
        }
    }
}
