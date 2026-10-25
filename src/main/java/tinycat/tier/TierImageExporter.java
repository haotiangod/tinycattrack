package tinycat.tier;

import tinycat.Utils;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.PixmapIO;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.TextureData;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.graphics.glutils.FrameBuffer;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.utils.JsonReader;
import com.badlogic.gdx.utils.JsonValue;
import com.badlogic.gdx.utils.ScreenUtils;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.helpers.CardLibrary;
import com.megacrit.cardcrawl.helpers.RelicLibrary;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import tinycat.tinycat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 从游戏内存导出卡图/遗物图。
 *
 * 图源完全本地：
 * - 卡牌：CardLibrary.getCard(id).portrait（public AtlasRegion）
 * - Boss 遗物：RelicLibrary.getRelic(id).img（public Texture）
 * - SKIP / 找不到图：本地 Pixmap + PixelFont 生成占位图
 *
 * 不访问网络，不读取外部图片目录，不引入新依赖。
 *
 * 实现说明：
 * 优先使用 FrameBuffer + SpriteBatch 把 TextureRegion 画一遍再读回像素，
 * 这样旋转/翻转图集也能按游戏内实际显示方向导出；
 * 如果 GL 环境不可用，则退回 Pixmap 直接裁剪（桌面版一般走 FrameBuffer）。
 */
public class TierImageExporter {
    private static final String PLACEHOLDER_PATH = "images/placeholder.png";
    private static final String SKIP_PATH = "images/skip.png";
    private static final int FBO_SIZE = 1024;

    private final File imagesDir;
    private final File cardsDir;
    private final File relicsDir;
    private final File potionsDir;
    private final File textsDir;
    private final File cardsCacheDir;
    private final File relicsCacheDir;
    private final File potionsCacheDir;
    private final File commonCacheDir;
    private final Map<Texture, Pixmap> pageCache = new IdentityHashMap<Texture, Pixmap>();

    private FrameBuffer frameBuffer;
    private SpriteBatch batch;
    private Matrix4 matrix;

    public TierImageExporter(File imagesDir) {
        this(imagesDir, new File(imagesDir.getParentFile(), "cache"));
    }

    public TierImageExporter(File imagesDir, File cacheDir) {
        imagesDir.mkdirs();
        cacheDir.mkdirs();
        this.imagesDir = imagesDir;
        this.cardsDir = new File(imagesDir, "cards");
        this.relicsDir = new File(imagesDir, "relics");
        this.potionsDir = new File(imagesDir, "potions");
        this.textsDir = new File(imagesDir, "text");
        this.cardsCacheDir = new File(cacheDir, "cards");
        this.relicsCacheDir = new File(cacheDir, "relics");
        this.potionsCacheDir = new File(cacheDir, "potions");
        this.commonCacheDir = new File(cacheDir, "common");
        this.cardsDir.mkdirs();
        this.relicsDir.mkdirs();
        this.potionsDir.mkdirs();
        this.textsDir.mkdirs();
        this.cardsCacheDir.mkdirs();
        this.relicsCacheDir.mkdirs();
        this.potionsCacheDir.mkdirs();
        this.commonCacheDir.mkdirs();

        File skip = new File(commonCacheDir, "skip.png");
        if (!isUsable(skip)) {
            writePlaceholder(skip, "SKIP", 6, 220, 190);
        }
        File placeholder = new File(commonCacheDir, "placeholder.png");
        if (!isUsable(placeholder)) {
            writePlaceholder(placeholder, "NO IMAGE", 3, 220, 190);
        }
    }

    public String export(TierEntry entry) {
        if (entry == null) {
            return exportCommon("placeholder.png", PLACEHOLDER_PATH);
        }
        if ("skip".equals(entry.type)) {
            return exportCommon("skip.png", SKIP_PATH);
        }
        if ("card".equals(entry.type)) {
            return exportCard(entry);
        }
        if ("boss_relic".equals(entry.type) || "relic".equals(entry.type)) {
            return exportRelic(entry);
        }
        if ("potion".equals(entry.type)) {
            return exportPotion(entry);
        }
        // 火堆行动类型 / 尼奥奖励 / 药水 / 其它：游戏里拿不到独立图源，用本地像素文字占位图
        return exportText(entry);
    }

