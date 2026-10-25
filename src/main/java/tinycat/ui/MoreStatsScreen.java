package tinycat.ui;

import tinycat.Localization;
import tinycat.LocalizationKeys;
import tinycat.tinycat;
import tinycat.YearMonth;
import tinycat.stats.ClassStat;
import tinycat.stats.SeedFilter;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.screens.options.DropdownMenu;
import com.megacrit.cardcrawl.screens.options.DropdownMenuListener;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.stream.Collectors;

public class MoreStatsScreen implements DropdownMenuListener {
    public DropdownMenu startDateDropdown;
    public DropdownMenu endDateDropDown;
    public DropdownMenu classDropdown;
    public DropdownMenu statTypeDropdown;
    public DropdownMenu abandonDropdown;
    public DropdownMenu seedFilterDropdown;
    public DropdownMenu sampleSizeDropdown;
    public ArrayList<YearMonth> startDates;
    public ArrayList<YearMonth> endDates;
    public ClassStat[] classStats;
    
    // 使用懒加载，在第一次使用时才获取本地化文本
    private String[] classes;
    private String[] statTypes;
    private String[] seedFilters;
    
    public String[] getClasses() {
        if (classes == null) {
            classes = new String[]{
                Localization.get("ui.all"),
                Localization.get("ui.ironclad"),
                Localization.get("ui.silent"),
                Localization.get("ui.defect"),
                Localization.get("ui.watcher")
            };
        }
        return classes;
    }
    
    public String[] getStatTypes() {
        if (statTypes == null) {
            statTypes = new String[]{
                    Localization.get("ui.overall"),
                    Localization.get("ui.card_pick_rate_act_1"),
                    Localization.get("ui.card_pick_rate_after_act_1"),
                    Localization.get("ui.card_win_rate"),
                    Localization.get("ui.neow_bonus"),
                    Localization.get("ui.boss_relic_pick_rate"),
                    Localization.get("ui.boss_relic_win_rate"),
                    Localization.get("ui.encounter_average_hp_loss"),
                    Localization.get("ui.encounter_average_combat_hp_loss"),
                    Localization.get("ui.encounter_split_hp_loss"),
                    Localization.get("ui.encounter_mortality_rate"),
                    Localization.get("ui.encounter_average_potions_used"),
                    Localization.get("ui.relic_win_rate"),
                    Localization.get("ui.event_win_rate_per_act"),
                    Localization.get("ui.campfire_actions"),
                    Localization.get("ui.shop_actions"),
                    Localization.get(LocalizationKeys.UI_CAMPFIRE_UPGRADE_PREF),
            };
        }
        return statTypes;
    }

    public String[] getSeedFilters() {
        if (seedFilters == null) {
            seedFilters = new String[]{
                    Localization.get(LocalizationKeys.UI_SEED_FILTER_ALL),
                    Localization.get(LocalizationKeys.UI_SEED_FILTER_EXCLUDE),
                    Localization.get(LocalizationKeys.UI_SEED_FILTER_ONLY)
            };
        }
        return seedFilters;
    }

    public SeedFilter currentSeedFilter() {
        int index = seedFilterDropdown == null ? 0 : seedFilterDropdown.getSelectedIndex();
        SeedFilter[] values = SeedFilter.values();
        if (index < 0 || index >= values.length) {
            return SeedFilter.ALL;
        }
        return values[index];
    }

    public ClassStat getClassStat() {
        int index = classDropdown.getSelectedIndex();
        // 索引映射：0=全部，1=战士，2=猎人，3=机器人，4=观者
        // 数组索引：0=战士，1=猎人，2=机器人，3=观者，4=全部
        if (index == 0) {
            return classStats[4]; // 全部
        } else {
            return classStats[index - 1]; // 具体角色
        }
    }

    @Override
    public void changedSelectionTo(DropdownMenu dropdownMenu, int i, String s) {
        int startDateIndex = startDateDropdown.getSelectedIndex();
        int endDateIndex = endDateDropDown.getSelectedIndex();

        setClassStats(startDates.get(startDateIndex),
                endDates.get(endDateIndex),
                classDropdown.getSelectedIndex(),
                abandonDropdown.getSelectedIndex() == 0,
                currentSeedFilter());
    }

