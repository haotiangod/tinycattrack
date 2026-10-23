package tinycat.stats;

import tinycat.tinycat;
import tinycat.YearMonth;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.google.gson.JsonSyntaxException;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.screens.stats.*;
import org.apache.commons.lang3.tuple.Pair;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static basemod.BaseMod.gson;

public class Run implements Comparable<Run> {
    final RunData runData;  // 改为包访问权限

    /** 存档目录名，用于匹配 TinyCat 状态文件（如 "1_IRONCLAD"） */
    private final String runDir;

    public int portalFloor = -1;
    /** 实际击破每幕 Boss 的最后一层；没有该幕 Boss 数据时为 -1。 */
    public int act1BossFloor = -1;
    public int act2BossFloor = -1;
    public int act3BossFloor = -1;

    /** TinyCat 自行记录的状态；-1 表示没有可用记录。 */
    private int trackedFloor0HP = -1;
    private int trackedGoldGained = -1;

    public final AbstractPlayer.PlayerClass playerClass;

    public final boolean isHeartKill;
    public final boolean isA20;
    public final boolean abandoned;
    public final boolean seeded;
    public final long timestamp;
    public final String killedBy;
    public final int floorsReached;
    public final int playtime;
    public final int score;
    public final int gold;
    /** true 表示 gold 是用 gold_per_floor 估算的累计获得金币，不是逐事件精确值。 */
    public boolean goldEstimated = true;
    /** true 表示第 1 层战损使用 damage-6/估算，不是精确起始 HP。 */
    public boolean floor0HPEstimated = false;
    public final int maxHP;
    public final int potionsCreated;
    public final int lessonsLearned;
    public final int maxDagger;
    public final int maxAlgo;
    public final int maxSearingBlow;

    public final int enemiesKilled;
    public final int bossesKilled;
    public final Neow neowPicked;
    public final List<Neow> neowSkipped = new ArrayList<>();
    public final List<String> relicsPurchased;

    public final List<CardChoice> cardChoices = new ArrayList<>();
    public final List<Card> masterDeck;
    public final BossRelic bossSwapRelic;
    public final List<BossRelicChoiceStats> bossRelicChoiceStats;
    public final List<EncounterStats> encounterStats = new ArrayList<>();
    public final List<String> relics;
    public final List<EventStats> eventStats;
    public final List<String> itemsPurged;  // 删除的卡牌列表
    public final List<Integer> itemsPurgedFloors;  // 删牌发生的楼层
    public final List<CampfireChoice> campfireChoices;
    public final int purchasedPurges;  // 
    public final List<String> itemsPurchased;  // 商店购买的物品列表
    public final List<Integer> itemPurchaseFloors;  // 购买发生的楼层
    public final List<ShopContent> shopContents;  // 遇到的所有商店内容
    public final NeowBonusLog neowBonusLog;  // 开局选择详情