    /**
     * 判断商店条目到底是卡牌、遗物还是药水。
     *
     * .run 的 items_purchased 只存名字，不分类，所以按「卡 → 遗物 → 药水」的顺序试查。
     * 查不到的统一当 other（用文字占位图兜底）。
     */
    static String classifyShopItem(String name) {
        if (name == null || name.isEmpty()) {
            return "other";
        }
        // 药水优先：卡牌与遗物查不到时都有「兜底返回」，会把药水误判成头环遗物。
        try {
            if (com.megacrit.cardcrawl.helpers.PotionHelper.isAPotion(name)) {
                return "potion";
            }
        } catch (Throwable ignored) {
            // 查不到就当不是药水
        }
        // ID 形式的药水（SwiftPotion）PotionHelper 有时认不出，按后缀兜底
        if (name.endsWith("Potion")) {
            return "potion";
        }
        try {
            if (resolveCard(name) != null) {
                return "card";
            }
        } catch (Throwable ignored) {
            // 游戏未初始化完就跳过
        }
        try {
            com.megacrit.cardcrawl.relics.AbstractRelic relic =
                    com.megacrit.cardcrawl.helpers.RelicLibrary.getRelic(name);
            // RelicLibrary 对未知 key 返回 Circlet（头环）而不是 null，必须排除这个兜底
            if (relic != null && !"Circlet".equals(relic.relicId)) {
                return "relic";
            }
        } catch (Throwable ignored) {
            // 查不到就当不是遗物
        }
        return "other";
    }

    /** 占位图上写的 ASCII 文字（像素字体只有 ASCII，中文名放 nameZh）。 */
    static String shopImageText(String name, String type) {
        if ("potion".equals(type)) {
            return "POTION";
        }
        if ("other".equals(type)) {
            return "ITEM";
        }
        return null;
    }

    /**
     * 文字占位图：把 imageText 画到 220x190 的本地 Pixmap 上。
     * 用于火堆行动类型（REST / RECALL…）、尼奥奖励（RM2+DMG…）、药水等没有独立图源的条目。
     */
    private String exportText(TierEntry entry) {
        String text = entry.imageText == null || entry.imageText.isEmpty() ? "?" : entry.imageText;
        text = text.toUpperCase(Locale.US);
        int scale = text.length() <= 6 ? 6 : (text.length() <= 10 ? 4 : 3);
        String key = cacheKey("text_" + text);
        File cacheFile = new File(commonCacheDir, key + ".png");
        if (!isUsable(cacheFile)) {
            writePlaceholder(cacheFile, text, scale, 220, 190);
        }
        if (!isUsable(cacheFile)) {
            return exportCommon("placeholder.png", PLACEHOLDER_PATH);
        }
        File packageFile = new File(textsDir, key + ".png");
        if (copyFile(cacheFile, packageFile)) {
            return "images/text/" + key + ".png";
        }
        return exportCommon("placeholder.png", PLACEHOLDER_PATH);
    }

    private String exportCard(TierEntry entry) {
        String key = cacheKey("card_" + entry.id);
        File cacheFile = new File(cardsCacheDir, key + ".png");
        File packageFile = new File(cardsDir, key + ".png");
        if (!isUsable(cacheFile)) {
            if (!extractCard(entry, cacheFile)) {
                return exportCommon("placeholder.png", PLACEHOLDER_PATH);
            }
        }
        if (copyFile(cacheFile, packageFile)) {
            return "images/cards/" + key + ".png";
        }
        return exportCommon("placeholder.png", PLACEHOLDER_PATH);
    }

