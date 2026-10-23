package tinycat.tier;

import com.badlogic.gdx.Gdx;
import tinycat.tinycat;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 怪物图片本地图源。
 *
 * 查找顺序：游戏目录 mods/TinyCat/monster-images（递归）→ monster-images → jar 内
 * tinycat/monsters → 占位图。图片只在导出时读取，不提交进公开仓库。
 */
public final class MonsterImageLibrary {
    private static final int OUTPUT_SIZE = 256;
    private static final int PADDING = 10;
    private static final String PLACEHOLDER = "images/monsters/placeholder.png";

    private static final Map<String, String[]> IMAGE_FILES = new HashMap<String, String[]>();
    private static final Map<String, String[]> FALLBACK_FILES = new HashMap<String, String[]>();

    static {
        // 用户图包中 Awakened-one-AAA-pretty.png 实际是 WebP，Java 8 无法解码；
        // 失败时退回同一 Boss 的未觉醒形态，避免整张卡变成占位图。
        FALLBACK_FILES.put("Awakened One", names("Unawakened_Form"));

        IMAGE_FILES.put("Hexaghost", names("Hexaghust"));
        IMAGE_FILES.put("Slime Boss", names("Slime-boss-pretty"));
        IMAGE_FILES.put("The Guardian", names("The-guardian-def-pretty"));
        IMAGE_FILES.put("Collector", names("TheCollector"));
        IMAGE_FILES.put("Automaton", names("Bronze-automoton-pretty"));
        IMAGE_FILES.put("Champ", names("Champ"));
        IMAGE_FILES.put("Time Eater", names("Time-eater-pretty"));
        IMAGE_FILES.put("Awakened One", names("Awakened-one-AAA-pretty"));
        IMAGE_FILES.put("Donu and Deca", names("Donu", "Deca"));

        IMAGE_FILES.put("3 Sentries", names("Sentry-pretty"));
        IMAGE_FILES.put("Lagavulin", names("Lagavulin-zzz-pretty"));
        IMAGE_FILES.put("Gremlin Nob", names("Gremlin-nob-pretty"));
        IMAGE_FILES.put("Book of Stabbing", names("Book-of-stabbing-pretty"));
        IMAGE_FILES.put("Giant Head", names("Giant-head-pretty"));
        IMAGE_FILES.put("Gremlin Leader", names("Gremlin_Leader"));
        IMAGE_FILES.put("Nemesis", names("Nemesis-pretty"));
        IMAGE_FILES.put("Reptomancer", names("Reptomancer"));

        IMAGE_FILES.put("Small Slimes", names("Acid-slime-l-pretty"));
        IMAGE_FILES.put("Large Slime", names("Acid-slime-l-pretty"));
        IMAGE_FILES.put("Lots of Slimes", names("Acid-slime-l-pretty"));
        IMAGE_FILES.put("Jaw Worm", names("Jaw-worm-pretty"));
        IMAGE_FILES.put("Jaw Worm Horde", names("Jaw-worm-pretty"));
        IMAGE_FILES.put("Cultist", names("Cultist-pretty"));
        IMAGE_FILES.put("3 Cultists", names("Cultist-pretty"));
        IMAGE_FILES.put("Cultist and Chosen", names("Cultist-pretty"));
        IMAGE_FILES.put("2 Louse", names("Louse-red-pretty"));
        IMAGE_FILES.put("3 Louse", names("Louse-red-pretty"));
        IMAGE_FILES.put("Looter", names("Looter-pretty"));
        IMAGE_FILES.put("2 Thieves", names("Looter-pretty"));
        IMAGE_FILES.put("Exordium Thugs", names("Looter-pretty"));
        IMAGE_FILES.put("Blue Slaver", names("Slaver-blue-pretty"));
        IMAGE_FILES.put("Red Slaver", names("Slaver-leader-pretty"));
        IMAGE_FILES.put("Slavers", names("Slaver-leader-pretty"));
        IMAGE_FILES.put("2 Fungi Beasts", names("Fungi-beast-pretty"));
        IMAGE_FILES.put("Exordium Wildlife", names("Fungi-beast-pretty"));
        IMAGE_FILES.put("Gremlin Gang", names("Fat-gremlin-pretty"));
        IMAGE_FILES.put("Centurion and Healer", names("Centurion-pretty"));
        IMAGE_FILES.put("Chosen", names("Chosen-pretty"));
        IMAGE_FILES.put("Chosen and Byrds", names("Chosen-pretty"));
        IMAGE_FILES.put("3 Byrds", names("Flying"));
        IMAGE_FILES.put("Snake Plant", names("Snake_Plant"));
        IMAGE_FILES.put("Snecko", names("Snecko"));
        IMAGE_FILES.put("Spheric Guardian", names("Spheric-guardian-pretty"));
        IMAGE_FILES.put("Shell Parasite", names("Shelled-parasite-pretty"));
        IMAGE_FILES.put("Shelled Parasite and Fungi", names("Shelled-parasite-pretty"));
        IMAGE_FILES.put("Sentry and Sphere", names("Sentry-pretty"));
        IMAGE_FILES.put("Sphere and 2 Shapes", names("Spheric-guardian-pretty"));
        IMAGE_FILES.put("3 Darklings", names("Darkling"));
        IMAGE_FILES.put("Orb Walker", names("Orb-walker-pretty"));
        IMAGE_FILES.put("3 Shapes", names("Spiker-pretty"));
        IMAGE_FILES.put("4 Shapes", names("Spiker-pretty"));
        IMAGE_FILES.put("Spire Growth", names("Spire_Growth"));
        IMAGE_FILES.put("Writhing Mass", names("Writhing_Mass"));
        IMAGE_FILES.put("Transient", names("The_Transient"));
        IMAGE_FILES.put("Maw", names("The-maw-pretty"));
    }

