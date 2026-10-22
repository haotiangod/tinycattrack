package tinycat.ui;

import tinycat.Localization;
import tinycat.LocalizationKeys;
import tinycat.tinycat;
import tinycat.YearMonth;
import tinycat.stats.ClassStat;
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
    public DropdownMenu sampleSizeDropdown;
    public ArrayList<YearMonth> startDates;
    public ArrayList<YearMonth> endDates;
    public ClassStat[] classStats;
    
    // 使用懒加载，在第一次使用时才获取本地化文本
    private String[] classes;
    private String[] statTypes;
    
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
            };
        }
        return statTypes;
    }

    public String currentClass() {
        return getClasses()[classDropdown.getSelectedIndex()];
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
                currentClass(),
                abandonDropdown.getSelectedIndex() == 0);
    }

    public void update() {
        startDateDropdown.update();
        endDateDropDown.update();
        classDropdown.update();
        statTypeDropdown.update();
        abandonDropdown.update();
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

        sampleSizeDropdown =
                new DropdownMenu(this,
                        new String[]{"0", "1", "2", "3", "4", "5", "6", "7", "8", "9", "10"},
                        FontHelper.tipBodyFont,
                        Settings.LIGHT_YELLOW_COLOR);

        setClassStats(startDates.get(startDateDropdown.getSelectedIndex()),
                endDates.get(endDateDropDown.getSelectedIndex()),
                getClasses()[classDropdown.getSelectedIndex()], abandonDropdown.getSelectedIndex() == 0);
    }

    public boolean isDropDownOpen() {
        return startDateDropdown.isOpen || endDateDropDown.isOpen || classDropdown.isOpen;
    }

    private static ClassStat[] getClassStats(YearMonth startDate,
                                             YearMonth endDate,
                                             String className,
                                             boolean includeAbandons) {
        ClassStat
                ironcladCS =
                new ClassStat(tinycat.runHistoryManager.getIroncladRuns(startDate, endDate, includeAbandons),
                        false);
        ClassStat
                silentCS =
                new ClassStat(tinycat.runHistoryManager.getSilentRuns(startDate, endDate, includeAbandons), false);
        ClassStat
                defectCS =
                new ClassStat(tinycat.runHistoryManager.getDefectRuns(startDate, endDate, includeAbandons), false);
        ClassStat
                watcherCS =
                new ClassStat(tinycat.runHistoryManager.getWatcherRuns(startDate, endDate, includeAbandons),
                        false);

        ClassStat allCS;
        switch (className) {
            case "Ironclad":
                allCS = ironcladCS;
                break;
            case "Silent":
                allCS = silentCS;
                break;
            case "Defect":
                allCS = defectCS;
                break;
            case "Watcher":
                allCS = watcherCS;
                break;
            default:
                allCS =
                        new ClassStat(tinycat.runHistoryManager.getAllRuns(startDate, endDate, includeAbandons),
                                true);
        }
        return new ClassStat[]{ironcladCS, silentCS, defectCS, watcherCS, allCS};
    }

    private void setClassStats(YearMonth startDate, YearMonth endDate, String className, boolean includeAbandons) {
        classStats = getClassStats(startDate, endDate, className, includeAbandons);
    }
}