    private String exportPotion(TierEntry entry) {
        String key = cacheKey("potion_" + entry.id);
        File cacheFile = new File(potionsCacheDir, key + ".png");
        File packageFile = new File(potionsDir, key + ".png");
        if (!isUsable(cacheFile)) {
            if (!extractPotion(entry, cacheFile)) {
                return exportCommon("placeholder.png", PLACEHOLDER_PATH);
            }
        }
        if (copyFile(cacheFile, packageFile)) {
            return "images/potions/" + key + ".png";
        }
        return exportCommon("placeholder.png", PLACEHOLDER_PATH);
    }

    private boolean extractPotion(TierEntry entry, File file) {
        try {
            com.megacrit.cardcrawl.potions.AbstractPotion potion =
                    com.megacrit.cardcrawl.helpers.PotionHelper.getPotion(entry.id);
            if (potion == null) {
                return false;
            }
            // img 不是 public 字段，走反射（和项目其它游戏 API 访问方式一致）
            Texture texture = Utils.getField(potion,
                    com.megacrit.cardcrawl.potions.AbstractPotion.class, "img");
            if (texture == null) {
                return false;
            }
            return writeTexture(texture, file);
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to export potion image for " + entry.id, t);
            return false;
        }
    }

    private String exportRelic(TierEntry entry) {
        String key = cacheKey("relic_" + entry.id);
        File cacheFile = new File(relicsCacheDir, key + ".png");
        File packageFile = new File(relicsDir, key + ".png");
        if (!isUsable(cacheFile)) {
            if (!extractRelic(entry, cacheFile)) {
                return exportCommon("placeholder.png", PLACEHOLDER_PATH);
            }
        }
        if (copyFile(cacheFile, packageFile)) {
            return "images/relics/" + key + ".png";
        }
        return exportCommon("placeholder.png", PLACEHOLDER_PATH);
    }

    private boolean extractCard(TierEntry entry, File file) {
        try {
            AbstractCard card = resolveCard(entry.id);
            if (card == null || card.portrait == null) {
                // 之前这里是静默 return，导致 4.2.2 的修复失败后查不到原因
                tinycat.logger.warn("TierList: card image not found in CardLibrary, id=" + entry.id
                        + " (nameEn=" + entry.nameEn + ")");
                return false;
            }
            return writeRegion(card.portrait, file);
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to export card image for " + entry.id, t);
            return false;
        }
    }

    /**
     * 按名字/ID 取 Card 实例。
     *
     * 不能直接用 CardLibrary.getCard(name)：卡牌 ID 与显示名并不总是一致，
     * 官方重命名过一批卡（ID 仍是旧名），例如
     *   Gash → Claw、Undo → Equilibrium、Redo → Recursion、Lockon → Bullseye、
     *   Steam → Steam Barrier、Steam Power → Overclock、Conserve Battery → Charge Battery。
     * 更糟的是 TinyCat 自己的 CardNameNormalizer 会把 .run 里的旧 cardID 换成新显示名，
     * 于是 entry.id 变成 "Equilibrium"，而 CardLibrary 的键仍是 "Undo"，直接查必然是 null。
     *
     * 4.2.2 试过「遍历 CardLibrary.cards，用 card.name 建索引」，但**在中文客户端下失败**：
     * AbstractCard.name / originalName 取的是当前语言包，中文下是"均衡"，索引里根本没有英文键；
     * 加上 normalizeKey 会剥掉非 ASCII 字符，中文名归一后是空串直接被丢弃，
     * 所以按名字建索引在中文环境里永远匹配不上 —— 这就是上一版没修好的真正原因。
     *
     * 现在的查找顺序（与客户端语言无关）：
     *   1) 直接按 ID 查（86 张正常卡走这条）；
     *   2) 英文卡名索引：从游戏自带 localization/eng/cards.json 建「英文名 → cardID」，
     *      该文件 key 是 cardID、NAME 是英文名，不受当前语言影响；
     *   3) 资源读不到时，用 LEGACY_CARD_IDS 兜底（已对 desktop-1.0.jar 的 eng/cards.json 逐条校验）；
     *   4) 以上都落空时再用当前语言的 name / originalName / cardID 索引。
     */
    static AbstractCard resolveCard(String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        AbstractCard direct = CardLibrary.getCard(id);
        if (direct != null && direct.portrait != null) {
            return direct;
        }
        AbstractCard indexed = cardNameIndex().get(normalizeKey(id));
        if (indexed != null && indexed.portrait != null) {
            return indexed;
        }
        String legacyId = LEGACY_CARD_IDS.get(normalizeKey(id));
        if (legacyId != null) {
            AbstractCard legacy = CardLibrary.getCard(legacyId);
            if (legacy != null && legacy.portrait != null) {
                return legacy;
            }
        }
        return direct;
    }

