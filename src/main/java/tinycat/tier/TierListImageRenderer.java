package tinycat.tier;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Iterator;
import java.util.List;

/**
 * 把 Tier List 渲染成一张可直接发送的整图。
 *
 * 输出 tierlist.png（无损）和 tierlist.jpg（便于微信/QQ/Discord 发送）。
 * 只依赖 JDK 自带的 Java2D / ImageIO，不引入新依赖，也不访问网络。
 */
public final class TierListImageRenderer {
    private static final int WIDTH = 1600;
    private static final int PAD = 40;
    private static final int LABEL_W = 170;
    private static final int COLS = 5;
    private static final int GAP = 10;
    private static final int ENTRY_H = 92;
    private static final int ENTRY_W = (WIDTH - PAD * 2 - LABEL_W - (COLS - 1) * GAP) / COLS;

    private static final Color BG = new Color(0x0f1116);
    private static final Color PANEL = new Color(0x171a21);
    private static final Color PANEL2 = new Color(0x1d212a);
    private static final Color TEXT = new Color(0xe7e2d4);
    private static final Color MUTED = new Color(0x9aa1ad);
    private static final Color LINE = new Color(0x2b303b);
    private static final Color GOLD = new Color(0xe5c07b);

    private TierListImageRenderer() {
    }

    public static void write(TierListModel model, File root) throws Exception {
        BufferedImage image = render(model, root);
        ImageIO.write(image, "png", new File(root, "tierlist.png"));
        writeJpeg(image, new File(root, "tierlist.jpg"), 0.92F);
    }

    private static BufferedImage render(TierListModel model, File root) {
        int height = layoutHeight(model);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(BG);
            g.fillRect(0, 0, WIDTH, height);

            int y = PAD;
            y = drawHeader(g, model, y);
            for (TierListGroup group : model.groups) {
                y = drawGroup(g, model, group, root, y);
            }
            g.setColor(MUTED);
            g.setFont(font(12, Font.PLAIN));
            g.drawString("TinyCat 4.2.2 · local export · no external image source", PAD, height - 24);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int layoutHeight(TierListModel model) {
        int height = PAD + 150;
        for (TierListGroup group : model.groups) {
            height += 54;
            if (group.entries.isEmpty()) {
                height += 40;
                continue;
            }
            for (Tier tier : Tier.values()) {
                int count = group.countOf(tier);
                int rows = Math.max(1, (count + COLS - 1) / COLS);
                height += count == 0 ? 34 : 28 + rows * (ENTRY_H + GAP) + 8;
            }
            height += 10;
        }
        return height + PAD;
    }

    private static int drawHeader(Graphics2D g, TierListModel model, int y) {
        g.setColor(GOLD);
        g.setFont(font(34, Font.BOLD));
        g.drawString("TinyCat Tier List", PAD, y + 34);
        g.setColor(MUTED);
        g.setFont(font(16, Font.PLAIN));
        g.drawString("抓率梯度 · local run history export", PAD, y + 60);

        y += 82;
        g.setFont(font(13, Font.PLAIN));
        g.setColor(TEXT);
        String scope = model.scope == null ? "全部抓率" : model.scope.labelZh + " / " + model.scope.labelEn;
        g.drawString("角色 / Character: " + model.characterZh + " / " + model.characterEn, PAD, y);
        g.drawString("范围 / Scope: " + scope, PAD + 420, y);
        g.drawString("日期 / Date: " + empty(model.startDate) + " ~ " + empty(model.endDate), PAD + 900, y);

        y += 20;
        g.setColor(MUTED);
        g.drawString("对局 / Runs: " + model.runCount
                + "    最小样本 / Min sample: " + model.minSample
                + "    放弃局 / Abandons: " + (model.includeAbandons ? "包含" : "排除")
                + "    种子局 / Seeds: " + model.seedFilter.labelZh
                + "    生成 / Generated: " + model.generatedAt, PAD, y);

        y += 22;
        g.setColor(MUTED);
        g.setFont(font(12, Font.PLAIN));
        g.drawString("抓率 = 被选取次数 / 被提供次数；0 档 = 从未被选取；SKIP 保留，颂钵并入 SKIP。"
                + "图片全部来自本地游戏，不依赖外部图源。", PAD, y);
        return y + 22;
    }

    private static int drawGroup(Graphics2D g, TierListModel model, TierListGroup group, File root, int y) {
        g.setColor(TEXT);
        g.setFont(font(24, Font.BOLD));
        g.drawString(group.labelZh, PAD, y + 28);
        int zhWidth = g.getFontMetrics().stringWidth(group.labelZh);
        g.setColor(MUTED);
        g.setFont(font(13, Font.PLAIN));
        g.drawString(group.labelEn, PAD + zhWidth + 14, y + 28);
        y += 46;

        if (group.entries.isEmpty()) {
            g.setColor(MUTED);
            g.setFont(font(14, Font.PLAIN));
            g.drawString("No data / 无数据", PAD + LABEL_W, y + 20);
            return y + 40;
        }

        for (Tier tier : Tier.values()) {
            List<TierEntry> entries = group.entriesOf(tier);
            int count = entries.size();
            Color tierColor = Color.decode(tier.color);
            g.setColor(tierColor);
            g.fillRoundRect(PAD, y + 4, 4, 24, 4, 4);
            g.setFont(font(22, Font.BOLD));
            g.drawString(tier.key, PAD + 12, y + 25);
            g.setColor(MUTED);
            g.setFont(font(11, Font.PLAIN));
            g.drawString(tier.labelEn + " · " + count, PAD + 42, y + 24);
            y += 30;

            if (count == 0) {
                g.setColor(LINE);
                g.drawLine(PAD + LABEL_W, y, WIDTH - PAD, y);
                y += 4;
                continue;
            }

            for (int i = 0; i < count; i++) {
                int col = i % COLS;
                int row = i / COLS;
                int x = PAD + LABEL_W + col * (ENTRY_W + GAP);
                int ey = y + row * (ENTRY_H + GAP);
                drawEntry(g, entries.get(i), root, x, ey, tierColor);
            }
            int rows = (count + COLS - 1) / COLS;
            y += rows * (ENTRY_H + GAP) + 8;
        }
        return y + 8;
    }

    private static void drawEntry(Graphics2D g, TierEntry entry, File root, int x, int y, Color tierColor) {
        g.setColor(PANEL2);
        g.fillRoundRect(x, y, ENTRY_W, ENTRY_H, 10, 10);
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.0F));
        g.drawRoundRect(x, y, ENTRY_W, ENTRY_H, 10, 10);

