package tinycat.tracker;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.metrics.MetricData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 测试模组的局内状态记录器，只做校验，不参与统计界面。
 *
 * 每次都写三份：
 *   runs/{runDir}/{start}.tinycat        —— 主 Mod 读取的机器可读状态（格式不变）
 *   runs_test/{runDir}/{start}.tinycat   —— 同一份内容的备份，防止 runs/ 被清理
 *   runs_test/{runDir}/{start}.gold.txt  —— 人读的金币校验表：每层金币、增减、大额跳变，
 *                                          以及「实际 goldGained」与「gold_per_floor 复算」的差额
 *
 * 大额金币层（|变化| >= 100）单独列出：金钱雨/事件/贪婪这类层是金币估算的主要误差来源，
 * 用同一个种子重打时优先核对它们。
 */
public final class RunStateTracker {
    private static final String VERSION = "2";
    /** 主 Mod 读取的目录，格式不能改。 */
    private static final String TINYCAT_DIR = "runs";
    /** 校验输出目录，位于游戏根目录。 */
    private static final String VERIFY_DIR = "runs_test";
    private static final String MOD_VERSION = "0.2.0";
    private static final long MATCH_TOLERANCE_SECONDS = 60L * 60L;
    /** 单层金币变化超过这个值就单独标记。 */
    private static final int BIG_DELTA = 100;

    private final Map<String, String> state = new LinkedHashMap<String, String>();
    private final List<String> rows = new ArrayList<String>();
    private final List<String> bigRows = new ArrayList<String>();

    private boolean active;
    private FileHandle file;
    private FileHandle verifyFile;
    private FileHandle verifyStateCopy;
    private String runDir;
    private long startTs;
    private int lastFloor = -1;
    private int lastGoldGained = -1;
    private int lastGold = -1;
    private int startGold = -1;
    private int floor0Hp = -1;
    private int maxHp = -1;
    private int spentTotal;
    private int maxGain;
    private int maxGainFloor = -1;
    private int maxLoss;
    private int maxLossFloor = -1;

    public void update() {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || AbstractDungeon.floorNum <= 0) {
            finish();
            return;
        }

        int floor = AbstractDungeon.floorNum;
        int goldGained = CardCrawlGame.goldGained;
        int gold = player.gold;
        if (!active && !attach(player, floor)) {
            return;
        }
        if (floor == lastFloor && goldGained == lastGoldGained && gold == lastGold) {
            return;
        }

        int delta = lastGold < 0 ? 0 : gold - lastGold;
        if (delta < 0) {
            spentTotal += -delta;
            if (-delta > maxLoss) {
                maxLoss = -delta;
                maxLossFloor = floor;
            }
            if (-delta >= BIG_DELTA) {
                bigRows.add(row(floor, gold, delta, goldGained, player.currentHealth, "大额支出"));
            }
        } else if (delta > 0) {
            if (delta > maxGain) {
                maxGain = delta;
                maxGainFloor = floor;
            }
            if (delta >= BIG_DELTA) {
                bigRows.add(row(floor, gold, delta, goldGained, player.currentHealth, "大额进账"));
            }
        }
        if (floor != lastFloor || delta != 0) {
            rows.add(row(floor, gold, delta, goldGained, player.currentHealth, ""));
        }

        state.put("floor", String.valueOf(floor));
        state.put("gold_gained", String.valueOf(goldGained));
        state.put("gold", String.valueOf(gold));
        state.put("hp", String.valueOf(player.currentHealth));
        state.put("max_hp", String.valueOf(player.maxHealth));
        state.put("updated_ts", String.valueOf(nowSeconds()));
        write();
        writeVerify(false);