    /**
     * 英文显示名 → 旧 cardID 的兜底表。
     * 每一条都用 desktop-1.0.jar 里的 localization/eng/cards.json 校验过
     * （该文件的 key 就是 CardLibrary 的键）。注意大小写与空格：
     * ClearTheMind / PathToVictory / "Wraith Form v2" / Fasting2 都是真实 ID。
     */
    private static final Map<String, String> LEGACY_CARD_IDS;

    static {
        Map<String, String> map = new HashMap<String, String>();
        map.put(normalizeKey("Claw"), "Gash");
        map.put(normalizeKey("Equilibrium"), "Undo");
        map.put(normalizeKey("Recursion"), "Redo");
        map.put(normalizeKey("Bullseye"), "Lockon");
        map.put(normalizeKey("Steam Barrier"), "Steam");
        map.put(normalizeKey("Overclock"), "Steam Power");
        map.put(normalizeKey("Charge Battery"), "Conserve Battery");
        map.put(normalizeKey("Alchemize"), "Venomology");
        map.put(normalizeKey("Nightmare"), "Night Terror");
        map.put(normalizeKey("Crippling Cloud"), "Crippling Poison");
        map.put(normalizeKey("Sneaky Strike"), "Underhanded Strike");
        map.put(normalizeKey("Tranquility"), "ClearTheMind");
        map.put(normalizeKey("Foresight"), "Wireheading");
        map.put(normalizeKey("Simmering Fury"), "Vengeance");
        map.put(normalizeKey("Rushdown"), "Adaptation");
        map.put(normalizeKey("Pressure Points"), "PathToVictory");
        map.put(normalizeKey("Apparition"), "Ghostly");
        map.put(normalizeKey("Wraith Form"), "Wraith Form v2");
        map.put(normalizeKey("Fasting"), "Fasting2");
        LEGACY_CARD_IDS = map;
    }

    private static Map<String, AbstractCard> cardNameIndex;

    private static Map<String, AbstractCard> cardNameIndex() {
        if (cardNameIndex == null) {
            cardNameIndex = buildCardNameIndex();
        }
        return cardNameIndex;
    }

    private static Map<String, AbstractCard> buildCardNameIndex() {
        Map<String, AbstractCard> index = new HashMap<String, AbstractCard>();
        Set<String> ambiguous = new HashSet<String>();
        if (CardLibrary.cards != null) {
            for (AbstractCard card : CardLibrary.cards.values()) {
                if (card == null) {
                    continue;
                }
                putCard(index, ambiguous, card.cardID, card);
                putCard(index, ambiguous, card.name, card);
                putCard(index, ambiguous, card.originalName, card);
            }
        }
        putEnglishNames(index, ambiguous);
        return index;
    }

