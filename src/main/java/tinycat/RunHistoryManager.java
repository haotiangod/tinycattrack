package tinycat;

import tinycat.stats.Run;
import com.badlogic.gdx.Gdx;
import com.megacrit.cardcrawl.core.CardCrawlGame;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 运行历史管理器，负责加载和管理所有对局数据。
 * 
 * 数据来源：RunHistoryPlus 模组生成的 runs/ 目录下的 .run JSON 文件。
 * 目录结构：runs/{角色名}/ 或 runs/{存档位}_{角色名}/
 * 
 * 对局数据按角色分为五个列表：
 * - allRuns: 全部对局
 * - ironcladRuns / silentRuns / defectRuns / watcherRuns: 按角色分
 * 
 * 支持按日期范围和是否包含放弃对局进行筛选。
 */
public class RunHistoryManager {
    private final List<Run> allRuns = new ArrayList<>();
    private final List<Run> ironcladRuns = new ArrayList<>();
    private final List<Run> silentRuns = new ArrayList<>();
    private final List<Run> defectRuns = new ArrayList<>();
    private final List<Run> watcherRuns = new ArrayList<>();

    /**
     * 从磁盘加载对局数据。
     * 
     * 加载逻辑：
     * 1. 扫描 runs/ 目录下的子目录（排除 gold_track）
     * 2. 对于默认存档位（slot=0），排除带 "0_"/"1_"/"2_" 前缀的目录
     * 3. 对于非默认存档位，只加载匹配前缀的目录
     * 4. 每个子目录下的 .run 文件通过 Run.fromFile() 解析
     * 5. 过滤无效对局（非A20、自定义种子、每日挑战等）
     * 
     * @return 有效对局数据流
     */
    private Stream<Run> loadData() {
        String prefix = CardCrawlGame.saveSlot + "_";
        boolean isDefaultSlot = CardCrawlGame.saveSlot == 0;

        return Arrays.stream(Gdx.files.local("runs" + File.separator).list())
                .filter(s -> s.isDirectory() && !s.name().equals("gold_track"))
                .filter(s -> {
                    if (isDefaultSlot) {
                        String name = s.name();
                        return !name.contains("0_") && !name.contains("1_") && !name.contains("2_");
                    } else {
                        return s.name().contains(prefix);
                    }
                })
                .flatMap(s -> Arrays.stream(s.list()))
                .map(Run::fromFile)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .filter(Run::valid)
                .sorted();
    }

    /**
     * 获取所有对局中出现过的年月列表（去重、排序）。
     * 用于统计面板的日期范围下拉菜单。
     */
    public Stream<YearMonth> getDates() {
        return allRuns.stream()
                .map(run -> run.timestamp)
                .map(ts -> new Date(ts * 1000))
                .map(YearMonth::fromDate)
                .distinct()
                .sorted();
    }

    /**
     * 按日期范围和是否包含放弃对局进行筛选。
     */
    private static List<Run> getRunsInRange(List<Run> runs, YearMonth start, YearMonth end, boolean includeAbandons) {
        return runs.stream().filter(run -> run.isInRange(start, end)).filter(run -> {
            if (includeAbandons) {
                return true;
            }
            return !run.abandoned;
        }).collect(Collectors.toList());
    }

    /**
     * 获取增量对局（用于之前的上传功能，现在主要用于本地统计）。
     * @param lastRunTime 上次同步时间戳，null 表示全量
     * @param outdated 需要重新计算的过期时间戳列表
     */
    public List<Run> getIncrementalRuns(Long lastRunTime, List<Long> outdated) {
        if (lastRunTime == null) {
            List<YearMonth> dates = getDates().collect(Collectors.toList());
            YearMonth firstRun = dates.get(0);
            YearMonth lastRun = dates.get(dates.size() - 1);
            return getAllRuns(firstRun, lastRun, true);
        }

        return allRuns.stream().filter(run -> {
            if (run.timestamp > lastRunTime) {
                return true;
            }
            return outdated.contains(run.timestamp);
        }).collect(Collectors.toList());
    }

    public List<Run> getAllRuns(YearMonth start, YearMonth end, boolean includeAbandons) {
        return getRunsInRange(allRuns, start, end, includeAbandons);
    }

    public List<Run> getIroncladRuns(YearMonth start, YearMonth end, boolean includeAbandons) {
        return getRunsInRange(ironcladRuns, start, end, includeAbandons);
    }

    public List<Run> getSilentRuns(YearMonth start, YearMonth end, boolean includeAbandons) {
        return getRunsInRange(silentRuns, start, end, includeAbandons);
    }

    public List<Run> getDefectRuns(YearMonth start, YearMonth end, boolean includeAbandons) {
        return getRunsInRange(defectRuns, start, end, includeAbandons);
    }

    public List<Run> getWatcherRuns(YearMonth start, YearMonth end, boolean includeAbandons) {
        return getRunsInRange(watcherRuns, start, end, includeAbandons);
    }


    /**
     * 重新加载全部对局数据（清空缓存后重新扫描磁盘）。
     */
    public void refreshData() {
        // 不要在批量加载历史 run 时使用“当前这一局”的第0层HP。
        // capturedFloor0HP 是静态字段，若不隔离，会把当前局HP套到所有历史局上。
        Integer captured = tinycat.capturedFloor0HP;
        tinycat.capturedFloor0HP = null;
        try {
            this.allRuns.clear();
            this.ironcladRuns.clear();
            this.silentRuns.clear();
            this.defectRuns.clear();
            this.watcherRuns.clear();

            loadData().forEach((run) -> {
                switch (run.playerClass) {
                    case IRONCLAD:
                        this.allRuns.add(run);
                        this.ironcladRuns.add(run);
                        break;
                    case THE_SILENT:
                        this.allRuns.add(run);
                        this.silentRuns.add(run);
                        break;
                    case DEFECT:
                        this.allRuns.add(run);
                        this.defectRuns.add(run);
                        break;
                    case WATCHER:
                        this.allRuns.add(run);
                        this.watcherRuns.add(run);
                        break;
                }
            });
        } finally {
            tinycat.capturedFloor0HP = captured;
        }

        tinycat.logger.info("Loaded " + this.allRuns.size() + " runs.");
        tinycat.logger.info("Loaded " + this.ironcladRuns.size() + " ironclad runs.");
        tinycat.logger.info("Loaded " + this.silentRuns.size() + " silent runs.");
        tinycat.logger.info("Loaded " + this.defectRuns.size() + " defect runs.");
        tinycat.logger.info("Loaded " + this.watcherRuns.size() + " watcher runs.");
    }
}