    public void update() {
        startDateDropdown.update();
        endDateDropDown.update();
        classDropdown.update();
        statTypeDropdown.update();
        abandonDropdown.update();
        seedFilterDropdown.update();
        sampleSizeDropdown.update();
    }

    public void refreshData() {
        tinycat.runHistoryManager.refreshData();
        startDates = tinycat.runHistoryManager.getDates().collect(Collectors.toCollection(ArrayList::new));
        if (startDates.isEmpty()) {
            LocalDate today = LocalDate.now();
            startDates.add(new YearMonth(today.getYear(), today.getMonthValue()));
        }
        endDates = new ArrayList<>(startDates);

        endDates.add(0, startDates.get(startDates.size() - 1));

        startDateDropdown =
                new DropdownMenu(this,
                        startDates.stream().map(x -> x.toString()).collect(Collectors.toCollection(ArrayList::new)),
                        FontHelper.tipBodyFont,
                        Settings.LIGHT_YELLOW_COLOR);
        endDateDropDown =
                new DropdownMenu(this,
                        endDates.stream().map(x -> x.toString()).collect(Collectors.toCollection(ArrayList::new)),
                        FontHelper.tipBodyFont,
                        Settings.LIGHT_YELLOW_COLOR);

        classDropdown = new DropdownMenu(this, getClasses(), FontHelper.tipBodyFont, Settings.LIGHT_YELLOW_COLOR);

        statTypeDropdown = new DropdownMenu(null, getStatTypes(), FontHelper.tipBodyFont, Settings.LIGHT_YELLOW_COLOR);

        abandonDropdown =
                new DropdownMenu(this, new String[]{Localization.get(LocalizationKeys.UI_YES), Localization.get(LocalizationKeys.UI_NO)}, FontHelper.tipBodyFont, Settings.LIGHT_YELLOW_COLOR);

        seedFilterDropdown =
                new DropdownMenu(this, getSeedFilters(), FontHelper.tipBodyFont, Settings.LIGHT_YELLOW_COLOR);
        seedFilterDropdown.setSelectedIndex(0);

        sampleSizeDropdown =
                new DropdownMenu(this,
                        new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"},
                        FontHelper.tipBodyFont,
                        Settings.LIGHT_YELLOW_COLOR);

        setClassStats(startDates.get(startDateDropdown.getSelectedIndex()),
                endDates.get(endDateDropDown.getSelectedIndex()),
                classDropdown.getSelectedIndex(), abandonDropdown.getSelectedIndex() == 0,
                currentSeedFilter());
    }

    public boolean isDropDownOpen() {
        return startDateDropdown.isOpen || endDateDropDown.isOpen || classDropdown.isOpen
                || seedFilterDropdown.isOpen;
    }

    private static ClassStat[] getClassStats(YearMonth startDate,
                                             YearMonth endDate,
                                             boolean includeAbandons,
                                             SeedFilter seedFilter) {
        ClassStat
                ironcladCS =
                new ClassStat(tinycat.runHistoryManager.getIroncladRuns(startDate, endDate, includeAbandons,
                        seedFilter), false);
        ClassStat
                silentCS =
                new ClassStat(tinycat.runHistoryManager.getSilentRuns(startDate, endDate, includeAbandons,
                        seedFilter), false);
        ClassStat
                defectCS =
                new ClassStat(tinycat.runHistoryManager.getDefectRuns(startDate, endDate, includeAbandons,
                        seedFilter), false);
        ClassStat
                watcherCS =
                new ClassStat(tinycat.runHistoryManager.getWatcherRuns(startDate, endDate, includeAbandons,
                        seedFilter), false);

        // 注意：classStats[4] 必须始终是"四个角色的真汇总"，不能按当前选中角色做别名，
        // 否则导出的「全角色汇总」行会等于当前角色（例如选机器人时 406 局被当成全角色 406 局）。
        ClassStat allCS =
                new ClassStat(tinycat.runHistoryManager.getAllRuns(startDate, endDate, includeAbandons,
                        seedFilter), true);
        return new ClassStat[]{ironcladCS, silentCS, defectCS, watcherCS, allCS};
    }

    private void setClassStats(YearMonth startDate,
                               YearMonth endDate,
                               int classIndex,
                               boolean includeAbandons,
                               SeedFilter seedFilter) {
        classStats = getClassStats(startDate, endDate, includeAbandons, seedFilter);
    }
}
