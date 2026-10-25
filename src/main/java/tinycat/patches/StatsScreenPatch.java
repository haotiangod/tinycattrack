package tinycat.patches;

import tinycat.Localization;
import tinycat.LocalizationKeys;
import tinycat.NameMapper;
import tinycat.Utils;
import tinycat.stats.*;
import tinycat.tier.MonsterTierExporter;
import tinycat.tier.NeowShort;
import tinycat.tier.TierListExporter;
import tinycat.tier.TierListScope;
import tinycat.tinycat;
import tinycat.ui.CharStatRenderer;
import tinycat.ui.MoreStatsScreen;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.helpers.ImageMaster;
import com.megacrit.cardcrawl.helpers.input.InputHelper;
import com.megacrit.cardcrawl.screens.mainMenu.ScrollBar;
import com.megacrit.cardcrawl.screens.stats.AchievementGrid;
import com.megacrit.cardcrawl.screens.stats.StatsScreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.megacrit.cardcrawl.screens.stats.StatsScreen.NAMES;
import static com.megacrit.cardcrawl.screens.stats.StatsScreen.renderHeader;

/**
 * 统计界面补丁 — 替换游戏原版 StatsScreen 的渲染逻辑。
 * 
 * 通过多个 @SpirePatch 子类拦截 StatsScreen 的各个生命周期方法：
 * - ConstructorPatch：创建 MoreStatsScreen 实例
 * - RefreshDataPatch：替换数据刷新逻辑
 * - RenderPatch：替换渲染逻辑，调用自定义统计界面
 * - UpdatePatch/UpdateScrollingPatch：处理下拉菜单和滚动
 * 
 * 核心渲染方法 renderStatScreen() 根据用户选择的统计类型，
 * 调用对应的 render 方法（renderCardPickRate、renderEncounterStats 等）。
 * 
 * 注意：此类使用大量静态字段和方法，因为它们通过 @SpirePatch 注入到游戏类中。
 */
public class StatsScreenPatch {
    @SpirePatch(clz = StatsScreen.class, method = "updateScrolling")
    public static class UpdateScrollingPatch {
        public static SpireReturn<Void> Prefix(StatsScreen __instance) {
            if (moreStatsScreen.isDropDownOpen()) {
                return SpireReturn.Return();
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch(clz = StatsScreen.class, method = "update")
    public static class UpdatePatch {
        public static void Postfix(StatsScreen __instance) {
            if (moreStatsScreen != null) {
                moreStatsScreen.update();
                updateTierListExportButton(__instance);
            }
        }
    }

    @SpirePatch(clz = StatsScreen.class, method = "refreshData")
    public static class RefreshDataPatch {
        public static void Replace(StatsScreen __instance) {
            StatsScreen.achievements = new AchievementGrid();
            moreStatsScreen.refreshData();
        }
    }

    @SpirePatch(clz = StatsScreen.class, method = SpirePatch.CONSTRUCTOR)
    public static class ConstructorPatch {
        public static void Postfix(StatsScreen __instance) {
            moreStatsScreen = new MoreStatsScreen();
        }
    }

    @SpirePatch(clz = StatsScreen.class, method = "render")
    public static class RenderPatch {
        public static void Replace(StatsScreen __instance, SpriteBatch sb) {
            renderStatScreen(__instance, sb);
            __instance.button.render(sb);
            ScrollBar scrollBar = Utils.getField(__instance, StatsScreen.class, "scrollBar");
            scrollBar.render(sb);
        }
    }

    public static MoreStatsScreen moreStatsScreen;

    /** 导出按钮的点击区域；位置每帧跟随统计屏滚动。 */
    private static Hitbox tierExportHb;
    private static boolean exportingTierList = false;
    private static String tierExportStatus = null;
    private static long tierExportStatusUntil = 0L;
    /** 导出按钮固定在统计区域右上角。 */
    private static final float EXPORT_BUTTON_X_OFFSET = 1320.0F;

    private static float screenPosX(float val) {
        return val * Settings.xScale;
    }

    private static float screenPosY(float val) {
        return val * Settings.yScale;
    }

    public static String getCharColor() {
        if (moreStatsScreen == null || moreStatsScreen.classDropdown == null) {
            return "#y";
        }
        // 按下拉索引判断，不能按 currentClass() 的本地化字符串判断，
        // 否则中文客户端会全部落到 default 的黄色。
        switch (moreStatsScreen.classDropdown.getSelectedIndex()) {
            case 1:
                return "#r";
            case 2:
                return "#g";
            case 3:
                return "#b";
            case 4:
                return "#p";
            default:
                return "#y";
        }
    }

    public static String colorText(String s, String color) {
        if (s == null || s.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(s.length() + 10);
        boolean first = true;
        for (String w : s.split(" ")) {
            if (!first) sb.append(' ');
            sb.append(color).append(w);
            first = false;
        }
        return sb.toString();
    }

    public static String colorForClass(String s) {
        String color = getCharColor();
        return colorText(s, color);
    }


    private static float getScrollY(StatsScreen s) {
        return Utils.getField(s, StatsScreen.class, "scrollY");
    }

    private static void renderCharacterStats(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        renderHeader(sb, NAMES[0], screenX, renderY);
        new CharStatRenderer(moreStatsScreen.getClassStat()).render(sb, screenX, renderY);
        renderY -= 500.0F * Settings.scale;

        for (int i = 0; i < 4; i++) {
            String name = NAMES[i + 2];
            renderHeader(sb, name, screenX, renderY);
            new CharStatRenderer(moreStatsScreen.classStats[i]).render(sb, screenX, renderY);
            renderY -= 500.0F * Settings.scale;
        }
    }

    private static void render2Columns(SpriteBatch sb, float screenX, float renderY, String s1, String s2) {
        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                s1,
                screenX + 75.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);

        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                s2,
                screenX + 675.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);
    }

    public static <T extends SampleSize> void renderT(SpriteBatch sb,
                                                      float screenX,
                                                      float renderY,
                                                      int maxRows,
                                                      List<T> xs,
                                                      Function<T, String> toString) {
        int minSample = moreStatsScreen.sampleSizeDropdown.getSelectedIndex();
        StringBuilder builder = new StringBuilder();
        StringBuilder builder2 = new StringBuilder();

        int len = xs.size();
        if (maxRows > 0) {
            len = Math.min(len, maxRows);
        }
        
        int midPoint = len / 2;
        for (int i = 0; i < len; i++) {
            T x = xs.get(i);
            if (x.getSampleSize() < minSample) continue;
            
            String translated = toString.apply(x);
            // 翻译名称部分（空格前的部分）
            int spaceIndex = translated.indexOf(' ');
            if (spaceIndex > 0) {
                String name = translated.substring(0, spaceIndex);
                String rest = translated.substring(spaceIndex);
                translated = NameMapper.toChinese(name) + rest;
            }
            
            if (i <= midPoint) {
                builder.append(translated).append(" NL ");
            } else {
                builder2.append(translated).append(" NL ");
            }
        }
        render2Columns(sb, screenX, renderY, builder.toString(), builder2.toString());
    }

    public static <A> void renderRates(SpriteBatch sb,
                                       float screenX,
                                       float renderY,
                                       int maxRows,
                                       List<Rate<A>> rates) {
        final String color = getCharColor();
        renderT(sb,
                screenX,
                renderY,
                maxRows,
                rates,
                c -> NameMapper.toChinese(c.what.toString()) + " " + color + c.percent() + "% " + c.winLoss());
    }

    /**
     * 渲染数量统计（不显示胜率百分比）
     */
    public static <A> void renderCounts(SpriteBatch sb,
                                        float screenX,
                                        float renderY,
                                        int maxRows,
                                        List<Rate<A>> rates) {
        final String color = getCharColor();
        renderT(sb,
                screenX,
                renderY,
                maxRows,
                rates,
                c -> NameMapper.toChinese(c.what.toString()) + " " + color + c.total() + " " + c.winLoss());
    }
    
    public static <A> void renderRates1Col(SpriteBatch sb,
                                           float screenX,
                                           float renderY,
                                           int maxRows,
                                           List<Rate<A>> rates) {
        int minSample = moreStatsScreen.sampleSizeDropdown.getSelectedIndex();
        StringBuilder builder = new StringBuilder();
        final String color = getCharColor();
        
        int len = rates.size();
        if (maxRows > 0) {
            len = Math.min(len, maxRows);
        }
        
        for (int i = 0; i < len; i++) {
            Rate<A> c = rates.get(i);
            if (c.getSampleSize() < minSample) continue;
            
            builder.append(NameMapper.toChinese(c.what.toString()))
                    .append(" ")
                    .append(color)
                    .append(c.percent())
                    .append("% ")
                    .append(c.winLoss())
                    .append(" NL ");
        }
        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                builder.toString(),
                screenX + 75.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);
    }