    private static String[] names(String... values) {
        return values;
    }

    private final File monstersDir;
    private final File cacheDir;
    private final Map<String, File> sourceIndex = new HashMap<String, File>();
    private final List<File> webpFiles = new ArrayList<File>();

    public MonsterImageLibrary(File imagesDir, File cacheDir) {
        this(discoverSourceRoots(), imagesDir, cacheDir);
    }

    public MonsterImageLibrary(File[] sourceRoots, File imagesDir, File cacheDir) {
        this.monstersDir = new File(imagesDir, "monsters");
        this.cacheDir = new File(cacheDir, "monsters");
        this.monstersDir.mkdirs();
        this.cacheDir.mkdirs();
        if (sourceRoots != null) {
            for (File root : sourceRoots) {
                index(root);
            }
        }
        warnUnsupported();
        ensurePlaceholder();
    }

    public String export(MonsterTierEntry entry) {
        if (entry == null) {
            return PLACEHOLDER;
        }
        RenderResult result = renderEntry(entry);
        if (result == null) {
            return PLACEHOLDER;
        }
        String signature = signature(result.sources);
        String fileName = safeName(entry.id) + "_" + shortHash(entry.id + "|" + signature) + ".png";
        File cached = new File(cacheDir, fileName);
        File packaged = new File(monstersDir, fileName);
        if (!isUsable(cached) && !writePng(result.image, cached)) {
            return PLACEHOLDER;
        }
        if (!copy(cached, packaged)) {
            return PLACEHOLDER;
        }
        return "images/monsters/" + fileName;
    }

    private RenderResult renderEntry(MonsterTierEntry entry) {
        List<Source> sources = resolve(entry);
        BufferedImage image = render(sources);
        if (image != null) {
            return new RenderResult(sources, image);
        }
        String[] fallbackKeys = FALLBACK_FILES.get(entry.id);
        if (fallbackKeys != null) {
            List<Source> fallback = resolveKeys(fallbackKeys);
            BufferedImage fallbackImage = render(fallback);
            if (fallbackImage != null) {
                return new RenderResult(fallback, fallbackImage);
            }
        }
        return null;
    }

    private List<Source> resolve(MonsterTierEntry entry) {
        String[] keys = IMAGE_FILES.get(entry.id);
        if (keys == null) {
            keys = new String[]{entry.nameEn};
        }
        List<Source> sources = resolveKeys(keys);
        if (sources.isEmpty()) {
            Source fallback = resolveOne(entry.nameEn);
            if (fallback != null) {
                sources.add(fallback);
            }
        }
        return sources;
    }

    private List<Source> resolveKeys(String[] keys) {
        List<Source> sources = new ArrayList<Source>();
        for (String key : keys) {
            Source source = resolveOne(key);
            if (source != null) {
                sources.add(source);
            }
        }
        return sources;
    }