    /**
     * 补一层「英文卡名 → 卡牌」索引。
     *
     * 游戏把语言包放在 desktop-1.0.jar 内（localization/&lt;lang&gt;/cards.json），
     * 用 Gdx.files.internal 读就行，和游戏自己加载语言包是同一个路径，
     * 既不联网也不依赖外部文件，也不需要新依赖（JsonReader 是 libGDX 自带）。
     */
    private static void putEnglishNames(Map<String, AbstractCard> index, Set<String> ambiguous) {
        try {
            FileHandle file = Gdx.files.internal("localization/eng/cards.json");
            if (file == null || !file.exists()) {
                tinycat.logger.warn("TierList: localization/eng/cards.json unavailable, "
                        + "card name index falls back to current language");
                return;
            }
            JsonValue root = new JsonReader().parse(file);
            int added = 0;
            for (JsonValue child = root.child; child != null; child = child.next()) {
                String cardId = child.name;
                JsonValue nameValue = child.get("NAME");
                if (cardId == null || cardId.isEmpty() || nameValue == null) {
                    continue;
                }
                AbstractCard card = CardLibrary.getCard(cardId);
                if (card == null || card.portrait == null) {
                    continue;
                }
                if (putCard(index, ambiguous, nameValue.asString(), card)) {
                    added++;
                }
            }
            tinycat.logger.info("TierList: indexed " + added + " english card names");
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to read localization/eng/cards.json", t);
        }
    }

    private static boolean putCard(Map<String, AbstractCard> index,
                                   Set<String> ambiguous,
                                   String key,
                                   AbstractCard card) {
        String normalized = normalizeKey(key);
        if (normalized.isEmpty() || ambiguous.contains(normalized)) {
            return false;
        }
        AbstractCard existing = index.get(normalized);
        if (existing == null) {
            index.put(normalized, card);
            return true;
        }
        if (existing != card) {
            // 同名不同卡（各色的 "Strike"、中文的"打击"等）：宁可查不到也不给错图
            index.remove(normalized);
            ambiguous.add(normalized);
        }
        return false;
    }

    /** 名称归一：小写、只保留 ASCII 字母数字（"Steam Barrier" 与 "SteamBarrier" 等价）。 */
    private static String normalizeKey(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length());
        String lower = value.toLowerCase(Locale.US);
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private boolean extractRelic(TierEntry entry, File file) {
        try {
            AbstractRelic relic = RelicLibrary.getRelic(entry.id);
            if (relic == null || relic.img == null) {
                return false;
            }
            return writeTexture(relic.img, file);
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to export relic image for " + entry.id, t);
            return false;
        }
    }

    private String exportCommon(String cacheName, String relativePath) {
        File cacheFile = new File(commonCacheDir, cacheName);
        if (!isUsable(cacheFile)) {
            return relativePath;
        }
        File packageFile = new File(imagesDir, cacheName);
        if (copyFile(cacheFile, packageFile)) {
            return relativePath;
        }
        return relativePath;
    }