    private Run(RunData runData, AbstractPlayer.PlayerClass playerClass, String runDir) {
        this.runData = runData;
        this.runDir = runDir;
        this.playerClass = playerClass;
        this.isHeartKill = isHeartKill();
        this.isA20 = runData.is_ascension_mode && runData.ascension_level == 20;
        this.timestamp = Long.parseLong(runData.timestamp);

        boolean abandoned = false;
        if (runData.killed_by == null || runData.killed_by.isEmpty()) {
            if (!runData.victory) {
                abandoned = true;
            }
        }
        this.abandoned = abandoned;
        this.seeded = runData.chose_seed;

        masterDeck = runData.master_deck.stream().map(Card::fromString).collect(Collectors.toList());
        killedBy = EncounterStats.getNameReplacementMap().getOrDefault(runData.killed_by, runData.killed_by);
        floorsReached = runData.floor_reached;
        relics = runData.relics;
        playtime = runData.playtime;
        eventStats = runData.event_choices;
        score = runData.score;
        itemsPurged = runData.items_purged;
        itemsPurgedFloors = runData.items_purged_floors;
        campfireChoices = runData.campfire_choices;
        purchasedPurges = runData.purchased_purges;
        itemsPurchased = runData.items_purchased;
        itemPurchaseFloors = runData.item_purchase_floors;
        shopContents = runData.shop_contents;
        neowBonusLog = runData.neow_bonus_log;

        Pair<Integer, Integer> enemiesKilled = enemiesKilled();
        this.enemiesKilled = enemiesKilled.getLeft();
        this.bossesKilled = enemiesKilled.getRight();

        for (EventStats s : runData.event_choices) {
            if (s.event_name.equals("SecretPortal") && s.player_choice.equals("Took Portal")) {
                portalFloor = s.floor;
            }

            if (s.event_name.equals("N'loth") && s.relics_lost != null) {
                s.relics_lost.stream().filter(Objects::nonNull).filter(x -> !x.isEmpty()).forEach(relics::add);
            }
        }

        relicsPurchased = runData.items_purchased.stream().filter(relics::contains).collect(Collectors.toList());

        int singingBowlFloor = -1;
        for (ObtainStats r : runData.relics_obtained) {
            int floor = r.floor;
            String key = r.key;
            if (key.equals("Singing Bowl")) {
                singingBowlFloor = floor;
            }
        }

        if (runData.neow_bonus != null && !runData.neow_bonus.isEmpty()) {
            this.neowPicked = new Neow(runData.neow_bonus, runData.neow_cost);
        } else {
            this.neowPicked = null;
        }
        for (int i = 0; i < runData.neow_bonuses_skipped_log.size(); i++) {
            this.neowSkipped.add(new Neow(runData.neow_bonuses_skipped_log.get(i),
                    runData.neow_costs_skipped_log.get(i)));
        }

        bossSwapRelic = this.bossSwapRelic();
        bossRelicChoiceStats = runData.boss_relics;
        readTrackedState();
        captureBossFloors();

        Map<Integer, Integer> potByFloor = new HashMap<>();
        for (int i = 0; i < runData.potion_use_per_floor.size(); ++i) {
            int floor = i + 1;
            int potionsUsed = Math.min(runData.potion_use_per_floor.get(i).size(), 9);
            potByFloor.put(floor, potionsUsed);
        }

        // 获取第0层HP（角色起始满血，第0层MaxHP≈第1层MaxHP）
        // current_hp_per_floor[0] 对应第1层，不包含第0层数据
        int floor0HP = getFloor0HP();

        for (BattleStats bs : runData.damage_taken) {
            int potsUsed = potByFloor.getOrDefault(bs.floor, -1);
            // 计算实际战损：进入本层前HP - 本层结束HP
            // current_hp_per_floor[0] = 第1层结束HP, [1] = 第2层结束HP, ...
            // 公式：hpBefore(前一层结束HP) - hpAfter(本层结束HP)
            // 正值=生命减少（战损），负值=生命增加（回血），保留负值不归0
            int actualHPLoss = 0;
            if (runData.current_hp_per_floor != null && !runData.current_hp_per_floor.isEmpty()) {
                int floor = bs.floor;
                int hpAfter;  // 本层结束HP
                int hpBefore; // 前一层结束HP（进入本层前HP）

                if (floor == 1) {
                    // 第1层：前一层为第0层，使用floor0HP
                    hpBefore = floor0HP;
                    hpAfter = runData.current_hp_per_floor.get(0);
                } else if (floor >= 2 && floor - 1 < runData.current_hp_per_floor.size()) {
                    hpBefore = runData.current_hp_per_floor.get(floor - 2);
                    hpAfter = runData.current_hp_per_floor.get(floor - 1);
                } else {
                    hpAfter = hpBefore = 0;
                }
                actualHPLoss = hpBefore - hpAfter;
            }
            EncounterStats e = new EncounterStats(bs.floor, bs.enemies, bs.damage, bs.turns, potsUsed, actualHPLoss);
            encounterStats.add(e);
        }
        gold = parseGold();
        maxHP = getMaxHP();
        potionsCreated = getPotionsCreated();
        lessonsLearned = getLessonsLearned();
        maxSearingBlow = parseSearingBlow();

        AtomicInteger maxDagger = new AtomicInteger();
        AtomicInteger maxAlgo = new AtomicInteger();
        runData.improvable_cards.forEach((n, cs) -> {
            String name = n.toLowerCase();
            if (name.contains("ritualdagger")) {
                cs.forEach(c -> {
                    maxDagger.set(Math.max(maxDagger.get(), c));
                });
            } else if (name.contains("genetic algorithm")) {
                cs.forEach(c -> {
                    maxAlgo.set(Math.max(maxAlgo.get(), c));
                });
            }
        });
        this.maxDagger = maxDagger.get();
        this.maxAlgo = maxAlgo.get();

        for (CardChoiceStats c : runData.card_choices) {
            Card picked = Card.fromStringIgnoreUpgrades(c.picked);
            List<Card>
                    notPicked =
                    c.not_picked.stream().map(Card::fromStringIgnoreUpgrades).collect(Collectors.toList());
            if (!picked.name.equals("SKIP") && !picked.name.equals("Singing Bowl")) {
                if (-1 < singingBowlFloor && singingBowlFloor <= c.floor) {
                    notPicked.add(Card.SingingBowl());
                } else {
                    notPicked.add(Card.SKIP());
                }
            }
            cardChoices.add(new CardChoice(picked, notPicked, c.floor));
        }
    }