    private Source resolveOne(String key) {
        File file = sourceIndex.get(normalize(key));
        if (file != null && file.isFile()) {
            return Source.file(file);
        }
        String[] extensions = {".png", ".jpg", ".jpeg"};
        for (String extension : extensions) {
            String path = "tinycat/monsters/" + key + extension;
            if (MonsterImageLibrary.class.getClassLoader().getResource(path) != null) {
                return Source.resource(path);
            }
        }
        return null;
    }

    private BufferedImage render(List<Source> sources) {
        List<BufferedImage> images = new ArrayList<BufferedImage>();
        for (Source source : sources) {
            BufferedImage image = source.read();
            if (image != null) {
                images.add(image);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        BufferedImage output = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = output.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int usable = OUTPUT_SIZE - PADDING * 2;
            if (images.size() == 1) {
                drawFitted(g, images.get(0), PADDING, PADDING, usable, usable);
            } else {
                int gap = 8;
                int cellWidth = (usable - gap) / 2;
                drawFitted(g, images.get(0), PADDING, PADDING, cellWidth, usable);
                drawFitted(g, images.get(1),
                        PADDING + cellWidth + gap, PADDING, usable - cellWidth - gap, usable);
            }
        } finally {
            g.dispose();
        }
        return output;
    }

    private void drawFitted(Graphics2D g, BufferedImage image, int x, int y, int width, int height) {
        int[] bounds = contentBounds(image);
        if (bounds == null) {
            return;
        }
        int sourceWidth = bounds[2] - bounds[0];
        int sourceHeight = bounds[3] - bounds[1];
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }
        double scale = Math.min((double) width / sourceWidth, (double) height / sourceHeight);
        int drawWidth = Math.max(1, (int) Math.round(sourceWidth * scale));
        int drawHeight = Math.max(1, (int) Math.round(sourceHeight * scale));
        int drawX = x + (width - drawWidth) / 2;
        int drawY = y + (height - drawHeight) / 2;
        g.drawImage(image, drawX, drawY, drawX + drawWidth, drawY + drawHeight,
                bounds[0], bounds[1], bounds[2], bounds[3], null);
    }

