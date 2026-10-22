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

    /** 存档目录名，用于匹配 gold-tracker 输出文件（如 "1_IRONCLAD"） */
    private final String runDir;

    public int portalFloor = -1;

    public final AbstractPlayer.PlayerClass playerClass;

    public final boolean isHeartKill;
    public final boolean isA20;
    public final boolean abandoned;
    public final long timestamp;
    public final String killedBy;
    public final int floorsReached;
    public final int playtime;
    public final int score;
    public final int gold;
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
                !runData.chose_seed && !runData.is_special_run && !runData.is_daily && !runData.is_endless && isA20;
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
        // 优先从 gold-tracker 模组输出文件读取精确累计金币
        int exactGold = readGoldTrackerOutput();
        if (exactGold > 0) {
            return exactGold;
        }

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

    /**
     * 从 gold-tracker 模组输出文件中读取精确累计金币。
     * 文件路径: runs/gold_track/{runDir}/{timestamp}.txt
     * 解析 summary 中的 gold_own 字段。
     *
     * @return 累计金币，找不到则返回 -1
     */
    private int readGoldTrackerOutput() {
        try {
            String timestamp = runData.timestamp;
            if (timestamp == null || runDir == null) {
                return -1;
            }

            // 构造 gold-tracker 输出文件路径
            String goldFilePath = "runs/gold_track/" + runDir + "/" + timestamp + ".txt";
            FileHandle goldFile = Gdx.files.local(goldFilePath);
            if (!goldFile.exists()) {
                return -1;
            }

            String content = goldFile.readString();
            for (String line : content.split("\n")) {
                line = line.trim();
                if (line.startsWith("gold_own:")) {
                    String[] parts = line.split(":", 2);
                    if (parts.length >= 2) {
                        return Integer.parseInt(parts[1].trim());
                    }
                }
            }
        } catch (Exception e) {
            tinycat.logger.warn("GoldTracker: failed to read gold-tracker output", e);
        }
        return -1;
    }

    private int getMaxHP() {
        if (runData.max_hp_per_floor.isEmpty()) {
            return 0;
        }
        return runData.max_hp_per_floor.get(runData.max_hp_per_floor.size() - 1);
    }

    /**
     * 获取第0层（Neow房间）结束时的HP值，即进入第1层前、未进行任何操作时的血量。
     * 优先级：
     * 1. tinycat.capturedFloor0HP（当前运行时 PostDungeonInitialize 精确捕获，最优先）
     * 2. 伴生文件 runs/{runDir}/{timestamp}.floor0hp（往期记录，tinycat写入）
     * 3. 估算法（仅用于历史数据，大量样本可稀释误差）：
     *    Ironclad: floor0HP = floor1HP + floor1Damage - 6（Burning Blood 回血6）
     *    其他角色: floor0HP = floor1HP + floor1Damage（无自带回复）
     * 4. max_hp_per_floor[0]（最终回退，假设无任何HP变化）
     *
     * 注意：估算法假设第1层只有战斗HP变化，忽略遗物回血/事件等。
     *       开启 tinycat 后运行的存档优先使用精确值。
     */
    private int getFloor0HP() {
        // 优先级1: 当前运行时精确捕获
        if (tinycat.capturedFloor0HP != null) {
            return tinycat.capturedFloor0HP;
        }

        // 优先级2: 伴生文件（往期 tinycat 记录）
        int companionHP = readCompanionFloor0HP();
        if (companionHP > 0) {
            return companionHP;
        }

        // 优先级3: 估算法（基于第1层受到伤害反推第0层HP）
        // floor0HP = floor1HP + floor1Damage - 6 (Ironclad, Burning Blood回血)
        // floor0HP = floor1HP + floor1Damage (其他角色，无自带回复)
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

        // 优先级4: 最终回退
        if (runData.max_hp_per_floor != null && !runData.max_hp_per_floor.isEmpty()) {
            return runData.max_hp_per_floor.get(0);
        }
        return 70;
    }

    /**
     * 读取 floor0HP 伴生文件。
     *
     * tinycat 在开局写入时用的是“开局时间戳”，而 .run 的 timestamp 是“结束时间戳”，
     * 两者通常不相等，所以不能只按精确文件名找。
     * 这里先找精确文件名，再在 runDir 下找 timestamp <= 结束时间、且相差不超过 24h 的
     * 最新一个 .floor0hp 文件，避免历史局永远读不到精确值。
     */
    private int readCompanionFloor0HP() {
        if (runDir == null || runData.timestamp == null) {
            return -1;
        }
        long endTimestamp;
        try {
            endTimestamp = Long.parseLong(runData.timestamp);
        } catch (NumberFormatException e) {
            return -1;
        }

        FileHandle exact = Gdx.files.local("runs/" + runDir + "/" + runData.timestamp + ".floor0hp");
        int exactHP = readFloor0File(exact);
        if (exactHP > 0) {
            return exactHP;
        }

        FileHandle directory = Gdx.files.local("runs/" + runDir + "/");
        if (!directory.exists() || !directory.isDirectory()) {
            return -1;
        }

        FileHandle best = null;
        long expectedStart = endTimestamp - Math.max(0, runData.playtime);
        long bestDiff = Long.MAX_VALUE;
        for (FileHandle file : directory.list()) {
            String name = file.name();
            if (!name.endsWith(".floor0hp")) {
                continue;
            }
            try {
                long timestamp = Long.parseLong(name.substring(0, name.length() - 9));
                if (timestamp > endTimestamp) {
                    continue;
                }
                long diff = Math.abs(timestamp - expectedStart);
                if (diff <= 24L * 60L * 60L && diff < bestDiff) {
                    bestDiff = diff;
                    best = file;
                }
            } catch (NumberFormatException ignored) {
                // 文件名不是时间戳，跳过
            }
        }
        return best == null ? -1 : readFloor0File(best);
    }

    private int readFloor0File(FileHandle file) {
        try {
            if (file != null && file.exists() && !file.isDirectory()) {
                int hp = Integer.parseInt(file.readString().trim());
                tinycat.logger.debug("Loaded floor0HP from companion file: " + file.path() + " = " + hp);
                return hp;
            }
        } catch (Exception e) {
            tinycat.logger.debug("Failed to read companion file: " + e.getMessage());
        }
        return -1;
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
        if (floor <= 17) {
            return 1;
        }
        if (floor <= 34) {
            return 2;
        }

        if (portalFloor > 0) {
            if (floor <= portalFloor + 3) {
                return 3;
            } else {
                return 4;
            }
        }

        if (floor <= 52) {
            return 3;
        }
        return 4;
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