    private static List<String> bannedCards = Arrays.asList("pride", "omega");
    private static List<String> bannedRelics = Arrays.asList("bargainbundle", "pet ghost", "replayfunnel");
    private static List<String>
            bannedNeows =
            Arrays.asList("CHOOSE_OTHER_CHAR_RANDOM_RARE_CARD",
                    "SWAP_MEMBERSHIP_COURIER",
                    "CHOOSE_OTHER_CHAR_RANDOM_UNCOMMON_CARD",
                    "CHOOSE_OTHER_CHAR_RANDOM_COMMON_CARD",
                    "GAIN_POTION_SLOT",
                    "GAIN_TWO_POTION_SLOTS",
                    "GAIN_TWO_RANDOM_COMMON_RELICS",
                    "GAIN_UNCOMMON_RELIC"
            );
    private static List<String> bannedBossRelics = Arrays.asList(
            "Honey Jar",
            "Snecko Heart",
            "Unceasing Top",
            "Anchor",
            "Bag of Preparation",
            "Abe's Treasure",
            "TungstenRod",
            "ChewingGum",
            "Letter Opener",
            "Pocketwatch",
            "Calipers",
            "FaceOfCleric",
            "NeowsBlessing",
            "Smiling Mask",
            "Centennial Puzzle",
            "Frozen Egg 2"
    );

    private static List<String> bannedEnemies = Arrays.asList(
            "Flame Bruiser (One Orb)",
            "Flame Bruiser (Two Orb)",
            "Flame Bruiser 2 Orb",
            "Flame Bruiser 1 Orb",
            "SlaverBoss",
            "Louse",
            "Slime"
    );

    private static List<String> bannedAct1Encounters = Arrays.asList(
            "Book of Stabbing",
            "GiantHead",
            "Reptomancer",
            "Champ",
            "BookOfStabbing",
            "City Looters",
            "Colosseum Nobs",
            "Colosseum Slavers",
            "Darkling Encounter",
            "Double Orb Walker",
            "Flame Bruiser (Two Orb)",
            "HealerTank",
            "Murder of Cultists",
            "Nemesis",
            "Shell Parasite",
            "Shelled Parasite",
            "SlaverBoss",
            "SnakePlant",
            "Snecko",
            "The Heart",
            "Time Eater",
            "Shield and Spear",
            "3 Darklings",
            "3 Byrds",
            "Jaw Worm Horde",
            "Collector",
            "Cultist and Chosen",
            "Gremlin Leader"
    );

    private static List<String> bannedAct2Encounters = Arrays.asList(
            "Maw",
            "Slime Boss"
    );

    private static List<String> bannedAct3Encounters = Arrays.asList(
            "Snake Plant",
            "The Heart"
    );

    private static List<String> bannedAct4Encounters = Arrays.asList(
            "2 Orb Walkers"
    );


