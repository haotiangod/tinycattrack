package tinycat.tier;

import java.util.HashMap;
import java.util.Map;

/**
 * 尼奥（开局）选项的文本概况：短名、全名、ASCII 短码都只在这里维护。
 *
 * 一个选项 = 奖励 + 代价，两者不合并、不丢键：PERCENT_DAMAGE + REMOVE_TWO 和
 * TEN_PERCENT_HP_LOSS + REMOVE_TWO 是两行（掉血删2 / 血上限删2），不会缩成「删2」。
 *
 * 短名规则（与用户确认过）：代价在前、奖励在后，直接相连不插分隔符；
 * 奖励以「金」结尾时去掉「金」，避免「诅咒250金」这种重复：
 * - 诅咒 + 250金   → 诅咒250
 * - 掉血 + 250金   → 掉血250
 * - 血上限 + 250金 → 血上限250
 * - 掉钱 + 250金   → 掉钱250
 * - 掉血 + 删2     → 掉血删2
 *
 * fullZh 是全句（用于 CSV/Excel 明细）；en 是像素占位图用的 ASCII 短码。
 */
public final class NeowShort {
    private static final Map<String, String> BONUS_SHORT_ZH = new HashMap<String, String>();
    private static final Map<String, String> COST_SHORT_ZH = new HashMap<String, String>();
    private static final Map<String, String> BONUS_FULL_ZH = new HashMap<String, String>();
    private static final Map<String, String> COST_FULL_ZH = new HashMap<String, String>();
    private static final Map<String, String> BONUS_EN = new HashMap<String, String>();
    private static final Map<String, String> COST_EN = new HashMap<String, String>();

    static {
        bonus("THREE_CARDS", "三选一", "从三张卡牌中选择一张。", "3CARDS");
        bonus("RANDOM_COLORLESS", "无色罕见", "选择一张无色罕见卡牌获得。", "CLRLS-U");
        bonus("RANDOM_COLORLESS_2", "稀有无色", "选择一张稀有无色卡牌获得。", "CLRLS-R");
        bonus("RANDOM_COLORLESS_2_ACTUAL", "稀有无色", "选择一张稀有无色卡牌获得。", "CLRLS-R");
        bonus("RANDOM_COMMON_RELIC", "普通遗物", "获得一个随机普通遗物。", "C.RELIC");
        bonus("ONE_RARE_RELIC", "稀有遗物", "获得一个随机稀有遗物。", "R.RELIC");
        bonus("REMOVE_CARD", "删1", "移除一张卡牌。", "RM1");
        bonus("REMOVE_TWO", "删2", "移除两张卡牌。", "RM2");
        bonus("TRANSFORM_CARD", "变1", "变化一张卡牌。", "TR1");
        bonus("TRANSFORM_TWO_CARDS", "变2", "变化两张卡牌。", "TR2");
        bonus("UPGRADE_CARD", "升级", "升级一张卡牌。", "UPG");
        bonus("THREE_ENEMY_KILL", "1血三战", "前三场战斗中的敌人只有 1 点生命。", "1HPx3");
        bonus("THREE_SMALL_POTIONS", "3药水", "获得 3 瓶随机药水。", "3POT");
        bonus("TEN_PERCENT_HP_BONUS", "血上限+10%", "获得 10% 最大生命值。", "HP+10");
        bonus("TWENTY_PERCENT_HP_BONUS", "血上限+20%", "获得 20% 最大生命值。", "HP+20");
        bonus("HUNDRED_GOLD", "100金", "获得 100 金币。", "100G");
        bonus("TWO_FIFTY_GOLD", "250金", "获得 250 金币。", "250G");
        bonus("ONE_RANDOM_RARE_CARD", "稀有卡", "获得一张随机稀有卡牌。", "R.CARD");
        bonus("THREE_RARE_CARDS", "三稀有选一", "从三张稀有卡牌中选择一张。", "3RARE");
        bonus("BOSS_RELIC", "换BOSS遗物", "失去初始遗物，获得一个随机 Boss 遗物。", "BOSS");

        cost("CURSE", "诅咒", "获得一个诅咒。", "CURSE");
        cost("NO_GOLD", "掉钱", "失去所有金币。", "NOGOLD");
        cost("TEN_PERCENT_HP_LOSS", "血上限", "失去 10% 最大生命值。", "HP-10");
        cost("PERCENT_DAMAGE", "掉血", "受到伤害。", "DMG");
        cost("TWO_STARTER_CARDS", "少2初始牌", "增加两张初始卡牌。", "2STR");
        cost("ONE_STARTER_CARD", "少1初始牌", "增加一张初始卡牌。", "1STR");
    }

    private NeowShort() {
    }

    private static void bonus(String key, String shortZh, String fullZh, String en) {
        BONUS_SHORT_ZH.put(key, shortZh);
        BONUS_FULL_ZH.put(key, fullZh);
        BONUS_EN.put(key, en);
    }

    private static void cost(String key, String shortZh, String fullZh, String en) {
        COST_SHORT_ZH.put(key, shortZh);
        COST_FULL_ZH.put(key, fullZh);
        COST_EN.put(key, en);
    }

    /** 中文短名：代价 + 奖励，直接相连。无代价时只有奖励。未知键原样返回。 */
    public static String zh(String bonusKey, String costKey) {
        String reward = text(BONUS_SHORT_ZH, bonusKey);
        String penalty = costText(COST_SHORT_ZH, costKey);
        if (penalty.isEmpty()) {
            return reward;
        }
        if (reward.endsWith("金")) {
            reward = reward.substring(0, reward.length() - 1);
        }
        return penalty + reward;
    }

    /** 中文全名：奖励全句，有代价时在括号里补上代价。 */
    public static String fullZh(String bonusKey, String costKey) {
        String reward = text(BONUS_FULL_ZH, bonusKey);
        String penalty = costText(COST_FULL_ZH, costKey);
        return penalty.isEmpty() ? reward : reward + "（代价：" + penalty + "）";
    }

    /** ASCII 短码，用于像素占位图。 */
    public static String en(String bonusKey, String costKey) {
        String reward = text(BONUS_EN, bonusKey);
        String penalty = costText(COST_EN, costKey);
        return penalty.isEmpty() ? reward : penalty + "+" + reward;
    }

    private static String text(Map<String, String> table, String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String text = table.get(key);
        return text == null ? key : text;
    }

    private static String costText(Map<String, String> table, String costKey) {
        if (costKey == null || "NONE".equals(costKey) || costKey.isEmpty()) {
            return "";
        }
        String text = table.get(costKey);
        return text == null ? costKey : text;
    }
}
