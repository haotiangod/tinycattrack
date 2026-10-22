package tinycat.tier;

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
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

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
    private final File cardsCacheDir;
    private final File relicsCacheDir;
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
        this.cardsCacheDir = new File(cacheDir, "cards");
        this.relicsCacheDir = new File(cacheDir, "relics");
        this.commonCacheDir = new File(cacheDir, "common");
        this.cardsDir.mkdirs();
        this.relicsDir.mkdirs();
        this.cardsCacheDir.mkdirs();
        this.relicsCacheDir.mkdirs();
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
        if ("boss_relic".equals(entry.type)) {
            return exportRelic(entry);
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
            AbstractCard card = CardLibrary.getCard(entry.id);
            if (card == null || card.portrait == null) {
                return false;
            }
            return writeRegion(card.portrait, file);
        } catch (Exception t) {
            tinycat.logger.warn("TierList: failed to export card image for " + entry.id, t);
            return false;
        }
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