    public boolean valid() {
        boolean
                valid =
                !runData.is_special_run && !runData.is_daily && !runData.is_endless && isA20;
        if (!valid) {
            return false;
        }

        for (Card c : this.masterDeck) {
            if (c.name.contains(":")) {
                return false;
            }
            if (bannedCards.contains(c.name.toLowerCase())) {
                return false;
            }
        }

        for (String r : this.relics) {
            if (r.contains(":")) {
                return false;
            }
            if (bannedRelics.contains(r.toLowerCase())) {
                return false;
            }
        }

        for (BossRelicChoiceStats b : bossRelicChoiceStats) {
            if (b == null) {
                continue;
            }
            if (b.picked != null && b.picked.contains(":")) {
                return false;
            }
            if (b.picked != null && bannedBossRelics.contains(b.picked)) {
                return false;
            }
            if (b.not_picked == null) {
                return false;
            }
            for (String s : b.not_picked) {
                if (s != null && s.contains(":")) {
                    return false;
                }
                if (s != null && bannedBossRelics.contains(s)) {
                    return false;
                }
            }
        }

        if (bossSwapRelic != null && bannedBossRelics.contains(bossSwapRelic.name)) {
            return false;
        }

        if (neowPicked == null) {
            return false;
        }

        if (bannedNeows.contains(neowPicked.bonus)) {
            return false;
        }

        for (Neow n : neowSkipped) {
            if (bannedNeows.contains(n.bonus)) {
                return false;
            }
        }

        for (EncounterStats e : encounterStats) {
            if (e == null || e.enemies == null) {
                return false;
            }
            if (e.enemies.contains(":")) {
                return false;
            }
            if (bannedEnemies.contains(e.enemies)) {
                return false;
            }
            int act = getAct(e.floor);
            if (act == 1 && bannedAct1Encounters.contains(e.enemies)) {
                return false;
            }
            if (act == 2 && bannedAct2Encounters.contains(e.enemies)) {
                return false;
            }
            if (act == 3 && bannedAct3Encounters.contains(e.enemies)) {
                return false;
            }
            if (act == 4 && bannedAct4Encounters.contains(e.enemies)) {
                return false;
            }
        }

        return true;
    }

    private int parseGold() {
        if (trackedGoldGained >= 0) {
            goldEstimated = false;
            return trackedGoldGained;
        }
        goldEstimated = true;

        // 回退到 gold_per_floor 公式估算 (条件公式 vF)
        // 按 neow_bonus 分组使用不同扣除常数，最小化 RMSE(sqrt(E^2+S^2))
        // 条件1: NO_GOLD → 不扣开局金币
        // 条件2: 其他 → 按 neow_bonus 扣对应常数
        // 条件3: 有 MawBank → +12
        if (runData.gold_per_floor == null || runData.gold_per_floor.isEmpty()) {
            return 0;
        }
        int defaultStart;
        if ("NO_GOLD".equals(runData.neow_cost)) {
            defaultStart = 0;
        } else {
            defaultStart = getNeowBonusDeduction(runData.neow_bonus);
        }
        int totalGold = Math.max(0, runData.gold_per_floor.get(0) - defaultStart);
        for (int i = 1; i < runData.gold_per_floor.size(); i++) {
            int diff = runData.gold_per_floor.get(i) - runData.gold_per_floor.get(i - 1);
            if (diff > 0) {
                totalGold += diff;
            }
        }
        if (runData.relics != null && runData.relics.contains("MawBank")) {
            totalGold += 12;
        }
        return totalGold;
    }

    /** 按 neow_bonus 返回最优扣除常数 (最小化 RMSE) */
    private static int getNeowBonusDeduction(String neowBonus) {
        if (neowBonus == null) return 45;
        switch (neowBonus) {
            case "TWO_FIFTY_GOLD":       return 55;
            case "HUNDRED_GOLD":         return 40;
            case "RANDOM_COMMON_RELIC":  return 35;
            case "REMOVE_CARD":          return 40;
            case "ONE_RARE_RELIC":       return 45;
            case "REMOVE_TWO":           return 40;
            case "TRANSFORM_TWO_CARDS":  return 55;
            case "TRANSFORM_CARD":       return 60;
            case "UPGRADE_CARD":         return 60;
            case "THREE_SMALL_POTIONS":  return 45;
            case "RANDOM_COLORLESS_2":   return 60;
            case "THREE_RARE_CARDS":     return 45;
            case "THREE_ENEMY_KILL":     return 40;
            default:                     return 45;
        }
    }

