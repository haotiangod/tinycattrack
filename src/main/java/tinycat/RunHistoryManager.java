package tinycat;

import tinycat.stats.Run;
import tinycat.stats.SeedFilter;
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
 * 支持按日期范围、是否包含放弃对局以及种子局模式进行筛选。
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
     * 5. 过滤无效对局（非A20、每日挑战、特殊局等）；种子局保留，由查询筛选
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
                .filter(f -> f.name().endsWith(".run"))
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
     * 按日期范围、放弃局和种子局模式进行筛选。
     */
    private static List<Run> getRunsInRange(List<Run> runs,
                                            YearMonth start,
                                            YearMonth end,
                                            boolean includeAbandons,
                                            SeedFilter seedFilter) {
        SeedFilter mode = seedFilter == null ? SeedFilter.ALL : seedFilter;
        return runs.stream()
                .filter(run -> run.isInRange(start, end))
                .filter(mode::accepts)
                .filter(run -> includeAbandons || !run.abandoned)
                .collect(Collectors.toList());
    }

    public List<Run> getAllRuns(YearMonth start, YearMonth end, boolean includeAbandons, SeedFilter seedFilter) {
        return getRunsInRange(allRuns, start, end, includeAbandons, seedFilter);
    }

    public List<Run> getIroncladRuns(YearMonth start, YearMonth end, boolean includeAbandons, SeedFilter seedFilter) {
        return getRunsInRange(ironcladRuns, start, end, includeAbandons, seedFilter);
    }

    public List<Run> getSilentRuns(YearMonth start, YearMonth end, boolean includeAbandons, SeedFilter seedFilter) {
        return getRunsInRange(silentRuns, start, end, includeAbandons, seedFilter);
    }

    public List<Run> getDefectRuns(YearMonth start, YearMonth end, boolean includeAbandons, SeedFilter seedFilter) {
        return getRunsInRange(defectRuns, start, end, includeAbandons, seedFilter);
    }

    public List<Run> getWatcherRuns(YearMonth start, YearMonth end, boolean includeAbandons, SeedFilter seedFilter) {
        return getRunsInRange(watcherRuns, start, end, includeAbandons, seedFilter);
    }


    /**
     * 重新加载全部对局数据（清空缓存后重新扫描磁盘）。
     */
    public void refreshData() {
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

        tinycat.logger.info("Loaded " + this.allRuns.size() + " runs.");
        tinycat.logger.info("Loaded " + this.ironcladRuns.size() + " ironclad runs.");
        tinycat.logger.info("Loaded " + this.silentRuns.size() + " silent runs.");
        tinycat.logger.info("Loaded " + this.defectRuns.size() + " defect runs.");
        tinycat.logger.info("Loaded " + this.watcherRuns.size() + " watcher runs.");
    }
}
