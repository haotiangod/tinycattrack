package tinycat.tracker;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测试模组使用的局内状态记录器。
 *
 * 写入 runs/{runDir}/{start}.tinycat，记录第 1 层 HP 与累计获得金币。
 * TinyCat 正式版只读取该文件，不会主动创建。
 */
public final class RunStateTracker {
    private static final String VERSION = "1";
    private static final long MATCH_TOLERANCE_SECONDS = 60L * 60L;

    private final Map<String, String> state = new LinkedHashMap<>();
    private boolean active;
    private FileHandle file;
    private String runDir;
    private int lastFloor = -1;
    private int lastGoldGained = -1;

    public void update() {
        AbstractPlayer player = AbstractDungeon.player;
        if (player == null || AbstractDungeon.floorNum <= 0) {
            reset();
            return;
        }

        int floor = AbstractDungeon.floorNum;
        if (!active && !attach(player, floor)) {
            return;
        }

        int goldGained = CardCrawlGame.goldGained;
        if (floor == lastFloor && goldGained == lastGoldGained) {
            return;
        }

        state.put("floor", String.valueOf(floor));
        state.put("gold_gained", String.valueOf(goldGained));
        state.put("gold", String.valueOf(player.gold));
        state.put("hp", String.valueOf(player.currentHealth));
        state.put("updated_ts", String.valueOf(nowSeconds()));
        write();
        lastFloor = floor;
        lastGoldGained = goldGained;
    }

    private boolean attach(AbstractPlayer player, int floor) {
        runDir = getRunDirName(player);
        long now = nowSeconds();
        long expectedStart = now - Math.max(0L, (long) CardCrawlGame.playtime);
        FileHandle directory = Gdx.files.local("runs/" + runDir + "/");

        FileHandle existing = findNearest(directory, expectedStart);
        if (existing != null) {
            load(existing);
            active = true;
            lastFloor = -1;
            lastGoldGained = -1;
            TinyCatTrackerTest.logger.info("RunStateTracker reattached: " + existing.path());
            return true;
        }

        if (!directory.exists()) {
            directory.mkdirs();
        }
        boolean freshFloorOne = floor == 1 && CardCrawlGame.playtime < 300.0F;
        file = Gdx.files.local("runs/" + runDir + "/" + expectedStart + ".tinycat");
        state.clear();
        state.put("version", VERSION);
        state.put("start_ts", String.valueOf(expectedStart));
        state.put("floor0_hp", String.valueOf(freshFloorOne ? player.currentHealth : -1));
        state.put("start_gold", String.valueOf(freshFloorOne ? player.gold : -1));
        active = true;
        lastFloor = -1;
        lastGoldGained = -1;
        TinyCatTrackerTest.logger.info("RunStateTracker started: " + file.path());
        return true;
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
    }

    private void write() {
        try {
            StringBuilder text = new StringBuilder();
            for (Map.Entry<String, String> entry : state.entrySet()) {
                text.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
            }
            file.writeString(text.toString(), false);
        } catch (Exception e) {
            TinyCatTrackerTest.logger.warn("RunStateTracker failed to write " + file.path(), e);
        }
    }

    private void reset() {
        active = false;
        file = null;
        runDir = null;
        state.clear();
        lastFloor = -1;
        lastGoldGained = -1;
    }

    private static String getRunDirName(AbstractPlayer player) {
        int slot = CardCrawlGame.saveSlot;
        String charName = player.chosenClass.name();
        return slot == 0 ? charName : slot + "_" + charName;
    }

    private static long nowSeconds() {
        return System.currentTimeMillis() / 1000L;
    }
}