        lastFloor = floor;
        lastGoldGained = goldGained;
        lastGold = gold;
    }

    private boolean attach(AbstractPlayer player, int floor) {
        runDir = getRunDirName(player);
        long now = nowSeconds();
        long expectedStart = now - Math.max(0L, (long) CardCrawlGame.playtime);
        FileHandle directory = Gdx.files.local(TINYCAT_DIR + "/" + runDir + "/");

        FileHandle existing = findNearest(directory, expectedStart);
        if (existing != null) {
            load(existing);
            active = true;
            resetCounters();
            TinyCatTrackerTest.logger.info("RunStateTracker reattached: " + existing.path());
            return true;
        }

        if (!directory.exists()) {
            directory.mkdirs();
        }
        boolean freshFloorOne = floor == 1 && CardCrawlGame.playtime < 300.0F;
        startTs = expectedStart;
        file = Gdx.files.local(TINYCAT_DIR + "/" + runDir + "/" + startTs + ".tinycat");
        verifyFile = Gdx.files.local(VERIFY_DIR + "/" + runDir + "/" + startTs + ".gold.txt");
        verifyStateCopy = Gdx.files.local(VERIFY_DIR + "/" + runDir + "/" + startTs + ".tinycat");
        state.clear();
        state.put("version", VERSION);
        state.put("start_ts", String.valueOf(startTs));
        floor0Hp = freshFloorOne ? player.currentHealth : -1;
        startGold = freshFloorOne ? player.gold : -1;
        maxHp = player.maxHealth;
        state.put("floor0_hp", String.valueOf(floor0Hp));
        state.put("start_gold", String.valueOf(startGold));
        active = true;
        resetCounters();
        TinyCatTrackerTest.logger.info("RunStateTracker started: " + file.path()
                + " floor0_hp=" + floor0Hp
                + " start_gold=" + startGold
                + " max_hp=" + maxHp
                + " start_ts=" + startTs);
        return true;
    }

    private void resetCounters() {
        lastFloor = -1;
        lastGoldGained = -1;
        lastGold = -1;
        rows.clear();
        bigRows.clear();
        spentTotal = 0;
        maxGain = 0;
        maxGainFloor = -1;
        maxLoss = 0;
        maxLossFloor = -1;
    }

    private FileHandle findNearest(FileHandle directory, long expectedStart) {
        if (!directory.exists() || !directory.isDirectory()) {
            return null;
        }
        FileHandle best = null;
        long bestDiff = MATCH_TOLERANCE_SECONDS + 1;
        for (FileHandle candidate : directory.list()) {
            if (!candidate.name().endsWith(".tinycat")) {
                continue;
            }
            long start = readStartTs(candidate);
            if (start < 0) {
                continue;
            }
            long diff = Math.abs(start - expectedStart);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = candidate;
            }
        }
        return best;
    }

    private long readStartTs(FileHandle candidate) {
        try {
            for (String line : candidate.readString().split("\\r?\\n")) {
                if (line.startsWith("start_ts=")) {
                    return Long.parseLong(line.substring("start_ts=".length()).trim());
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    private void load(FileHandle source) {
        file = source;
        state.clear();
        try {
            for (String line : source.readString().split("\\r?\\n")) {
                int eq = line.indexOf('=');
                if (eq > 0) {
                    state.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
        } catch (Exception e) {
            TinyCatTrackerTest.logger.warn("RunStateTracker failed to read " + source.path(), e);
        }
        state.put("version", VERSION);
        startTs = parseLong(state.get("start_ts"), -1L);
        floor0Hp = parseInt(state.get("floor0_hp"), -1);
        startGold = parseInt(state.get("start_gold"), -1);
        maxHp = parseInt(state.get("max_hp"), -1);
        verifyFile = Gdx.files.local(VERIFY_DIR + "/" + runDir + "/" + startTs + ".gold.txt");
        verifyStateCopy = Gdx.files.local(VERIFY_DIR + "/" + runDir + "/" + startTs + ".tinycat");
        if (rows.isEmpty()) {
            rows.add("(续玩：之前的楼层明细未记录，只保留第 1 层 HP 与起始金币)");
        }
    }

    private String row(int floor, int gold, int delta, int goldGained, int hp, String note) {
        String sign = delta > 0 ? "+" : "";
        String text = String.format(Locale.US, "%-5d %-4s %-7d %-6s %-7d %d/%d",
                floor, nodeAt(floor), gold, sign + delta, goldGained, hp, maxHp);
        if (note != null && !note.isEmpty()) {
            text = text + "  " + note;
        }
        return text;
    }

    private String nodeAt(int floor) {
        MetricData data = CardCrawlGame.metricData;
        if (data == null || data.path_per_floor == null
                || floor <= 0 || floor > data.path_per_floor.size()) {
            return "?";
        }
        String node = data.path_per_floor.get(floor - 1);
        return node == null || node.isEmpty() ? "?" : node;
    }

    private void write() {
        writeState(file);
        writeState(verifyStateCopy);
        TinyCatTrackerTest.logger.info("RunStateTracker wrote " + (file == null ? "-" : file.path())
                + " floor=" + state.get("floor")
                + " gold=" + state.get("gold")
                + " gold_gained=" + state.get("gold_gained")
                + " hp=" + state.get("hp")
                + " floor0_hp=" + state.get("floor0_hp")
                + " start_gold=" + state.get("start_gold"));
    }

    private void writeState(FileHandle target) {
        if (target == null) {
            return;
        }
        try {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> entry : state.entrySet()) {
                text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            target.writeString(text.toString(), false);
        } catch (Exception e) {
            TinyCatTrackerTest.logger.warn("RunStateTracker failed to write " + target.path(), e);
        }
    }

    private void writeVerify(boolean finished) {
        if (verifyFile == null) {
            return;
        }
        try {
            StringBuilder text = new StringBuilder(8192);
            text.append("TinyCat 金币校验 / gold verification\n");
            text.append("==================================\n\n");
            text.append("mod         : TinyCatTrackerTest ").append(MOD_VERSION).append('\n');
            text.append("character   : ").append(runDir).append('\n');
            text.append("ascension   : ").append(AbstractDungeon.ascensionLevel).append('\n');
            text.append("seed        : ").append(seedText()).append('\n');
            text.append("start_ts    : ").append(startTs)
                    .append("  (").append(timeText(startTs)).append(")\n");
            text.append("start_gold  : ")
                    .append(startGold < 0 ? "未记录（非第 1 层开局）" : String.valueOf(startGold)).append('\n');
            text.append("floor0_hp   : ")
                    .append(floor0Hp < 0 ? "未记录" : String.valueOf(floor0Hp))
                    .append("   max_hp ").append(maxHp).append('\n');
            text.append("floor       : ").append(state.get("floor"))
                    .append("   hp ").append(state.get("hp"))
                    .append("   gold ").append(state.get("gold"))
                    .append("   gold_gained ").append(state.get("gold_gained")).append('\n');
            text.append("updated     : ").append(timeText(nowSeconds()))
                    .append(finished ? "   [局已结束]" : "").append("\n\n");

            text.append("每层金币明细（node: M普通/$商店/E精英/B Boss/?事件/R篝火/T宝箱）\n");
            text.append("----------------------------------------------------------------\n");
            text.append("floor node gold    delta  gained  hp/max\n");
            text.append("----- ---- ------- ------ ------- -------\n");
            if (rows.isEmpty()) {
                text.append("(暂无)\n");
            } else {
                for (String line : rows) {
                    text.append(line).append('\n');
                }
            }
            text.append('\n');

            text.append("校验 (gold_per_floor vs 实际 goldGained)\n");
            text.append("----------------------------------------------------------------\n");
            int actual = CardCrawlGame.goldGained;
            int[] recomputed = recomputeGold();
            text.append("实际累计获得 (CardCrawlGame.goldGained) : ").append(actual).append('\n');
            if (recomputed == null) {
                text.append("按 gold_per_floor 复算              : 数据不足（gold_per_floor 为空）\n");
            } else {
                text.append("首层 gold_per_floor                 : ").append(recomputed[1]).append('\n');
                text.append("按 gold_per_floor 复算              : ").append(recomputed[0])
                        .append("   (首层 - 起始金币 + Σ正差)\n");
                int diff = recomputed[0] - actual;
                text.append("差额 (复算 - 实际)                  : ").append(diff)
                        .append(diff == 0 ? "   OK 口径一致" : "   !! 不一致，按该种子重打核对").append('\n');
                int vf = vfEstimate();
                text.append("主 Mod vF 估算 (首层 - 常数 + Σ正差) : ").append(vf)
                        .append("   常数 ")
                        .append(neowDeduction())
                        .append("（neow_bonus=")
                        .append(dataNeowBonus())
                        .append("）\n");
                text.append("差额 (vF 估算 - 实际)                : ").append(vf - actual)
                        .append("   ← 游戏内统计页显示的就是这个误差").append('\n');
            }
            text.append("商店/事件支出合计                   : ").append(spentTotal).append('\n');
            text.append("单层最大进账                        : ").append(maxGain)
                    .append(maxGainFloor > 0 ? " (第 " + maxGainFloor + " 层)" : "").append('\n');
            text.append("单层最大支出                        : ").append(maxLoss)
                    .append(maxLossFloor > 0 ? " (第 " + maxLossFloor + " 层)" : "").append('\n');
            text.append('\n');

            text.append("大额金币层（|变化| >= ").append(BIG_DELTA).append("，重打该种子时优先核对）\n");
            text.append("----------------------------------------------------------------\n");
            if (bigRows.isEmpty()) {
                text.append("(无)\n");
            } else {
                for (String line : bigRows) {
                    text.append(line).append('\n');
                }
            }
            text.append("\n说明：start_gold 只在第 1 层开局 300 秒内记录；续玩时为 -1，")
                    .append("复算会用 99 + Neow 金币加成推算。\n");

            verifyFile.writeString(text.toString(), false);
        } catch (Exception e) {
            TinyCatTrackerTest.logger.warn("RunStateTracker failed to write " + verifyFile.path(), e);
        }
    }

    /** 主 Mod（没有 .tinycat 时）用的金币估算：首层 gpf - 常数 + Σ正差。 */
    private int vfEstimate() {
        MetricData data = CardCrawlGame.metricData;
        if (data == null || data.gold_per_floor == null || data.gold_per_floor.isEmpty()) {
            return 0;
        }
        int total = Math.max(0, data.gold_per_floor.get(0) - neowDeduction());
        for (int i = 1; i < data.gold_per_floor.size(); i++) {
            int diff = data.gold_per_floor.get(i) - data.gold_per_floor.get(i - 1);
            if (diff > 0) {
                total += diff;
            }
        }
        return total;
    }

    /** 与 Run.getNeowBonusDeduction 保持一致的扣除常数。 */
    private int neowDeduction() {
        MetricData data = CardCrawlGame.metricData;
        if (data != null && "NO_GOLD".equals(data.neowCost)) {
            return 0;
        }
        String bonus = data == null ? null : data.neowBonus;
        if (bonus == null) {
            return 45;
        }
        if ("TWO_FIFTY_GOLD".equals(bonus)) {
            return 55;
        }
        if ("HUNDRED_GOLD".equals(bonus)) {
            return 40;
        }
        if ("RANDOM_COMMON_RELIC".equals(bonus)) {
            return 35;
        }
        if ("REMOVE_CARD".equals(bonus) || "REMOVE_TWO".equals(bonus)) {
            return 40;
        }
        if ("ONE_RARE_RELIC".equals(bonus) || "THREE_SMALL_POTIONS".equals(bonus)
                || "THREE_RARE_CARDS".equals(bonus)) {
            return 45;
        }
        if ("TRANSFORM_TWO_CARDS".equals(bonus)) {
            return 55;
        }
        if ("TRANSFORM_CARD".equals(bonus) || "UPGRADE_CARD".equals(bonus)
                || "RANDOM_COLORLESS_2".equals(bonus)) {
            return 60;
        }
        if ("THREE_ENEMY_KILL".equals(bonus)) {
            return 40;
        }
        return 45;
    }

    private String dataNeowBonus() {
        MetricData data = CardCrawlGame.metricData;
        if (data == null || data.neowBonus == null) {
            return "-";
        }
        return data.neowBonus + (data.neowCost == null ? "" : "/" + data.neowCost);
    }

    /** 返回 {复算值, 首层gold_per_floor, 推算起始金币}；数据不足返回 null。 */
    private int[] recomputeGold() {
        MetricData data = CardCrawlGame.metricData;
        if (data == null || data.gold_per_floor == null || data.gold_per_floor.isEmpty()) {
            return null;
        }
        int first = data.gold_per_floor.get(0);
        int start = startGold;
        if (start < 0) {
            // 续玩时没有记录起始金币：用 99 + Neow 金币加成近似，只影响展示
            start = 99 + neowGoldBonus();
        }
        int total = Math.max(0, first - start);
        for (int i = 1; i < data.gold_per_floor.size(); i++) {
            int diff = data.gold_per_floor.get(i) - data.gold_per_floor.get(i - 1);
            if (diff > 0) {
                total += diff;
            }
        }
        return new int[]{total, first, start};
    }

    private int neowGoldBonus() {
        MetricData data = CardCrawlGame.metricData;
        String bonus = data == null ? null : data.neowBonus;
        if ("TWO_FIFTY_GOLD".equals(bonus)) {
            return 250;
        }
        if ("HUNDRED_GOLD".equals(bonus)) {
            return 100;
        }
        return 0;
    }

    private String seedText() {
        if (!Settings.seedSet || Settings.seed == null) {
            return "未固定（随机种子）";
        }
        return String.valueOf(Settings.seed)
                + (Settings.specialSeed == null ? "" : " (special " + Settings.specialSeed + ")");
    }

    private void finish() {
        if (!active) {
            return;
        }
        writeVerify(true);
        int actual = CardCrawlGame.goldGained;
        int[] recomputed = recomputeGold();
        TinyCatTrackerTest.logger.info("RunStateTracker VERIFY "
                + runDir
                + " start_ts=" + startTs
                + " floor0_hp=" + floor0Hp
                + " start_gold=" + startGold
                + " gold_gained=" + actual
                + " gpf_recomputed=" + (recomputed == null ? "n/a" : String.valueOf(recomputed[0]))
                + " diff=" + (recomputed == null ? "n/a" : String.valueOf(recomputed[0] - actual))
                + " spent=" + spentTotal
                + " max_gain=" + maxGain + "@" + maxGainFloor
                + " file=" + (verifyFile == null ? "-" : verifyFile.path()));
        reset();
    }

    private void reset() {
        active = false;
        file = null;
        verifyFile = null;
        verifyStateCopy = null;
        runDir = null;
        startTs = 0L;
        state.clear();
        resetCounters();
        startGold = -1;
        floor0Hp = -1;
        maxHp = -1;
    }

    private static String getRunDirName(AbstractPlayer player) {
        int slot = CardCrawlGame.saveSlot;
        String charName = player.chosenClass.name();
        return slot == 0 ? charName : slot + "_" + charName;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static String timeText(long seconds) {
        if (seconds <= 0L) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(seconds * 1000L));
    }

    private static int parseInt(String value, int fallback) {
        try {
            return value == null ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long parseLong(String value, long fallback) {
        try {
            return value == null ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