    private static void renderCardPickRate(StatsScreen s, SpriteBatch sb, boolean act1, float screenX) {
        float renderY = getScrollY(s);
        String baseKey = act1 ? LocalizationKeys.UI_CARD_PICK_RATE_ACT_1 : LocalizationKeys.UI_CARD_PICK_RATE_AFTER_ACT_1;
        String top200 = Localization.get(LocalizationKeys.UI_TOP_200);
        String name = Localization.get(baseKey) + " " + top200;
        renderHeader(sb, colorForClass(name), screenX, renderY);

        ClassStat cs = moreStatsScreen.getClassStat();
        List<Rate<Card>> picks = act1 ? cs.cardPicksAct1 : cs.cardPicksAfterAct1;
        renderRates(sb, screenX, renderY, 200, picks);
    }


    private static void renderCardWinRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        String baseKey = LocalizationKeys.UI_CARD_WIN_RATE;
        String top200 = Localization.get(LocalizationKeys.UI_TOP_200);
        String name = Localization.get(baseKey) + " " + top200;
        renderHeader(sb, colorForClass(name), screenX, renderY);

        ClassStat cs = moreStatsScreen.getClassStat();
        renderRates(sb, screenX, renderY, 200, cs.cardWinRate);
    }

    private static void renderNeowBonus(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_NEOW_WIN_RATE)), screenX, renderY);

        ClassStat cs = moreStatsScreen.getClassStat();
        renderRates1Col(sb, screenX, renderY, 0, cs.neowWinRate);

        renderY -= 44.0F * Settings.scale * cs.neowWinRate.size();
        renderY -= 100 * Settings.scale;
        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_NEOW_PICK_RATE)), screenX, renderY);

        renderNeowOptions(sb, screenX, renderY, cs.neowPickRate);
    }

    /**
     * 尼奥选项按「选中次数 ÷ 全部尼奥选择次数」显示。
     *
     * 不能复用 renderRates1Col 的 percent()：.run 里被跳过选项的代价全是 NONE，
     * 带代价的选项收不到跳过计数，win/(win+loss) 会恒等于 100%。
     */
    private static void renderNeowOptions(SpriteBatch sb, float screenX, float renderY,
                                          List<Rate<Neow>> rates) {
        if (rates == null || rates.isEmpty()) {
            return;
        }
        int minSample = moreStatsScreen.sampleSizeDropdown.getSelectedIndex();
        int totalSelected = 0;
        for (Rate<Neow> rate : rates) {
            if (rate != null && rate.what != null && rate.win > 0) {
                totalSelected += rate.win;
            }
        }
        final String color = getCharColor();
        StringBuilder builder = new StringBuilder();
        // 分母用「该选项被提供的次数」（选中 + 被跳过），不是总局数：
        // 用总局数会让「出现频率低的选项」永远看起来没人选。排序也按选取率。
        List<Rate<Neow>> ordered = new ArrayList<Rate<Neow>>(rates);
        Collections.sort(ordered, new Comparator<Rate<Neow>>() {
            @Override
            public int compare(Rate<Neow> a, Rate<Neow> b) {
                return Double.compare(b.percent(), a.percent());
            }
        });
        for (Rate<Neow> rate : ordered) {
            if (rate == null || !(rate.what instanceof Neow) || rate.win <= 0
                    || rate.getSampleSize() < minSample) {
                continue;
            }
            Neow neow = (Neow) rate.what;
            int offered = rate.total();
            double pct = offered > 0 ? 100.0D * rate.win / offered : 0.0D;
            builder.append(NeowShort.zh(neow.bonus, neow.cost))
                    .append(" ")
                    .append(color)
                    .append(String.format("%.1f%% (%d/%d) NL ", pct, rate.win, offered));
        }
        FontHelper.renderSmartText(sb,
                FontHelper.panelNameFont,
                builder.toString(),
                screenX + 75.0F * Settings.scale,
                renderY + 766.0F * Settings.yScale,
                9999.0F,
                38.0F * Settings.scale,
                Settings.CREAM_COLOR);
    }

    private static void renderBossRelicPickRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_BOSS_RELIC_PICK_RATE_ACT_1)), screenX, renderY);

        renderRates(sb, screenX, renderY, 0, cs.bossRelicPickRateAct1);

        renderY -= 22.0F * Settings.scale * cs.bossRelicPickRateAct1.size();
        renderY -= 100 * Settings.scale;
        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_BOSS_RELIC_PICK_RATE_ACT_2)), screenX, renderY);

        renderRates(sb, screenX, renderY, 0, cs.bossRelicPickRateAct2);
    }

    private static void renderBossRelicWinRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_BOSS_RELIC_WIN_RATE_ACT_1)), screenX, renderY);
        renderRates(sb, screenX, renderY, 0, cs.bossRelicWinRateAct1);

        renderY -= 22.0F * Settings.scale * cs.bossRelicPickRateAct1.size();
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_BOSS_RELIC_WIN_RATE_ACT_2)), screenX, renderY);
        renderRates(sb, screenX, renderY, 0, cs.bossRelicWinRateAct2);

        renderY -= 22.0F * Settings.scale * cs.bossRelicPickRateAct2.size();
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_BOSS_SWAP_WIN_RATE)), screenX, renderY);
        renderRates(sb, screenX, renderY, 0, cs.bossRelicWinRateSwap);
    }

    // Boss遭遇名称集合，用于区分Boss战损和非Boss战损
    private static final Set<String> BOSS_ENCOUNTERS = new HashSet<>(Arrays.asList(
            "Slime Boss", "The Guardian", "Hexaghost",
            "The Collector", "Bronze Automaton", "Champ",
            "Donu and Deca", "Awakened One", "Time Eater",
            "The Heart"
    ));

    private static boolean isBossEncounter(String name) {
        return BOSS_ENCOUNTERS.contains(name);
    }

    private static final Set<String> TOTAL_NAMES = new HashSet<>(Arrays.asList(
            "战斗总计", "非Boss战斗总计"
    ));

    private static boolean isTotalEntry(String name) {
        return TOTAL_NAMES.contains(name);
    }

    private static float renderActSummary(SpriteBatch sb, float screenX, float renderY,
            List<Mean> actData, String title) {
        final float LINE = 22.0F * Settings.scale;

        // 检查是否有战斗总计条目
        boolean hasCombatTotal = false;
        for (Mean m : actData) {
            if ("战斗总计".equals(m.what.toString())) {
                hasCombatTotal = true;
                break;
            }
        }

        if (!hasCombatTotal) {
            return renderY;
        }

        renderHeader(sb, colorForClass(title), screenX, renderY);
        renderY -= LINE;
        return renderY;
    }

    private static List<Mean> combineLists(List<Mean> a, List<Mean> b) {
        List<Mean> combined = new ArrayList<>(a.size() + b.size());
        combined.addAll(a);
        combined.addAll(b);
        return combined;
    }

    private static void renderEncounterHPLoss(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        final String color = getCharColor();
        List<Mean> act1 = cs.averageDamageTaken.getOrDefault(1, new ArrayList<>());
        List<Mean> act2 = cs.averageDamageTaken.getOrDefault(2, new ArrayList<>());
        List<Mean> act3 = cs.averageDamageTaken.getOrDefault(3, new ArrayList<>());
        List<Mean> act4 = cs.averageDamageTaken.getOrDefault(4, new ArrayList<>());

        renderY = renderActSummary(sb, screenX, renderY, act1, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_HP_LOSS_ACT_1));
        renderT(sb, screenX, renderY, 0, act1, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act1.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act2, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_HP_LOSS_ACT_2));
        renderT(sb, screenX, renderY, 0, act2, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act2.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act3, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_HP_LOSS_ACT_3));
        renderT(sb, screenX, renderY, 0, act3, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act3.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act4, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_HP_LOSS_ACT_4));
        renderT(sb, screenX, renderY, 0, act4, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
    }

    private static void renderEncounterCombatHPLoss(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        final String color = getCharColor();
        List<Mean> act1 = cs.averageActualHPLoss.getOrDefault(1, new ArrayList<>());
        List<Mean> act2 = cs.averageActualHPLoss.getOrDefault(2, new ArrayList<>());
        List<Mean> act3 = cs.averageActualHPLoss.getOrDefault(3, new ArrayList<>());
        List<Mean> act4 = cs.averageActualHPLoss.getOrDefault(4, new ArrayList<>());

        renderY = renderActSummary(sb, screenX, renderY, act1, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_COMBAT_HP_LOSS_ACT_1));
        renderT(sb, screenX, renderY, 0, act1, x -> formatHPLossEntry(x, color));
        renderY -= 22.0F * Settings.scale * act1.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act2, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_COMBAT_HP_LOSS_ACT_2));
        renderT(sb, screenX, renderY, 0, act2, x -> formatHPLossEntry(x, color));
        renderY -= 22.0F * Settings.scale * act2.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act3, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_COMBAT_HP_LOSS_ACT_3));
        renderT(sb, screenX, renderY, 0, act3, x -> formatHPLossEntry(x, color));
        renderY -= 22.0F * Settings.scale * act3.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, act4, Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_COMBAT_HP_LOSS_ACT_4));
        renderT(sb, screenX, renderY, 0, act4, x -> formatHPLossEntry(x, color));
    }

    /**
     * 格式化战损条目显示名称，处理"事件:"前缀和总计条目
     * - 总计条目（战斗总计/事件总计/每幕总计）直接显示中文名称
     * - 事件条目去掉"事件:"前缀后翻译，然后加上"事件:"前缀
     * - 普通战斗条目使用 NameMapper 翻译
     */
    private static String formatHPLossEntry(Mean m, String color) {
        String name = m.what.toString();
        // 总计条目：直接显示
        if (isTotalEntry(name)) {
            return name + " " + color + Utils.round(m.mean(), 3);
        }
        // 事件条目：保留"事件:"前缀，只翻译事件名
        if (name.startsWith("事件:")) {
            String eventName = name.substring(3); // 去掉"事件:"
            String translated = NameMapper.toChinese(eventName);
            return "事件:" + translated + " " + color + Utils.round(m.mean(), 3);
        }
        // 普通战斗条目
        return NameMapper.toChinese(name) + " " + color + Utils.round(m.mean(), 3);
    }

    private static void renderEncounterSplitHPLoss(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        final String color = getCharColor();
        List<Mean> noHeal1 = cs.averageNoHealActualHPLoss.getOrDefault(1, new ArrayList<>());
        List<Mean> noHeal2 = cs.averageNoHealActualHPLoss.getOrDefault(2, new ArrayList<>());
        List<Mean> noHeal3 = cs.averageNoHealActualHPLoss.getOrDefault(3, new ArrayList<>());
        List<Mean> noHeal4 = cs.averageNoHealActualHPLoss.getOrDefault(4, new ArrayList<>());
        List<Mean> heal1 = cs.averageHealActualHPLoss.getOrDefault(1, new ArrayList<>());
        List<Mean> heal2 = cs.averageHealActualHPLoss.getOrDefault(2, new ArrayList<>());
        List<Mean> heal3 = cs.averageHealActualHPLoss.getOrDefault(3, new ArrayList<>());
        List<Mean> heal4 = cs.averageHealActualHPLoss.getOrDefault(4, new ArrayList<>());

        Map<String, Mean> healMap1 = toNameMap(heal1);
        Map<String, Mean> healMap2 = toNameMap(heal2);
        Map<String, Mean> healMap3 = toNameMap(heal3);
        Map<String, Mean> healMap4 = toNameMap(heal4);

        renderY = renderActSummary(sb, screenX, renderY, combineLists(noHeal1, heal1), Localization.get(LocalizationKeys.UI_ENCOUNTER_SPLIT_HP_LOSS_ACT_1));
        renderT(sb, screenX, renderY, 0, noHeal1, x -> formatSplitHPLoss(x, healMap1, color));
        renderY -= 22.0F * Settings.scale * noHeal1.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, combineLists(noHeal2, heal2), Localization.get(LocalizationKeys.UI_ENCOUNTER_SPLIT_HP_LOSS_ACT_2));
        renderT(sb, screenX, renderY, 0, noHeal2, x -> formatSplitHPLoss(x, healMap2, color));
        renderY -= 22.0F * Settings.scale * noHeal2.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, combineLists(noHeal3, heal3), Localization.get(LocalizationKeys.UI_ENCOUNTER_SPLIT_HP_LOSS_ACT_3));
        renderT(sb, screenX, renderY, 0, noHeal3, x -> formatSplitHPLoss(x, healMap3, color));
        renderY -= 22.0F * Settings.scale * noHeal3.size();
        renderY -= 100 * Settings.scale;

        renderY = renderActSummary(sb, screenX, renderY, combineLists(noHeal4, heal4), Localization.get(LocalizationKeys.UI_ENCOUNTER_SPLIT_HP_LOSS_ACT_4));
        renderT(sb, screenX, renderY, 0, noHeal4, x -> formatSplitHPLoss(x, healMap4, color));
    }

    private static Map<String, Mean> toNameMap(List<Mean> list) {
        Map<String, Mean> map = new HashMap<>();
        for (Mean m : list) {
            map.put(m.what.toString(), m);
        }
        return map;
    }

    private static String formatSplitHPLoss(Mean noHeal, Map<String, Mean> healMap, String color) {
        String name = NameMapper.toChinese(noHeal.what.toString());
        String noHealStr = String.valueOf(Utils.round(noHeal.mean(), 1));
        Mean healMean = healMap.get(noHeal.what.toString());
        String healStr = healMean != null ? String.valueOf(Utils.round(healMean.mean(), 1)) : "-";
        return name + " " + color + noHealStr + "/" + healStr;
    }

    private static void renderEncounterMortalityRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        List<Rate<String>> act1 = cs.encounterDeadRate.get(1);
        List<Rate<String>> act2 = cs.encounterDeadRate.get(2);
        List<Rate<String>> act3 = cs.encounterDeadRate.get(3);
        List<Rate<String>> act4 = cs.encounterDeadRate.get(4);

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_MORTALITY_RATE_ACT_1)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act1, LocalizationKeys.TOTAL_ENCOUNTERS, 1, cs);
        renderY -= 22.0F * Settings.scale * (act1.size() + 1);
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_MORTALITY_RATE_ACT_2)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act2, LocalizationKeys.TOTAL_ENCOUNTERS, 2, cs);
        renderY -= 22.0F * Settings.scale * (act2.size() + 1);
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_MORTALITY_RATE_ACT_3)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act3, LocalizationKeys.TOTAL_ENCOUNTERS, 3, cs);
        renderY -= 22.0F * Settings.scale * (act3.size() + 1);
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_MORTALITY_RATE_ACT_4)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act4, LocalizationKeys.TOTAL_ENCOUNTERS, 4, cs);
    }

    // 辅助函数：渲染列表（带汇总信息作为第一行）
    private static void renderRatesWithSummary(SpriteBatch sb, float screenX, float renderY, List<Rate<String>> rates, String summaryKey, int actNumber, ClassStat cs) {
        if (rates == null || rates.isEmpty()) {
            return;
        }
        
        // 计算总遭遇数
        int totalEncounters = 0;
        int totalLosses = 0;
        for (Rate<String> rate : rates) {
            totalEncounters += rate.total();
            totalLosses += rate.loss;
        }
        
        // 创建新列表，第一行是问号统计，第二行是汇总信息
        List<Rate<String>> ratesWithSummary = new ArrayList<>();
        
        // 添加问号统计（第一位）
        Integer questionMarkCount = cs.questionMarksByAct.get(actNumber);
        if (questionMarkCount != null && questionMarkCount > 0) {
            Rate<String> qmRate = new Rate<>("问号节点");
            qmRate.win = questionMarkCount;
            qmRate.loss = 0;
            ratesWithSummary.add(qmRate);
        }
        
        // 创建汇总 Rate 对象（第二位）
        Rate<String> summaryRate = new Rate<>(Localization.get(summaryKey));
        summaryRate.win = totalEncounters - totalLosses;
        summaryRate.loss = totalLosses;
        ratesWithSummary.add(summaryRate);
        
        // 添加原有列表
        ratesWithSummary.addAll(rates);
        
        // 渲染整个列表
        renderRates(sb, screenX, renderY, 0, ratesWithSummary);
    }

    private static void renderEncounterPotionsUsed(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        final String color = getCharColor();
        List<Mean> act1 = cs.averagePotionUse.getOrDefault(1, new ArrayList<>());
        List<Mean> act2 = cs.averagePotionUse.getOrDefault(2, new ArrayList<>());
        List<Mean> act3 = cs.averagePotionUse.getOrDefault(3, new ArrayList<>());
        List<Mean> act4 = cs.averagePotionUse.getOrDefault(4, new ArrayList<>());

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_POTIONS_USED_ACT_1)), screenX, renderY);
        renderT(sb, screenX, renderY, 0, act1, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act1.size();
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_POTIONS_USED_ACT_2)), screenX, renderY);
        renderT(sb, screenX, renderY, 0, act2, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act2.size();
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_POTIONS_USED_ACT_3)), screenX, renderY);
        renderT(sb, screenX, renderY, 0, act3, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
        renderY -= 22.0F * Settings.scale * act3.size();
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_ENCOUNTER_AVERAGE_POTIONS_USED_ACT_4)), screenX, renderY);
        renderT(sb, screenX, renderY, 0, act4, x -> NameMapper.toChinese(x.what.toString()) + " " + color + Utils.round(x.mean(), 3));
    }

    private static void renderRelicWinRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        String name = Localization.get(LocalizationKeys.UI_RELIC_WIN_RATE);
        renderHeader(sb, colorForClass(name), screenX, renderY);

        ClassStat cs = moreStatsScreen.getClassStat();
        renderRates(sb, screenX, renderY, 0, cs.relicWinRate);
    }

    private static void renderRelicWinRateWhenPurchased(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        String name = Localization.get(LocalizationKeys.UI_RELIC_WIN_RATE_WHEN_PURCHASED);
        renderHeader(sb, colorForClass(name), screenX, renderY);

        ClassStat cs = moreStatsScreen.getClassStat();
        renderRates(sb, screenX, renderY, 0, cs.relicPurchasedWinRate);
    }

    private static void renderEventWinRate(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        List<Rate<String>> act1 = cs.eventWinRateAct1;
        List<Rate<String>> act2 = cs.eventWinRateAct2;
        List<Rate<String>> act3 = cs.eventWinRateAct3;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_EVENT_WIN_RATE_ACT_1)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act1, LocalizationKeys.TOTAL_EVENTS, 1, cs);
        renderY -= 22.0F * Settings.scale * (act1.size() + 2);  // +2: 问号+总事件
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_EVENT_WIN_RATE_ACT_2)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act2, LocalizationKeys.TOTAL_EVENTS, 2, cs);
        renderY -= 22.0F * Settings.scale * (act2.size() + 2);  // +2: 问号+总事件
        renderY -= 100 * Settings.scale;

        renderHeader(sb, colorForClass(Localization.get(LocalizationKeys.UI_EVENT_WIN_RATE_ACT_3)), screenX, renderY);
        renderRatesWithSummary(sb, screenX, renderY, act3, LocalizationKeys.TOTAL_EVENTS, 3, cs);
    }

    private static void renderMetaScaling(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        renderHeader(sb, NAMES[0], screenX, renderY);
        moreStatsScreen.getClassStat().metaScaling.render(sb, screenX, renderY);
        renderY -= 350.0F * Settings.scale;

        for (int i = 0; i < 4; i++) {
            String name = NAMES[i + 2];
            renderHeader(sb, name, screenX, renderY);
            moreStatsScreen.classStats[i].metaScaling.render(sb, screenX, renderY);
            renderY -= 350.0F * Settings.scale;
        }
    }
    
    private static void renderCampfireActions(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        
        // 渲染每个幕的火堆行动统计（添加前35提示）
        renderY = renderCampfireAct(sb, screenX, renderY, "第一幕（前35）", cs.campfireActionsAct1);
        renderY = renderCampfireAct(sb, screenX, renderY, "第二幕（前35）", cs.campfireActionsAct2);
        renderY = renderCampfireAct(sb, screenX, renderY, "第三幕（前35）", cs.campfireActionsAct3);
        renderY = renderCampfireAct(sb, screenX, renderY, "第四幕（前35）", cs.campfireActionsAct4);
    }
    
    private static float renderCampfireAct(SpriteBatch sb, float screenX, float renderY, String actName, List<Rate<Object>> actions) {
        final String color = getCharColor();
        final int actTotal = totalActionCount(actions, "火堆总数");
        renderHeader(sb, colorForClass(actName), screenX, renderY);
        renderY -= 22.0F * Settings.scale;
        
        // 使用 renderT 双列布局渲染，每幕只显示前35项
        renderT(sb, screenX, renderY, 35, actions, c -> {
            Object what = c.what;
            String displayName;
            if (what instanceof String) {
                // 行动类型
                String actionKey = (String) what;
                Map<String, String> actionNames = new HashMap<>();
                actionNames.put("SMITH", "升级");
                actionNames.put("RECALL", "回忆");
                actionNames.put("REST", "睡觉");
                actionNames.put("PURGE", "删牌");
                actionNames.put("DIG", "挖掘");
                actionNames.put("LIFT", "举重");
                displayName = actionNames.getOrDefault(actionKey, actionKey);
            } else {
                // 卡牌
                displayName = NameMapper.toChinese(what.toString());
            }
            // 火堆的 win 记的是次数、loss 为 0，percent() 会恒等于 100%；
            // 真正有意义的是「这次行动占该幕火堆行动的比例」。
            if ("火堆总数".equals(what)) {
                return displayName + " " + color + actTotal + " 次";
            }
            // 行动表看的是「做了几次」，不是占比
            return displayName + " " + color + c.total() + " 次";
        });
        
        // 计算渲染后的高度偏移（最多显示35项）
        int displayCount = Math.min(actions.size(), 35);
        renderY -= 22.0F * Settings.scale * displayCount;
        renderY -= 100 * Settings.scale;
        
        return renderY;
    }
    
    private static void renderShopActions(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();
        
        // 渲染每个幕的商店行动统计（添加前30提示）
        renderY = renderShopAct(sb, screenX, renderY, "第一幕（前30）", cs.shopActionsAct1);
        renderY = renderShopAct(sb, screenX, renderY, "第二幕（前30）", cs.shopActionsAct2);
        renderY = renderShopAct(sb, screenX, renderY, "第三幕（前30）", cs.shopActionsAct3);
        renderY = renderShopAct(sb, screenX, renderY, "第四幕（前30）", cs.shopActionsAct4);
    }
    
    private static float renderShopAct(SpriteBatch sb, float screenX, float renderY, String actName, List<Rate<Object>> actions) {
        final String color = getCharColor();
        final int shopTotal = totalActionCount(actions, "商店总数");
        final int purchaseTotal = shopPurchaseCount(actions);
        renderHeader(sb, colorForClass(actName), screenX, renderY);
        renderY -= 22.0F * Settings.scale;
        
        // 使用 renderT 双列布局渲染，每幕显示前32项（2个统计项 + 30个具体物品）
        renderT(sb, screenX, renderY, 32, actions, c -> {
            Object what = c.what;
            String displayName;
            if (what instanceof String) {
                String itemKey = (String) what;
                // 商店总数和有效商店数特殊处理（不翻译）
                if (itemKey.equals("商店总数") || itemKey.equals("有效商店数")) {
                    displayName = itemKey;
                }
                // 商店删牌特殊处理
                else if (itemKey.equals("商店删牌")) {
                    displayName = "商店删牌";
                } else {
                    // 其他物品使用 NameMapper 转换
                    displayName = NameMapper.toChinese(itemKey);
                }
            } else {
                displayName = what.toString();
            }
            // 商店的 win 同样只是「次数」，percent() 恒为 100%。
            // 商店总数按「遇到的商店」计，其余行按「购买总次数」计。
            if ("商店总数".equals(what)) {
                return displayName + " " + color + shopTotal + " 次";
            }
            if ("有效商店数".equals(what)) {
                // 只写率：分母（商店总数）就在上一行，再写 (107/111) 会把右列挤掉
                return displayName + " " + color + sharePercent(c.total(), shopTotal) + " 有效";
            }
            // 商品表看的是「买了几次」，不是占比
            return displayName + " " + color + c.total() + " 次";
        });
        
        // 计算渲染后的高度偏移（最多显示32项）
        int displayCount = Math.min(actions.size(), 32);
        renderY -= 22.0F * Settings.scale * displayCount;
        renderY -= 100 * Settings.scale;
        
        return renderY;
    }

    private static int totalActionCount(List<Rate<Object>> actions, String totalKey) {
        for (Rate<Object> rate : actions) {
            if (rate != null && totalKey.equals(rate.what)) {
                return rate.total();
            }
        }
        int sum = 0;
        for (Rate<Object> rate : actions) {
            if (rate != null) {
                sum += rate.total();
            }
        }
        return sum;
    }

    private static int shopPurchaseCount(List<Rate<Object>> actions) {
        int sum = 0;
        for (Rate<Object> rate : actions) {
            if (rate == null) {
                continue;
            }
            Object what = rate.what;
            if ("商店总数".equals(what) || "有效商店数".equals(what)) {
                continue;
            }
            sum += rate.total();
        }
        return sum;
    }

    private static String shareText(int count, int total) {
        if (total <= 0) {
            return String.valueOf(count);
        }
        return String.format("%.1f%% (%d/%d)", 100.0D * count / total, count, total);
    }

    // 不设额外下限：门槛完全交给界面顶部的「样本量」下拉（0-10，默认 0 = 不限）。
    // 原值 10 会盖住下拉，玩家调到 0 也只剩下候选 ≥10 的卡。
    private static final int MIN_UPGRADE_OWNED = 0;

    /**
     * 火堆偏好：同一批火堆决策上的两层视图。
     *
     * 选项层回答「这次火堆为什么没敲卡」：每个选项（升级/睡觉/回忆/抽烟/举重/挖掘）
     * 的分母是它<b>真正可选</b>的次数，所以抽烟只在有和平烟斗的 120 次里算，
     * 睡觉则是每次火堆都能选。倍数 = 该选项被选概率 ÷ 随机挑一个的期望概率。
     * 回忆拆「强制」：要进第四幕就必须在前三幕消耗一个火堆拿红钥匙，是固定成本，
     * 因此第三幕回忆占比高不是偏好，是亏损掉的一个火堆。
     *
     * 卡牌层回答「有这些候选时，哪张更值得敲」：每次锻造的分母是当时未升级卡牌种类数，
     * 同名多张只算一个选项。倍数 = 该卡被敲概率 ÷ 随机基线。带 * 的卡有若干次锻造
     * 因卡组无法重建（星盘/空鸟笼）没能计入，这类对局以 A 口列（导出表）为准。
     */
    private static void renderCampfireUpgradePref(StatsScreen s, SpriteBatch sb, float screenX) {
        float renderY = getScrollY(s);
        ClassStat cs = moreStatsScreen.getClassStat();

        renderY = renderUpgradePrefAct(sb, screenX, renderY, 1, "第一幕 · 火堆偏好", cs);
        renderY = renderUpgradePrefAct(sb, screenX, renderY, 2, "第二幕 · 火堆偏好", cs);
        renderY = renderUpgradePrefAct(sb, screenX, renderY, 3, "第三幕 · 火堆偏好", cs);
        renderY = renderUpgradePrefAct(sb, screenX, renderY, 4, "第四幕 · 火堆偏好", cs);
        renderUpgradePrefAct(sb, screenX, renderY, 0, "四幕合计 · 火堆偏好", cs);
    }

    private static float renderUpgradePrefAct(SpriteBatch sb,
                                              float screenX,
                                              float renderY,
                                              int act,
                                              String actName,
                                              ClassStat cs) {
        final String color = getCharColor();
        final int visits = cs.campfireVisits[act];
        renderHeader(sb, colorForClass(actName), screenX, renderY);
        renderY -= 22.0F * Settings.scale;

        // 概览：平均池子大小决定随机基线
        final double baseline = cs.campfireUpgradeBaseline[act];
        final int ok = cs.campfireRebuildOk[act];
        final int miss = cs.campfireRebuildMiss[act];
        String left = "火堆 " + visits + " 次 · 锻造 " + sharePercent(cs.campfireSmiths[act], visits)
                + " NL 平均候选 " + String.format("%.1f", cs.campfireUpgradeAvgOptions[act]) + " 个"
                + " · 随机基线 " + String.format("%.1f%%", baseline) + "（倍 = 占比÷基线）";
        String right = "重建覆盖 " + ok + "/" + (ok + miss)
                + " (" + sharePercent(ok, ok + miss) + ")";
        if (act == 0 && (cs.campfireAstrolabeRuns > 0 || cs.campfireEmptyCageRuns > 0)) {
            right = right + " NL 星盘 " + cs.campfireAstrolabeRuns + " 局 · 空鸟笼 "
                    + cs.campfireEmptyCageRuns + " 局";
        }
        render2Columns(sb, screenX, renderY, left, right);
        renderY -= (act == 0 ? 120.0F : 82.0F) * Settings.scale;

        // 睡觉单列：约束是血量，不和「敲哪张 / 用哪个遗物」抢同一次选择
        ClassStat.CampfireOption sleep = null;
        for (ClassStat.CampfireOption option : campfireOptionsOf(cs, act)) {
            if (option != null && "REST".equals(option.key)) {
                sleep = option;
            }
        }
        if (sleep != null && sleep.available > 0) {
            String sleepLine = "睡觉 " + String.format("%.1f%%", sleep.percent())
                    + " (" + sleep.chosen + "/" + sleep.available + ")";
            render2Columns(sb, screenX, renderY, sleepLine, "");
            renderY -= 38.0F * Settings.scale + 8.0F * Settings.scale;
        }

        // 统一榜：每张可敲的牌 + 抽烟/举重/挖掘/回忆，按占比降序，同一把尺子
        List<ClassStat.CampfireShare> rows = campfireDistOf(cs, act);
        if (!rows.isEmpty()) {
            renderT(sb, screenX, renderY, UPGRADE_ROWS, rows, r -> {
                String name;
                if (r.card) {
                    name = NameMapper.toChinese(((Card) r.what).name);
                } else {
                    name = optionZh((String) r.what);
                }
                boolean missed = r.card && cs.campfireUpgradeMissCard != null
                        && cs.campfireUpgradeMissCard.containsKey(r.what);
                double mult = baseline > 0.0D ? r.percent() / baseline : 0.0D;
                return name + " " + String.format("%.1f%%", r.percent())
                        + " " + color + String.format("%.1f倍", mult)
                        + " " + color + "(" + r.chosen + "/" + r.visits + ")"
                        + (missed ? "*" : "");
            });
        }

        int displayCount = Math.min(rows.size(), UPGRADE_ROWS);
        renderY -= 22.0F * Settings.scale * displayCount;
        renderY -= 100 * Settings.scale;
        return renderY;
    }

    private static List<ClassStat.CampfireShare> campfireDistOf(ClassStat cs, int act) {
        switch (act) {
            case 1: return cs.campfireChoiceDistAct1;
            case 2: return cs.campfireChoiceDistAct2;
            case 3: return cs.campfireChoiceDistAct3;
            case 4: return cs.campfireChoiceDistAct4;
            default: return cs.campfireChoiceDistTotal;
        }
    }

    /** 火堆偏好每幕最多显示多少条（超过靠滚动）。 */
    private static final int UPGRADE_ROWS = 50;

    /**
     * 六个火堆选项的占比：被选 ÷ 该选项真正可选的次数，**按占比从高到低排名**。
     *
     * 升级、睡觉、回忆、抽烟、举重、挖掘是同一次决策里的平级选项，谁高谁低就是玩家的取舍，
     * 所以不再按固定顺序摆，直接按被选概率排。不可选的（咖啡杯禁用睡觉、锤子禁用升级、
     * 没遗物的抽烟/举重/挖掘、已经用过的回忆）根本不参与分母，这里也不会出现。
     *
     * 行格式与卡牌层一致：`名称 占比 倍数 (被选/可选)`。倍数用选项层自己的基线
     * （100 ÷ 该幕平均可选项数），所以选项之间可以比，但不能拿去和卡牌的倍数比 ——
     * 卡牌的分母是「可升级的卡种数」，两者不是同一把尺子。
     */
    private static float renderCampfireOptionWeights(SpriteBatch sb, float screenX, float renderY,
                                                     int act, ClassStat cs) {
        List<ClassStat.CampfireOption> options = campfireOptionsOf(cs, act);
        if (options == null || options.isEmpty()) {
            return renderY;
        }
        List<ClassStat.CampfireOption> shown = new ArrayList<ClassStat.CampfireOption>();
        for (ClassStat.CampfireOption o : options) {
            if (o != null && o.available > 0) {
                shown.add(o);
            }
        }
        Collections.sort(shown, new Comparator<ClassStat.CampfireOption>() {
            @Override
            public int compare(ClassStat.CampfireOption a, ClassStat.CampfireOption b) {
                return Double.compare(b.percent(), a.percent());
            }
        });

        final String color = getCharColor();
        StringBuilder left = new StringBuilder();
        StringBuilder right = new StringBuilder();
        int leftRows = 0;
        int rightRows = 0;
        for (int i = 0; i < shown.size(); i++) {
            ClassStat.CampfireOption o = shown.get(i);
            String line = optionZh(o.key)
                    + " " + String.format("%.1f%%", o.percent())
                    + " " + color + String.format("%.1f倍", o.multiple())
                    + " " + color + "(" + o.chosen + "/" + o.available + ")";
            // 左列放排名前一半，右列放后半；不足 4 项时全部靠左，避免右侧孤零零一行
            if (i < (shown.size() + 1) / 2) {
                left.append(leftRows++ > 0 ? " NL " : "").append(line);
            } else {
                right.append(rightRows++ > 0 ? " NL " : "").append(line);
            }
        }
        render2Columns(sb, screenX, renderY, left.toString(), right.toString());
        int rows = Math.max(leftRows, rightRows);
        return renderY - rows * 38.0F * Settings.scale - 10.0F * Settings.scale;
    }

    private static String optionZh(String key) {
        if ("SMITH".equals(key)) return "升级";
        if ("REST".equals(key)) return "睡觉";
        if ("RECALL".equals(key)) return "回忆";
        if ("PURGE".equals(key)) return "抽烟";
        if ("LIFT".equals(key)) return "举重";
        if ("DIG".equals(key)) return "挖掘";
        return key;
    }

    private static final int OPTION_RECALL_INDEX = 2;

    private static List<ClassStat.CampfireOption> campfireOptionsOf(ClassStat cs, int act) {
        switch (act) {
            case 1: return cs.campfireOptionsAct1;
            case 2: return cs.campfireOptionsAct2;
            case 3: return cs.campfireOptionsAct3;
            case 4: return cs.campfireOptionsAct4;
            default: return cs.campfireOptionsTotal;
        }
    }

    private static String sharePercent(int count, int total) {
        if (total <= 0) {
            return "0.0%";
        }
        return String.format("%.1f%%", 100.0D * count / total);
    }

    private static void renderTierListExportButton(SpriteBatch sb,
                                                   float screenX,
                                                   float dropdownY) {
        float btnX = screenX + screenPosX(EXPORT_BUTTON_X_OFFSET);
        float btnY = dropdownY;
        float btnW = screenPosX(240);
        float btnH = screenPosY(44);
        float line = Math.max(1.0F, 2.0F * Settings.scale);

        boolean hovered = tierExportHb != null && tierExportHb.hovered;
        if (ImageMaster.WHITE_SQUARE_IMG != null) {
            sb.setColor(0.0F, 0.0F, 0.0F, 0.5F);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX + 2.0F * Settings.scale, btnY - 2.0F * Settings.scale, btnW, btnH);
            if (hovered) {
                sb.setColor(0.23F, 0.20F, 0.11F, 1.0F);
            } else {
                sb.setColor(0.11F, 0.12F, 0.15F, 0.96F);
            }
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX, btnY, btnW, btnH);
            sb.setColor(0.84F, 0.68F, 0.30F, 1.0F);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX, btnY, btnW, line);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX, btnY + btnH - line, btnW, line);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX, btnY, line, btnH);
            sb.draw(ImageMaster.WHITE_SQUARE_IMG, btnX + btnW - line, btnY, line, btnH);
            sb.setColor(Color.WHITE);
        }

        int statTypeIndex = moreStatsScreen == null || moreStatsScreen.statTypeDropdown == null
                ? -1
                : moreStatsScreen.statTypeDropdown.getSelectedIndex();
        String text;
        boolean showStatus = tierExportStatus != null && System.currentTimeMillis() < tierExportStatusUntil;
        if (showStatus) {
            text = tierExportStatus;
        } else if (statTypeIndex == 0) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_ALL_TIER_LIST);
        } else if (statTypeIndex == 8) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_MONSTER_TIER);
        } else if (statTypeIndex == 14) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_CAMPFIRE_TIER);
        } else if (statTypeIndex == 15) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_SHOP_TIER);
        } else if (statTypeIndex == 16) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_CAMPFIRE_UPGRADE_TIER);
        } else if (statTypeIndex == 4) {
            text = Localization.get(LocalizationKeys.UI_EXPORT_NEOW_TIER);
        } else {
            text = Localization.get(LocalizationKeys.UI_EXPORT_TIER_LIST);
        }
        FontHelper.renderFontCentered(sb,
                FontHelper.tipBodyFont,
                text,
                btnX + btnW / 2.0F,
                btnY + btnH / 2.0F,
                Settings.CREAM_COLOR);
    }

    private static boolean isTierListPage(int statTypeIndex) {
        // 0: 总体统计，导出全部四组 + 全部统计 CSV；1/2: 卡牌；5: BOSS 遗物；
        // 8: 遭遇战平均战损，导出怪物危险度梯度（独立导出包，不改动原统计）；
        // 4: 尼奥开局选项（每个「奖励 + 代价」单独一行，按选中次数排名）；
        // 14/15: 火堆行动 / 商店行动（按幕拆「升级目标卡」「购买商品」等分组）
        // 16: 火堆偏好（行动占比 + 升级次数 / 拥有张数，按幕拆，合计放最后）
        return statTypeIndex == 0 || statTypeIndex == 1 || statTypeIndex == 2
                || statTypeIndex == 4 || statTypeIndex == 5
                || statTypeIndex == 14 || statTypeIndex == 15 || statTypeIndex == 16
                || statTypeIndex == 8;
    }

    private static TierListScope scopeForStatType(int statTypeIndex) {
        switch (statTypeIndex) {
            case 0:
                return TierListScope.ALL;
            case 1:
                return TierListScope.CARDS_ACT1;
            case 2:
                return TierListScope.CARDS_AFTER_ACT1;
            case 4:
                return TierListScope.NEOW;
            case 5:
                return TierListScope.BOSS_RELICS;
            case 14:
                return TierListScope.CAMPFIRE;
            case 15:
                return TierListScope.SHOP;
            case 16:
                return TierListScope.CAMPFIRE_UPGRADE;
            default:
                return TierListScope.ALL;
        }
    }

    private static void updateTierListExportButton(StatsScreen s) {
        if (moreStatsScreen == null || moreStatsScreen.statTypeDropdown == null) {
            return;
        }
        int statTypeIndex = moreStatsScreen.statTypeDropdown.getSelectedIndex();
        if (!isTierListPage(statTypeIndex)) {
            if (tierExportHb != null) {
                tierExportHb.unhover();
                tierExportHb.clicked = false;
                tierExportHb.clickStarted = false;
            }
            return;
        }

        float screenX = Utils.getField(s, StatsScreen.class, "screenX");
        float scrollY = Utils.getField(s, StatsScreen.class, "scrollY");
        float labelY = scrollY + screenPosY(950);
        float dropdownY = labelY - screenPosY(40);
        float btnX = screenX + screenPosX(EXPORT_BUTTON_X_OFFSET);
        float btnY = dropdownY;
        float btnW = screenPosX(240);
        float btnH = screenPosY(44);

        if (tierExportHb == null) {
            tierExportHb = new Hitbox(btnX, btnY, btnW, btnH);
        }
        tierExportHb.width = btnW;
        tierExportHb.height = btnH;

        boolean pointerInside = InputHelper.mX > btnX && InputHelper.mX < btnX + btnW
                && InputHelper.mY > btnY && InputHelper.mY < btnY + btnH;

        if (isAnyDropdownOpen()) {
            tierExportHb.unhover();
            tierExportHb.clicked = false;
            tierExportHb.clickStarted = false;
            return;
        }

        // 两段式 update：
        // update(x, y) 只更新 hover；update() 才会根据 clickStarted/justReleasedClickLeft 置 clicked。
        tierExportHb.update(btnX, btnY);
        tierExportHb.update();
        boolean clicked = tierExportHb.clicked
                || (InputHelper.justReleasedClickLeft && pointerInside);
        if (clicked) {
            tierExportHb.clicked = false;
            tierExportHb.clickStarted = false;
            if (statTypeIndex == 8) {
                startMonsterTierExport();
            } else {
                startTierListExport(scopeForStatType(statTypeIndex));
            }
        }
    }

    private static boolean isAnyDropdownOpen() {
        if (moreStatsScreen == null) {
            return false;
        }
        return (moreStatsScreen.startDateDropdown != null && moreStatsScreen.startDateDropdown.isOpen)
                || (moreStatsScreen.endDateDropDown != null && moreStatsScreen.endDateDropDown.isOpen)
                || (moreStatsScreen.classDropdown != null && moreStatsScreen.classDropdown.isOpen)
                || (moreStatsScreen.statTypeDropdown != null && moreStatsScreen.statTypeDropdown.isOpen)
                || (moreStatsScreen.abandonDropdown != null && moreStatsScreen.abandonDropdown.isOpen)
                || (moreStatsScreen.seedFilterDropdown != null && moreStatsScreen.seedFilterDropdown.isOpen)
                || (moreStatsScreen.sampleSizeDropdown != null && moreStatsScreen.sampleSizeDropdown.isOpen);
    }

    private static void startTierListExport(TierListScope scope) {
        if (exportingTierList) {
            return;
        }
        exportingTierList = true;
        tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORTING_TIER_LIST);
        tierExportStatusUntil = Long.MAX_VALUE;
        tinycat.logger.info("TierList export started: " + (scope == null ? "all" : scope.key));
        try {
            String path = TierListExporter.export(moreStatsScreen, scope);
            tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORT_DONE);
            tinycat.logger.info("TierList export finished: " + path);
        } catch (Exception t) {
            tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORT_FAILED);
            tinycat.logger.error("TierList export failed", t);
        } finally {
            exportingTierList = false;
            tierExportStatusUntil = System.currentTimeMillis() + 20000L;
        }
    }

    private static void startMonsterTierExport() {
        if (exportingTierList) {
            return;
        }
        exportingTierList = true;
        tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORTING_MONSTER_TIER);
        tierExportStatusUntil = Long.MAX_VALUE;
        tinycat.logger.info("Monster tier export started");
        try {
            String path = MonsterTierExporter.export(moreStatsScreen);
            tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORT_DONE);
            tinycat.logger.info("Monster tier export finished: " + path);
        } catch (Exception t) {
            tierExportStatus = Localization.get(LocalizationKeys.UI_EXPORT_FAILED);
            tinycat.logger.error("Monster tier export failed", t);
        } finally {
            exportingTierList = false;
            tierExportStatusUntil = System.currentTimeMillis() + 20000L;
        }
    }

    public static void renderStatScreen(StatsScreen s, SpriteBatch sb) {
        float screenX = Utils.getField(s, StatsScreen.class, "screenX");
        float scrollY = Utils.getField(s, StatsScreen.class, "scrollY");
        boolean renderSampleSize = true;

        int statTypeIndex = moreStatsScreen.statTypeDropdown.getSelectedIndex();
        switch (statTypeIndex) {
            case 0: // Overall
                renderSampleSize = false;
                renderCharacterStats(s, sb, screenX);
                break;
            case 1: // Card pick rate act 1
                renderCardPickRate(s, sb, true, screenX);
                break;
            case 2: // Card pick rate after act 1
                renderCardPickRate(s, sb, false, screenX);
                break;
            case 3: // Card win rate
                renderCardWinRate(s, sb, screenX);
                break;
            case 4: // Neow bonus
                renderNeowBonus(s, sb, screenX);
                break;
            case 5: // Boss relic pick rate
                renderBossRelicPickRate(s, sb, screenX);
                break;
            case 6: // Boss relic win rate
                renderBossRelicWinRate(s, sb, screenX);
                break;
            case 7: // Encounter average HP loss (受到伤害)
                renderEncounterHPLoss(s, sb, screenX);
                break;
            case 8: // Encounter average combat HP loss (战损)
                renderEncounterCombatHPLoss(s, sb, screenX);
                break;
            case 9: // Encounter split HP loss (有/无回复战损)
                renderEncounterSplitHPLoss(s, sb, screenX);
                break;
            case 10: // Encounter mortality rate
                renderEncounterMortalityRate(s, sb, screenX);
                break;
            case 11: // Encounter average potions used
                renderEncounterPotionsUsed(s, sb, screenX);
                break;
            case 12: // Relic win rate
                renderRelicWinRate(s, sb, screenX);
                break;
            case 13: // Event win rate per act
                renderEventWinRate(s, sb, screenX);
                break;
            case 14: // Campfire Actions
                renderCampfireActions(s, sb, screenX);
                break;
            case 15: // Shop Actions
                renderShopActions(s, sb, screenX);
                break;
            case 16: // Campfire upgrade preference
                renderCampfireUpgradePref(s, sb, screenX);
                break;
        }

        float labelY = scrollY + screenPosY(950);
        float dropdownY = labelY - screenPosY(40);

        float xDiff = 170;
        float startDateX = screenX + screenPosX(-270);
        float endDateX = startDateX + screenPosX(xDiff);
        float characterX = endDateX + screenPosX(xDiff);
        float statTypeX = characterX + screenPosX(xDiff);
        float includeAbandonsX = statTypeX + screenPosX(375);
        float sampleSizeX = includeAbandonsX + screenPosX(xDiff * 2);
        float seedLabelY = labelY - screenPosY(80);
        float seedDropdownY = dropdownY - screenPosY(80);

        FontHelper.renderSmartText(sb, FontHelper.tipBodyFont, Localization.get(LocalizationKeys.UI_START_DATE), startDateX, labelY, Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb, FontHelper.tipBodyFont, Localization.get(LocalizationKeys.UI_END_DATE), endDateX, labelY, Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb, FontHelper.tipBodyFont, Localization.get(LocalizationKeys.UI_CHARACTER), characterX, labelY, Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb, FontHelper.tipBodyFont, Localization.get(LocalizationKeys.UI_STAT_TYPE), statTypeX, labelY, Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb,
                FontHelper.tipBodyFont,
                Localization.get(LocalizationKeys.UI_INCLUDE_ABANDONS),
                includeAbandonsX,
                labelY,
                Settings.CREAM_COLOR);
        FontHelper.renderSmartText(sb,
                FontHelper.tipBodyFont,
                Localization.get(LocalizationKeys.UI_SEED_FILTER),
                includeAbandonsX,
                seedLabelY,
                Settings.CREAM_COLOR);
        if (renderSampleSize) {
            FontHelper.renderSmartText(sb,
                    FontHelper.tipBodyFont,
                    Localization.get(LocalizationKeys.UI_MIN_SAMPLE_SIZE),
                    sampleSizeX,
                    labelY,
                    Settings.CREAM_COLOR);
        }

        if (isTierListPage(statTypeIndex)) {
            renderTierListExportButton(sb, screenX, dropdownY);
        }

        moreStatsScreen.startDateDropdown.render(sb, startDateX, dropdownY);
        moreStatsScreen.endDateDropDown.render(sb, endDateX, dropdownY);
        moreStatsScreen.classDropdown.render(sb, characterX, dropdownY);
        moreStatsScreen.statTypeDropdown.render(sb, statTypeX, dropdownY);
        moreStatsScreen.abandonDropdown.render(sb, includeAbandonsX, dropdownY);
        moreStatsScreen.seedFilterDropdown.render(sb, includeAbandonsX, seedDropdownY);
        if (renderSampleSize) {
            moreStatsScreen.sampleSizeDropdown.render(sb, sampleSizeX, dropdownY);
        }

        if (Settings.isControllerMode) {
            s.allCharsHb.move(300.0F * Settings.scale, scrollY + 600.0F * Settings.scale);
            s.ironcladHb.move(300.0F * Settings.scale, scrollY - 1600.0F * Settings.scale);
            if (s.silentHb != null) {
                s.silentHb.move(300.0F * Settings.scale, scrollY - 2000.0F * Settings.scale);
            }

            if (s.defectHb != null) {
                s.defectHb.move(300.0F * Settings.scale, scrollY - 2400.0F * Settings.scale);
            }

            if (s.watcherHb != null) {
                s.watcherHb.move(300.0F * Settings.scale, scrollY - 2800.0F * Settings.scale);
            }
        }
    }
}