    private int[] contentBounds(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int minX = width;
        int minY = height;
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int alpha = (image.getRGB(x, y) >>> 24) & 0xff;
                if (alpha > 8) {
                    if (x < minX) {
                        minX = x;
                    }
                    if (y < minY) {
                        minY = y;
                    }
                    if (x > maxX) {
                        maxX = x;
                    }
                    if (y > maxY) {
                        maxY = y;
                    }
                }
            }
        }
        if (maxX < minX || maxY < minY) {
            return new int[]{0, 0, width, height};
        }
        return new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    private void ensurePlaceholder() {
        File cached = new File(cacheDir, "placeholder.png");
        if (!isUsable(cached)) {
            BufferedImage image = new BufferedImage(OUTPUT_SIZE, OUTPUT_SIZE, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(new Color(0x151821));
                g.fillRoundRect(0, 0, OUTPUT_SIZE, OUTPUT_SIZE, 18, 18);
                g.setColor(new Color(0x3b4250));
                g.drawRoundRect(1, 1, OUTPUT_SIZE - 3, OUTPUT_SIZE - 3, 18, 18);
                g.setColor(new Color(0x8b93a3));
                g.setFont(font(18, Font.BOLD));
                FontMetrics metrics = g.getFontMetrics();
                String text = "NO IMAGE";
                g.drawString(text, (OUTPUT_SIZE - metrics.stringWidth(text)) / 2,
                        OUTPUT_SIZE / 2 + metrics.getAscent() / 2 - 2);
            } finally {
                g.dispose();
            }
            writePng(image, cached);
        }
        copy(cached, new File(monstersDir, "placeholder.png"));
    }

    private static Font font(int size, int style) {
        Font font = new Font("Microsoft YaHei", style, size);
        if (font.canDisplay('图')) {
            return font;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static File[] discoverSourceRoots() {
        List<File> roots = new ArrayList<File>();
        addRoot(roots, "mods/TinyCat/monster-images");
        addRoot(roots, "monster-images");
        return roots.toArray(new File[roots.size()]);
    }

    private static void addRoot(List<File> roots, String path) {
        try {
            File file = Gdx.files.local(path).file();
            if (file.isDirectory() && !roots.contains(file)) {
                roots.add(file);
            }
        } catch (Exception ignored) {
            // Gdx 未初始化时由测试构造器直接传入图源目录
        }
    }

    private void index(File root) {
        if (root == null || !root.isDirectory()) {
            return;
        }
        File[] files = root.listFiles();
        if (files == null) {
            return;
        }
        Arrays.sort(files);
        for (File file : files) {
            if (file.isDirectory()) {
                index(file);
                continue;
            }
            String name = file.getName();
            int dot = name.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String extension = name.substring(dot + 1).toLowerCase(Locale.US);
            if (!"png".equals(extension) && !"jpg".equals(extension) && !"jpeg".equals(extension)) {
                continue;
            }
            if (isWebp(file)) {
                // Java 8 的 ImageIO 不支持 WebP；图包里存在 .png 扩展名的 WebP 文件。
                // 记录后跳过，让同名真 PNG 或备用图有机会被索引。
                webpFiles.add(file);
                continue;
            }
            String key = normalize(name.substring(0, dot));
            if (!key.isEmpty() && !sourceIndex.containsKey(key)) {
                sourceIndex.put(key, file);
            }
        }
    }

    private static boolean isWebp(File file) {
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            byte[] header = new byte[12];
            int read = stream.read(header);
            return read == 12
                    && header[0] == 'R' && header[1] == 'I' && header[2] == 'F' && header[3] == 'F'
                    && header[8] == 'W' && header[9] == 'E' && header[10] == 'B' && header[11] == 'P';
        } catch (Exception ignored) {
            return false;
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void warnUnsupported() {
        if (webpFiles.isEmpty()) {
            return;
        }
        StringBuilder names = new StringBuilder();
        int limit = Math.min(webpFiles.size(), 5);
        for (int i = 0; i < limit; i++) {
            if (i > 0) {
                names.append(", ");
            }
            names.append(webpFiles.get(i).getName());
        }
        if (webpFiles.size() > limit) {
            names.append(", ...");
        }
        tinycat.logger.warn("MonsterTier: " + webpFiles.size()
                + " WebP image(s) renamed as .png cannot be decoded by Java 8; convert to real PNG: "
                + names);
    }

    private static String signature(List<Source> sources) {
        StringBuilder value = new StringBuilder();
        for (Source source : sources) {
            value.append(source.signature()).append(';');
        }
        return value.toString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static String safeName(String value) {
        String name = value == null ? "monster" : value.toLowerCase(Locale.US);
        name = name.replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (name.isEmpty()) {
            name = "monster";
        }
        return name.length() > 50 ? name.substring(0, 50) : name;
    }

    private static String shortHash(String raw) {
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

    private static boolean writePng(BufferedImage image, File file) {
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            return ImageIO.write(image, "png", file);
        } catch (Exception t) {
            tinycat.logger.warn("MonsterTier: failed to write image " + file, t);
            return false;
        }
    }

    private static boolean isUsable(File file) {
        return file.isFile() && file.length() > 0L;
    }

    private static boolean copy(File source, File destination) {
        try {
            File parent = destination.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            Files.copy(source.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return isUsable(destination);
        } catch (Exception t) {
            tinycat.logger.warn("MonsterTier: failed to copy image " + source, t);
            return false;
        }
    }

    private static final class RenderResult {
        private final List<Source> sources;
        private final BufferedImage image;

        private RenderResult(List<Source> sources, BufferedImage image) {
            this.sources = sources;
            this.image = image;
        }
    }

    private static final class Source {
        private final File file;
        private final String resourcePath;

        private Source(File file, String resourcePath) {
            this.file = file;
            this.resourcePath = resourcePath;
        }

        private static Source file(File file) {
            return new Source(file, null);
        }

        private static Source resource(String path) {
            return new Source(null, path);
        }

        private String signature() {
            if (file != null) {
                return file.getAbsolutePath() + ":" + file.length() + ":" + file.lastModified();
            }
            return "classpath:" + resourcePath;
        }

        private BufferedImage read() {
            if (file != null) {
                try {
                    return ImageIO.read(file);
                } catch (Exception t) {
                    tinycat.logger.warn("MonsterTier: failed to read image " + file, t);
                    return null;
                }
            }
            InputStream stream = null;
            try {
                stream = MonsterImageLibrary.class.getClassLoader().getResourceAsStream(resourcePath);
                return stream == null ? null : ImageIO.read(stream);
            } catch (Exception t) {
                tinycat.logger.warn("MonsterTier: failed to read classpath image " + resourcePath, t);
                return null;
            } finally {
                if (stream != null) {
                    try {
                        stream.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
    }
}