    private void captureBossFloors() {
        int act1 = -1;
        int act2 = -1;
        int act3 = -1;
        for (BattleStats bs : runData.damage_taken) {
            if (bs.enemies == null) {
                continue;
            }
            String name = EncounterStats.getNameReplacementMap().getOrDefault(bs.enemies, bs.enemies);
            switch (name) {
                case "Hexaghost":
                case "Slime Boss":
                case "The Guardian":
                    if (act1 < 0 || bs.floor < act1) {
                        act1 = bs.floor;
                    }
                    break;
                case "Collector":
                case "Automaton":
                case "Champ":
                    if (act2 < 0 || bs.floor < act2) {
                        act2 = bs.floor;
                    }
                    break;
                case "Time Eater":
                case "Awakened One":
                case "Donu and Deca":
                    act3 = Math.max(act3, bs.floor);
                    break;
                default:
                    break;
            }
        }
        act1BossFloor = act1;
        act2BossFloor = act2;
        act3BossFloor = act3;
    }

    /**
     * 读取 runs/{runDir}/{start}.tinycat（由 TinyCatTrackerTest 测试模组写入）。
     * 文件按“最后一次更新时间”与本局结束时间最接近来匹配，
     * 这样中途退出再继续的对局也能对上同一条记录。
     */
    private void readTrackedState() {
        if (runDir == null || runData.timestamp == null) {
            return;
        }
        long endTimestamp;
        try {
            endTimestamp = Long.parseLong(runData.timestamp);
        } catch (NumberFormatException e) {
            return;
        }

        FileHandle directory = Gdx.files.local("runs/" + runDir + "/");
        if (!directory.exists() || !directory.isDirectory()) {
            return;
        }

        Map<String, String> best = null;
        long bestDiff = Long.MAX_VALUE;
        for (FileHandle file : directory.list()) {
            if (!file.name().endsWith(".tinycat")) {
                continue;
            }
            Map<String, String> state;
            try {
                state = parseTrackedFile(file);
            } catch (Exception e) {
                continue;
            }
            long updated = parseLong(state.get("updated_ts"), -1);
            long start = parseLong(state.get("start_ts"), -1);
            if (updated < 0 || start < 0 || start > endTimestamp) {
                continue;
            }
            long diff = Math.abs(endTimestamp - updated);
            if (diff > 6L * 60L * 60L || diff >= bestDiff) {
                continue;
            }
            bestDiff = diff;
            best = state;
        }
        if (best == null) {
            return;
        }
        trackedFloor0HP = parseInt(best.get("floor0_hp"), -1);
        trackedGoldGained = parseInt(best.get("gold_gained"), -1);
    }