    private boolean writeRegion(TextureAtlas.AtlasRegion region, File file) {
        int width = region.getRegionWidth();
        int height = region.getRegionHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (width <= FBO_SIZE && height <= FBO_SIZE && ensureGl()) {
            boolean scissorEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
            if (scissorEnabled) {
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            }
            boolean fboBegun = false;
            boolean batchBegun = false;
            try {
                frameBuffer.begin();
                fboBegun = true;
                Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                batch.begin();
                batchBegun = true;
                batch.draw(region, 0.0F, 0.0F, width, height);
                batch.end();
                batchBegun = false;
                Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
                frameBuffer.end();
                fboBegun = false;
                Pixmap flipped = flipVertical(raw, width, height);
                raw.dispose();
                boolean ok = writePng(flipped, file);
                flipped.dispose();
                if (ok) {
                    return true;
                }
            } catch (Exception t) {
                tinycat.logger.warn("TierList: framebuffer export failed, fallback to pixmap crop", t);
                try {
                    if (batchBegun && batch != null) {
                        batch.end();
                    }
                } catch (Exception ignored) {
                }
                if (fboBegun) {
                    try {
                        frameBuffer.end();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (scissorEnabled) {
                    Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                }
            }
        }
        return writeRegionByPixmap(region, file);
    }

    private boolean writeTexture(Texture texture, File file) {
        int width = texture.getWidth();
        int height = texture.getHeight();
        if (width <= 0 || height <= 0) {
            return false;
        }
        if (width <= FBO_SIZE && height <= FBO_SIZE && ensureGl()) {
            boolean scissorEnabled = Gdx.gl.glIsEnabled(GL20.GL_SCISSOR_TEST);
            if (scissorEnabled) {
                Gdx.gl.glDisable(GL20.GL_SCISSOR_TEST);
            }
            boolean fboBegun = false;
            boolean batchBegun = false;
            try {
                frameBuffer.begin();
                fboBegun = true;
                Gdx.gl.glClearColor(0.0F, 0.0F, 0.0F, 0.0F);
                Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT);
                batch.begin();
                batchBegun = true;
                batch.draw(texture, 0.0F, 0.0F, width, height);
                batch.end();
                batchBegun = false;
                Pixmap raw = ScreenUtils.getFrameBufferPixmap(0, 0, width, height);
                frameBuffer.end();
                fboBegun = false;
                Pixmap flipped = flipVertical(raw, width, height);
                raw.dispose();
                boolean ok = writePng(flipped, file);
                flipped.dispose();
                if (ok) {
                    return true;
                }
            } catch (Exception t) {
                tinycat.logger.warn("TierList: framebuffer texture export failed, fallback to pixmap copy", t);
                try {
                    if (batchBegun && batch != null) {
                        batch.end();
                    }
                } catch (Exception ignored) {
                }
                if (fboBegun) {
                    try {
                        frameBuffer.end();
                    } catch (Exception ignored) {
                    }
                }
            } finally {
                if (scissorEnabled) {
                    Gdx.gl.glEnable(GL20.GL_SCISSOR_TEST);
                }
            }
        }
        return writeTextureByPixmap(texture, file);
    }

    private boolean writeRegionByPixmap(TextureAtlas.AtlasRegion region, File file) {
        try {
            if (region.rotate) {
                return false;
            }
            int width = region.getRegionWidth();
            int height = region.getRegionHeight();
            Texture texture = region.getTexture();
            if (texture == null) {
                return false;
            }
            Pixmap page = getPage(texture);
            if (page == null) {
                return false;
            }
            int srcX = region.getRegionX();
            // TextureRegion 的 regionY 是“从下往上”的 y；Pixmap 是“从上往下”，需要换算
            int srcY = page.getHeight() - region.getRegionY() - height;
            if (!fits(page, srcX, srcY, width, height)) {
                srcY = region.getRegionY();
                if (!fits(page, srcX, srcY, width, height)) {
                    return false;
                }
            }
            Pixmap out = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            out.setBlending(Pixmap.Blending.None);
            out.drawPixmap(page, srcX, srcY, width, height, 0, 0, width, height);
            boolean ok = writePng(out, file);
            out.dispose();
            return ok;
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to crop image region", t);
            return false;
        }
    }

    private boolean writeTextureByPixmap(Texture texture, File file) {
        try {
            Pixmap page = getPage(texture);
            if (page == null) {
                return false;
            }
            int width = page.getWidth();
            int height = page.getHeight();
            Pixmap out = new Pixmap(width, height, Pixmap.Format.RGBA8888);
            out.setBlending(Pixmap.Blending.None);
            out.drawPixmap(page, 0, 0, width, height, 0, 0, width, height);
            boolean ok = writePng(out, file);
            out.dispose();
            return ok;
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to copy texture", t);
            return false;
        }
    }

    private boolean ensureGl() {
        if (frameBuffer != null && batch != null) {
            return true;
        }
        try {
            frameBuffer = new FrameBuffer(Pixmap.Format.RGBA8888, FBO_SIZE, FBO_SIZE, false);
            batch = new SpriteBatch();
            batch.disableBlending();
            matrix = new Matrix4();
            matrix.setToOrtho2D(0.0F, 0.0F, FBO_SIZE, FBO_SIZE);
            batch.setProjectionMatrix(matrix);
            return true;
        } catch (Exception t) {
            tinycat.logger.warn("TierList: GL framebuffer unavailable, using pixmap fallback", t);
            disposeGl();
            return false;
        }
    }

    private Pixmap flipVertical(Pixmap source, int width, int height) {
        Pixmap out = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        out.setBlending(Pixmap.Blending.None);
        for (int y = 0; y < height; y++) {
            out.drawPixmap(source, 0, y, width, 1, 0, height - y - 1, width, 1);
        }
        return out;
    }

    private Pixmap getPage(Texture texture) {
        Pixmap cached = pageCache.get(texture);
        if (cached != null) {
            return cached;
        }
        TextureData data = texture.getTextureData();
        if (data == null) {
            return null;
        }
        if (!data.isPrepared()) {
            data.prepare();
        }
        Pixmap page = data.consumePixmap();
        if (page != null) {
            pageCache.put(texture, page);
        }
        return page;
    }

    private boolean fits(Pixmap page, int x, int y, int width, int height) {
        return x >= 0 && y >= 0 && x + width <= page.getWidth() && y + height <= page.getHeight();
    }

    private boolean writePng(Pixmap pixmap, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            PixmapIO.writePNG(new FileHandle(file), pixmap);
            return true;
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to write png " + file, t);
            return false;
        }
    }