        int imageX = x + 8;
        int imageY = y + 8;
        int imageW = 64;
        int imageH = 48;
        BufferedImage image = loadImage(root, entry.imagePath);
        if (image != null) {
            g.drawImage(image, imageX, imageY, imageW, imageH, null);
        } else {
            g.setColor(new Color(0x0b0d11));
            g.fillRoundRect(imageX, imageY, imageW, imageH, 6, 6);
            g.setColor(MUTED);
            g.setFont(font(9, Font.PLAIN));
            g.drawString("NO IMAGE", imageX + 7, imageY + 28);
        }

        int textX = imageX + imageW + 8;
        int textW = x + ENTRY_W - textX - 8;
        g.setColor(TEXT);
        g.setFont(font(16, Font.BOLD));
        g.drawString(fit(g, entry.nameZh, textW, Font.BOLD, 16), textX, y + 26);
        g.setColor(MUTED);
        g.setFont(font(10, Font.PLAIN));
        g.drawString(fit(g, entry.nameEn, textW, Font.PLAIN, 10), textX, y + 42);

        g.setColor(tierColor);
        g.setFont(font(15, Font.BOLD));
        g.drawString(String.format(java.util.Locale.US, "%.2f%%", entry.rate), textX, y + 66);
        g.setColor(MUTED);
        g.setFont(font(9, Font.PLAIN));
        g.drawString(entry.picked + " / " + entry.offered, textX, y + 80);
    }

    private static BufferedImage loadImage(File root, String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return null;
        }
        try {
            File file = new File(root, relativePath.replace('/', File.separatorChar));
            if (!file.isFile()) {
                return null;
            }
            return ImageIO.read(file);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void writeJpeg(BufferedImage image, File file, float quality) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            return;
        }
        ImageWriter writer = writers.next();
        ImageOutputStream out = null;
        try {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            out = ImageIO.createImageOutputStream(file);
            writer.setOutput(out);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            if (out != null) {
                out.close();
            }
            writer.dispose();
        }
    }

    private static String fit(Graphics2D g, String text, int maxWidth, int style, int size) {
        if (text == null) {
            return "";
        }
        g.setFont(font(size, style));
        FontMetrics metrics = g.getFontMetrics();
        if (metrics.stringWidth(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "...";
        int width = metrics.stringWidth(ellipsis);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (width + metrics.charWidth(c) > maxWidth) {
                break;
            }
            out.append(c);
            width += metrics.charWidth(c);
        }
        return out.append(ellipsis).toString();
    }

    private static int textWidth(Graphics2D g, String text) {
        return g.getFontMetrics().stringWidth(text);
    }

    private static Font font(int size, int style) {
        Font font = new Font("Microsoft YaHei", style, size);
        if (font.canDisplay('战')) {
            return font;
        }
        return new Font(Font.SANS_SERIF, style, size);
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }
}