    private static Map<String, String> parseTrackedFile(FileHandle file) {
        Map<String, String> state = new HashMap<>();
        for (String line : file.readString().split("\\r?\\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            state.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
        }
        return state;
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

    private int getMaxHP() {
        if (runData.max_hp_per_floor.isEmpty()) {
            return 0;
        }
        return runData.max_hp_per_floor.get(runData.max_hp_per_floor.size() - 1);
    }

    /**
     * 第 1 层开始前的 HP（Neow 结束后）。
     * 优先使用 TinyCatTrackerTest 写的 .tinycat 记录，缺失时按第 1 层受到伤害反推。
     */
    private int getFloor0HP() {
        if (trackedFloor0HP > 0) {
            return trackedFloor0HP;
        }

        floor0HPEstimated = true;
        if (runData.current_hp_per_floor != null && !runData.current_hp_per_floor.isEmpty()) {
            int floor1HP = runData.current_hp_per_floor.get(0);
            int floor1Damage = 0;
            if (runData.damage_taken != null) {
                for (BattleStats bs : runData.damage_taken) {
                    if (bs.floor == 1) {
                        floor1Damage = bs.damage;
                        break;
                    }
                }
            }

            if (playerClass == AbstractPlayer.PlayerClass.IRONCLAD) {
                return floor1HP + floor1Damage - 6;
            } else {
                return floor1HP + floor1Damage;
            }
        }

        if (runData.max_hp_per_floor != null && !runData.max_hp_per_floor.isEmpty()) {
            return runData.max_hp_per_floor.get(0);
        }
        return 70;
    }

    private int getPotionsCreated() {
        int p = 0;
        for (List<String> l : runData.potions_obtained_alchemize) {
            p += l.size();
        }
        return p;
    }

    private int getLessonsLearned() {
        int c = 0;
        for (List<String> l : runData.lesson_learned_per_floor) {
            c += l.size();
        }
        return c;
    }

    private int parseSearingBlow() {
        int m = 0;
        for (Card card : masterDeck) {
            if (card.name.equals("Searing Blow")) {
                try {
                    m = Math.max(m, card.upgrades);
                } catch (Exception e) {
                    tinycat.logger.warn("Failed to parse Searing Blow upgrade count from master deck: " + card);
                }
            }
        }
        return m;
    }

    private boolean isHeartKill() {
        if (!runData.victory) {
            return false;
        }
        for (int i = runData.damage_taken.size() - 1; i >= 0; --i) {
            BattleStats bs = runData.damage_taken.get(i);
            if (bs.enemies != null && bs.enemies.equals("The Heart")) {
                return true;
            }
        }
        return false;
    }


    private Pair<Integer, Integer> enemiesKilled() {
        int enemiesKilled = 0;
        int bossesKilled = 0;

        // 支持中英文客户端: "Enemies Slain" / "杀死的敌人", "Bosses Slain" / "打败Boss"
        Pattern enemies = Pattern.compile("(?:Enemies Slain|杀死的敌人) \\((\\d+)\\):.*");
        Pattern bosses = Pattern.compile("(?:Bosses Slain|打败Boss) \\((\\d+)\\):.*");

        for (String s : runData.score_breakdown) {
            Matcher e = enemies.matcher(s);
            Matcher b = bosses.matcher(s);
            if (e.matches()) {
                enemiesKilled = Integer.parseInt(e.group(1));
            } else if (b.matches()) {
                bossesKilled = Integer.parseInt(b.group(1));
            }
        }

        return Pair.of(enemiesKilled, bossesKilled);
    }

    private BossRelic bossSwapRelic() {
        String nlothsGift = "Nloth's Gift";
        String nloth = "N'loth";
        String s = "";

        if (neowPicked != null && Objects.equals(neowPicked.bonus, "BOSS_RELIC")) {
            if (!runData.relics.isEmpty()) {
                s = runData.relics.get(0);
            }
            if (s.equals(nlothsGift)) {
                for (EventStats e : runData.event_choices) {
                    if (e.event_name.equals(nloth)) {
                        s = e.relics_lost.stream().findFirst().orElse("");
                    }
                }
            }
        }

        if (!s.isEmpty()) {
            return new BossRelic(s, 0);
        }
        return null;
    }

    public String toJSON() {
        return gson.toJson(this);
    }

    public static Optional<Run> fromFile(FileHandle file) {
        if (file == null) {
            return Optional.empty();
        }
        if (file.isDirectory()) {
            return Optional.empty();
        }
        RunData data;
        AbstractPlayer.PlayerClass playerClass;
        String path = file.path() == null ? "" : file.path();

        try {
            data = gson.fromJson(readRunText(file), RunData.class);
        } catch (Exception e) {
            tinycat.logger.info("Failed to load RunData from JSON file: " + path);
            return Optional.empty();
        }

        try {
            playerClass = AbstractPlayer.PlayerClass.valueOf(data.character_chosen);
        } catch (NullPointerException | IllegalArgumentException e) {
            tinycat.logger.info("Run file " +
                    path +
                    " does not use a real character: " +
                    data.character_chosen);
            return Optional.empty();
        }

        if (data.timestamp == null) {
            data.timestamp = file.nameWithoutExtension();
            String exampleDaysSinceUnixStr = "17586";
            boolean assumeDaysSinceUnix = data.timestamp.length() == exampleDaysSinceUnixStr.length();
            if (assumeDaysSinceUnix) {
                try {
                    long secondsInDay = 86400L;
                    long days = Long.parseLong(data.timestamp);
                    data.timestamp = Long.toString(days * secondsInDay);
                } catch (NumberFormatException e) {
                    tinycat.logger.info("Run file " +
                            file.path() +
                            " name is could not be parsed into a Timestamp.");
                    return Optional.empty();
                }
            }
        }

        return Optional.of(new Run(data, playerClass, file.parent().name()));
    }

    /**
     * RunHistoryPlus 写出的 .run 文件在中文 Windows 上是 GBK 编码，
     * 但旧代码用 FileHandle.readString() 依赖系统默认字符集。
     * 这里用严格解码先试 UTF-8，再试 GBK，避免换 locale 后整批 run 解析失败。
     */
    private static String readRunText(FileHandle file) {
        byte[] bytes = file.readBytes();
        String text = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (text == null) {
            text = decodeStrict(bytes, Charset.forName("GBK"));
        }
        if (text == null) {
            text = new String(bytes, Charset.defaultCharset());
        }
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') {
            text = text.substring(1);
        }
        return text;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        try {
            return charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    public boolean isInRange(YearMonth startDate, YearMonth endDate) {
        YearMonth date = YearMonth.fromDate(new Date(Long.parseLong(runData.timestamp) * 1000));
        return date.compareTo(startDate) >= 0 && date.compareTo(endDate) <= 0;
    }

    public int getAct(int floor) {
        if (act1BossFloor > 0) {
            if (floor <= act1BossFloor) {
                return 1;
            }
            if (act2BossFloor <= 0) {
                return 2;
            }
            if (floor <= act2BossFloor) {
                return 2;
            }
            if (act3BossFloor <= 0) {
                return 3;
            }
            if (floor <= act3BossFloor) {
                return 3;
            }
            return 4;
        }

        if (portalFloor > 0) {
            if (floor <= portalFloor) {
                return 1;
            }
            if (act3BossFloor > 0) {
                return floor <= act3BossFloor ? 3 : 4;
            }
            return floor <= portalFloor + 3 ? 3 : 4;
        }

        if (floor <= 17) {
            return 1;
        }
        if (floor <= 34) {
            return 2;
        }
        if (floor <= 52) {
            return 3;
        }
        return 4;
    }

    /**
     * 宝箱层，以及 Boss 后的奖励/过渡层。这些楼层不属于战损统计范围，
     * 否则会把 Boss 后的回血或宝箱效果算到下一幕。
     */
    public boolean isRewardFloor(int floor) {
        if (floor <= 0) {
            return false;
        }
        if ((act1BossFloor > 0 && floor == act1BossFloor + 1)
                || (act2BossFloor > 0 && floor == act2BossFloor + 1)
                || (act3BossFloor > 0 && floor == act3BossFloor + 1)) {
            return true;
        }
        if (runData.path_per_floor != null && floor <= runData.path_per_floor.size()) {
            return "T".equals(runData.path_per_floor.get(floor - 1));
        }
        return false;
    }

    /** 战损统计用幕号；奖励/过渡层返回 0，调用方应跳过。 */
    public int getActForStats(int floor) {
        return isRewardFloor(floor) ? 0 : getAct(floor);
    }

    /**
     * path_per_floor 的节点类型：M=普通战斗、E=精英、B=Boss、?=事件、T=宝箱等。
     * 返回空串表示该层没有可用路径记录。
     */
    public String getPathNode(int floor) {
        if (floor <= 0 || runData.path_per_floor == null || floor > runData.path_per_floor.size()) {
            return "";
        }
        String node = runData.path_per_floor.get(floor - 1);
        return node == null ? "" : node;
    }

    /** 本局实际到过的最后一幕，忽略 Boss 后的奖励/过渡层。 */
    public int getLastActForStats() {
        int floor = Math.max(1, floorsReached);
        while (floor > 1 && isRewardFloor(floor)) {
            floor--;
        }
        return Math.min(4, Math.max(1, getAct(floor)));
    }
    
    /**
     * 检查是否拥有空鸟笼 Boss 遗物
     * 
     * @return true 如果有空鸟笼，false 如果没有
     * 
     * 备注：空鸟笼（Empty Cage）是 Boss 遗物，获得时强制删除 2 张牌。
     *       但 Run History+ 的 JSON 文件中不记录这 2 张删牌，
     *       需要在统计时手动 +2 补偿。
     */
    public boolean hasEmptyCage() {
        return relics != null && relics.contains("Empty Cage");
    }

    @Override
    public int compareTo(Run run) {
        return RunData.orderByTimestampDesc.compare(run.runData, this.runData);
    }
}
