package tinycat;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 单卡名称归一化。
 *
 * 历史上 TinyCat 有两套重复映射：
 * - stats/Card.nameReplacementMap
 * - Utils.CARD_NAME_REPLACEMENTS
 *
 * 两套内容不完全一致，导致同一个旧 ID 在卡牌抓率页能映射、在火堆/商店页却不能。
 * 现在统一到这里，两个入口都调用 normalize()。
 *
 * 这些旧 ID 不是“多余映射”：实际 .run 里仍然大量出现
 * Conserve Battery / Gash / Redo / Steam / Lockon / Ghostly 等。
 */
public final class CardNameNormalizer {
    private static final Map<String, String> REPLACEMENTS;

    static {
        Map<String, String> map = new HashMap<String, String>();
        map.put("Lockon", "Bullseye");
        map.put("Steam", "Steam Barrier");
        map.put("Steam Power", "Overclock");
        map.put("Redo", "Recursion");
        map.put("Undo", "Equilibrium");
        map.put("Gash", "Claw");
        map.put("Venomology", "Alchemize");
        map.put("Night Terror", "Nightmare");
        map.put("NightTerror", "Nightmare");
        map.put("Crippling Poison", "Crippling Cloud");
        map.put("CripplingPoison", "Crippling Cloud");
        map.put("Underhanded Strike", "Sneaky Strike");
        map.put("UnderhandedStrike", "Sneaky Strike");
        map.put("Clear The Mind", "Tranquility");
        map.put("ClearTheMind", "Tranquility");
        map.put("Wireheading", "Foresight");
        map.put("Vengeance", "Simmering Fury");
        map.put("Adaptation", "Rushdown");
        map.put("Path to Victory", "Pressure Points");
        map.put("PathToVictory", "Pressure Points");
        map.put("Ghostly", "Apparition");
        map.put("Wraith Form v2", "Wraith Form");
        map.put("Fasting2", "Fasting");
        map.put("Conserve Battery", "Charge Battery");
        // 之前只存在于 Utils 的条目，统一保留，避免火堆/商店口径丢失
        map.put("Yang", "Duality");
        map.put("Snake Skull", "Snecko Skull");
        REPLACEMENTS = Collections.unmodifiableMap(map);
    }

    private CardNameNormalizer() {
    }

    /**
     * 对单个基础名做旧 ID -> 当前 ID 映射；找不到就原样返回。
     * 不负责去掉 "+1" 后缀，调用方自己处理升级后缀。
     */
    public static String normalize(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        String mapped = REPLACEMENTS.get(name);
        return mapped == null ? name : mapped;
    }
}