    private void writePlaceholder(File file, String text, int scale, int width, int height) {
        Pixmap pixmap = new Pixmap(width, height, Pixmap.Format.RGBA8888);
        try {
            pixmap.setColor(0.09F, 0.09F, 0.12F, 1.0F);
            pixmap.fill();
            pixmap.setColor(0.30F, 0.30F, 0.36F, 1.0F);
            pixmap.drawRectangle(0, 0, width - 1, height - 1);
            pixmap.setColor(0.42F, 0.40F, 0.34F, 1.0F);
            pixmap.drawRectangle(3, 3, width - 7, height - 7);
            int textY = height / 2 - 3 * scale;
            PixelFont.drawCentered(pixmap, text, textY, scale, new Color(0.86F, 0.82F, 0.68F, 1.0F));
            writePng(pixmap, file);
        } finally {
            pixmap.dispose();
        }
    }

    private String cacheKey(String raw) {
        String base = raw.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
        base = base.replaceAll("^_+", "").replaceAll("_+$", "");
        if (base.isEmpty()) {
            base = "image";
        }
        if (base.length() > 60) {
            base = base.substring(0, 60);
        }
        return base + "_" + shortHash(raw);
    }

    private String shortHash(String raw) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] bytes = digest.digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(8);
            for (int i = 0; i < 4; i++) {
                out.append(String.format(Locale.US, "%02x", bytes[i] & 0xff));
            }
            return out.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(raw.hashCode());
        }
    }

    private boolean isUsable(File file) {
        return file.isFile() && file.length() > 0L;
    }

    private boolean copyFile(File source, File destination) {
        try {
            File parent = destination.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return isUsable(destination);
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to copy cached image " + source + " -> " + destination, t);
            return false;
        }
    }

    private void disposeGl() {
        if (batch != null) {
            try {
                batch.dispose();
            } catch (Exception ignored) {
            }
            batch = null;
        }
        if (frameBuffer != null) {
            try {
                frameBuffer.dispose();
            } catch (Exception ignored) {
            }
            frameBuffer = null;
        }
        matrix = null;
    }

    public void dispose() {
        disposeGl();
        for (Pixmap pixmap : pageCache.values()) {
            try {
                pixmap.dispose();
            } catch (Exception ignored) {
                // 忽略释放异常
            }
        }
        pageCache.clear();
    }
}
