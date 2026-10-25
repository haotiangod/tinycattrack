package tinycat.tier;

import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.helpers.MonsterHelper;
import com.megacrit.cardcrawl.localization.MonsterStrings;

import java.util.HashMap;
import java.util.Map;

/**
 * 遭遇显示名：优先用 TinyCat 自带汉化包，其次游戏内置本地化，绝不机翻。
 *
 * 查找顺序：
 * 1. TinyCat 本地汉化包（location/chinese_english_mapping.json 的「怪物名称映射」段），
 *    例如 Hexaghost → 六火亡魂、Gremlin Nob → 红地精、3 Sentries → 三柱；
 * 2. localization/&lt;lang&gt;/monsters.json 的 NAME：遭遇名当怪物 ID 直查；
 * 3. 同上去掉空格再查（Jaw Worm → JawWorm）；
 * 4. 逐词首字母大写再查（Book of Stabbing → BookOfStabbing）；
 * 5. 遭遇名与怪物 ID 不一致时的少量别名；
 * 6. MonsterHelper.getEncounterName：组队遭遇的官方名（RunHistoryMonsterNames）；
 * 7. 仍取不到时返回英文遭遇 ID。
 *
 * ⚠️ 游戏语言包对查不到的 key 返回 "[MISSING_XXX]" 占位符而不是 null，
 * 所有取值都必须过 clean()，否则整条回退链会被占位符短路。
 */
public final class MonsterNames {
    /** 遭遇名 → 游戏内怪物 ID，只覆盖前两步查不到的条目。 */
    private static final Map<String, String> ALIASES = new HashMap<String, String>();
    private static final Map<String, String> CACHE = new HashMap<String, String>();
    /** 游戏语言包缺失条目时的占位符前缀，例如 "[MISSING_NAME]"。 */
    private static final String MISSING_MARK = "[MISSING_";

    static {
        ALIASES.put("2 Louse", "FuzzyLouseNormal");
        ALIASES.put("3 Louse", "FuzzyLouseDefensive");
        ALIASES.put("Blue Slaver", "SlaverBlue");
        ALIASES.put("Red Slaver", "SlaverRed");
        ALIASES.put("Slavers", "SlaverBoss");
        ALIASES.put("Book of Stabbing", "BookOfStabbing");
        ALIASES.put("2 Fungi Beasts", "FungiBeast");
        ALIASES.put("Collector", "TheCollector");
        ALIASES.put("Automaton", "BronzeAutomaton");
        ALIASES.put("3 Darklings", "Darkling");
        ALIASES.put("Spire Growth", "Serpent");
        ALIASES.put("Shell Parasite", "Shelled Parasite");
        ALIASES.put("The Heart", "CorruptHeart");
        ALIASES.put("Shield and Spear", "SpireShield");
        ALIASES.put("Chosen and Byrds", "Chosen");
        ALIASES.put("Cultist and Chosen", "Chosen");
    }

    private MonsterNames() {
    }

    public static String displayName(String encounterId) {
        if (encounterId == null || encounterId.isEmpty()) {
            return "";
        }
        String cached = CACHE.get(encounterId);
        if (cached != null) {
            return cached;
        }
        String name = resolve(encounterId);
        if (name == null || name.isEmpty()) {
            name = encounterId;
        }
        CACHE.put(encounterId, name);
        return name;
    }

    /**
     * 游戏语言包查不到条目时会返回 "[MISSING_XXX]" 占位符而不是 null，
     * 必须当成"没找到"，否则整条链路会被占位符短路（历史上就踩过这个坑）。
     */
    private static String clean(String name) {
        if (name == null) {
            return null;
        }
        String value = name.trim();
        if (value.isEmpty() || value.indexOf(MISSING_MARK) >= 0) {
            return null;
        }
        return value;
    }

    private static String resolve(String encounterId) {
        // 1) TinyCat 自己的本地汉化包（location/chinese_english_mapping.json 的「怪物名称映射」段）
        String local = localName(encounterId);
        if (local != null) {
            return local;
        }
        String name = monsterName(encounterId);
        if (name != null) {
            return name;
        }
        name = monsterName(encounterId.replace(" ", ""));
        if (name != null) {
            return name;
        }
        // 逐词首字母大写（Book of Stabbing → BookOfStabbing，官方怪物 ID 就是这种写法）
        name = monsterName(camel(encounterId));
        if (name != null) {
            return name;
        }
        String alias = ALIASES.get(encounterId);
        if (alias != null) {
            name = monsterName(alias);
            if (name != null) {
                return name;
            }
        }
        return encounterName(encounterId);
    }

    /**
     * 查 TinyCat 自带的汉化包（卡牌/遗物/术语/怪物名称映射）。
     * 命中返回中文，未命中返回 null；查不到时 toChinese 会原样返回输入，这里用等值判断排除。
     */
    private static String localName(String encounterId) {
        try {
            tinycat.NameMapper.initialize();
            String zh = tinycat.NameMapper.toChinese(encounterId);
            String cleaned = clean(zh);
            if (cleaned != null && !cleaned.equals(encounterId)) {
                return cleaned;
            }
        } catch (Throwable ignored) {
            // 汉化包未加载时直接走游戏语言包
        }
        return null;
    }

    /** 逐词首字母大写并拼在一起：Book of Stabbing → BookOfStabbing。 */
    private static String camel(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (String part : value.split(" ")) {
            if (part.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                out.append(part.substring(1));
            }
        }
        return out.toString();
    }

    /** 取单个怪物的官方名；取不到返回 null。 */
    private static String monsterName(String monsterId) {
        if (monsterId == null || monsterId.isEmpty()) {
            return null;
        }
        try {
            MonsterStrings strings = CardCrawlGame.languagePack.getMonsterStrings(monsterId);
            if (strings != null) {
                return clean(strings.NAME);
            }
        } catch (Throwable ignored) {
            // 语言包未初始化或该 ID 没有本地化条目
        }
        return null;
    }

    /** 取组队遭遇的官方名；取不到返回 null。 */
    private static String encounterName(String encounterId) {
        try {
            return clean(MonsterHelper.getEncounterName(encounterId));
        } catch (Throwable ignored) {
            // 语言包未初始化
        }
        return null;
    }
}
