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
 * 把怪物危险度梯度渲染成一张可直接发送的整图。
 *
 * 输出 monster-tiers.png（无损）和 monster-tiers.jpg。
 * 只依赖 JDK 自带的 Java2D / ImageIO，不访问网络。
 */
public final class MonsterTierImageRenderer {
    private static final int WIDTH = 1600;
    private static final int PAD = 40;
    private static final int LABEL_W = 170;
    private static final int COLS = 5;
    private static final int GAP = 10;
    private static final int ENTRY_H = 126;
    private static final int HEADER_H = 222;
    private static final int ENTRY_W = (WIDTH - PAD * 2 - LABEL_W - (COLS - 1) * GAP) / COLS;

    private static final Color BG = new Color(0x0f1116);
    private static final Color PANEL2 = new Color(0x1d212a);
    private static final Color IMAGE_BG = new Color(0x0b0d11);
    private static final Color TEXT = new Color(0xe7e2d4);
    private static final Color MUTED = new Color(0x9aa1ad);
    private static final Color LINE = new Color(0x2b303b);
    private static final Color GOLD = new Color(0xe5c07b);

    private MonsterTierImageRenderer() {
    }

    public static void write(MonsterTierModel model, File root) throws Exception {
        BufferedImage image = render(model, root);
        ImageIO.write(image, "png", new File(root, "monster-tiers.png"));
        writeJpeg(image, new File(root, "monster-tiers.jpg"), 0.92F);
    }

    private static BufferedImage render(MonsterTierModel model, File root) {
        int height = layoutHeight(model);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setColor(BG);
            g.fillRect(0, 0, WIDTH, height);

            int y = drawHeader(g, model, PAD);
            for (MonsterTierGroup group : model.groups) {
                y = drawGroup(g, group, root, y);
            }
            g.setColor(MUTED);
            g.setFont(font(12, Font.PLAIN));
            g.drawString("TinyCat · local run history export · no external image source", PAD, height - 24);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static int layoutHeight(MonsterTierModel model) {
        int height = PAD + HEADER_H;
        for (MonsterTierGroup group : model.groups) {
            if (group.isEmpty()) {
                height += 86;
                continue;
            }
            height += 50;
            for (MonsterTier tier : MonsterTier.values()) {
                int count = group.countOf(tier);
                height += 30;
                if (count == 0) {
                    height += 20;
                    continue;
                }
                int rows = (count + COLS - 1) / COLS;
                height += rows * (ENTRY_H + GAP) + 8;
            }
            if (group.lowSampleCount > 0) {
                int rows = (group.lowSampleCount + COLS - 1) / COLS;
                height += 30 + rows * (ENTRY_H + GAP) + 8;
            }
            height += 14;
        }
        return height + PAD;
    }

    private static int drawHeader(Graphics2D g, MonsterTierModel model, int y) {
        g.setColor(GOLD);
        g.setFont(font(34, Font.BOLD));
        g.drawString("TinyCat Monster Danger Tier List", PAD, y + 34);
        g.setColor(MUTED);
        g.setFont(font(16, Font.PLAIN));
        g.drawString("怪物危险度梯度 · 平均实际战损", PAD, y + 60);

        y += 84;
        g.setFont(font(13, Font.PLAIN));
        g.setColor(TEXT);
        g.drawString("角色 / Character: " + model.characterZh + " / " + model.characterEn, PAD, y);
        g.drawString("日期 / Date: " + empty(model.startDate) + " ~ " + empty(model.endDate), PAD + 470, y);
        g.drawString("对局 / Runs: " + model.runCount, PAD + 900, y);

        y += 20;
        g.setColor(MUTED);
        g.drawString("样本门槛 / Sample gates: Boss ≥ " + model.bossMinSample
                + "    非 Boss / Non-boss ≥ " + model.nonBossMinSample
                + "    放弃局 / Abandons: " + (model.includeAbandons ? "包含" : "排除")
                + "    种子局 / Seeds: " + (model.seedFilter == null ? "" : model.seedFilter.labelZh), PAD, y);

        y += 22;
        g.drawString("生成 / Generated: " + model.generatedAt
                + "    低样本 / Low sample: " + model.lowSampleCount + " 项（保留，不参与分档）", PAD, y);

        y += 18;
        g.setFont(font(12, Font.PLAIN));
        g.drawString("指标 / Metric: 每场战斗的实际生命损失（战后回血计为负）。"
                + "T0 = Boss（按战损单独排序）；非 Boss（含精英）按幕内均值降序等人数分档，T1 最危险。"
                + "事件战斗与第四幕固定战斗不参与排名。", PAD, y);

        y += 18;
        g.drawString("Tiering: equal-count rank slices within each act, mean HP loss descending. "
                + "Bosses are ranked separately in T0. Event fights and Act 4 are excluded.", PAD, y);

        y += 18;
        g.drawString("样本占比与战损贡献在同幕同类（Boss 或非 Boss）内计算；净战损合计 ≤ 0 时贡献显示 —。", PAD, y);

        y += 18;
        g.drawString("Sample share and loss contribution are computed within the same act and category; "
                + "contribution is - when category net HP loss is <= 0.", PAD, y);
        return y + 24;
    }

    private static int drawGroup(Graphics2D g, MonsterTierGroup group, File root, int y) {
        g.setColor(TEXT);
        g.setFont(font(24, Font.BOLD));
        g.drawString(group.labelZh, PAD, y + 28);
        int zhWidth = g.getFontMetrics().stringWidth(group.labelZh);
        g.setColor(MUTED);
        g.setFont(font(13, Font.PLAIN));
        g.drawString(group.labelEn, PAD + zhWidth + 14, y + 28);
        if (group.lowSampleCount > 0) {
            String lowText = "样本不足 / Low sample: " + group.lowSampleCount;
            int lowWidth = g.getFontMetrics().stringWidth(lowText);
            g.drawString(lowText, WIDTH - PAD - lowWidth, y + 28);
        }
        y += 46;

        if (group.isEmpty()) {
            g.setColor(MUTED);
            g.setFont(font(14, Font.PLAIN));
            g.drawString("No data / 无数据", PAD + LABEL_W, y + 20);
            return y + 40;
        }

        for (MonsterTier tier : MonsterTier.values()) {
            List<MonsterTierEntry> entries = group.entriesOf(tier);
            int count = entries.size();
            Color tierColor = Color.decode(tier.color);
            g.setColor(tierColor);
            g.fillRoundRect(PAD, y + 4, 4, 24, 4, 4);
            g.setFont(font(22, Font.BOLD));
            g.drawString(tier.key, PAD + 12, y + 25);
            g.setColor(MUTED);
            g.setFont(font(11, Font.PLAIN));
            g.drawString(tierLabel(tier) + " · " + count, PAD + 46, y + 24);
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

        if (group.lowSampleCount > 0) {
            g.setColor(MUTED);
            g.fillRoundRect(PAD, y + 4, 4, 24, 4, 4);
            g.setFont(font(22, Font.BOLD));
            g.drawString("N/A", PAD + 12, y + 25);
            g.setFont(font(11, Font.PLAIN));
            g.drawString("样本不足 · 不参与分档 · " + group.lowSampleCount, PAD + 58, y + 24);
            y += 30;
            List<MonsterTierEntry> low = group.lowSampleEntries;
            for (int i = 0; i < low.size(); i++) {
                int col = i % COLS;
                int row = i / COLS;
                int x = PAD + LABEL_W + col * (ENTRY_W + GAP);
                int ey = y + row * (ENTRY_H + GAP);
                drawEntry(g, low.get(i), root, x, ey, MUTED);
            }
            int rows = (low.size() + COLS - 1) / COLS;
            y += rows * (ENTRY_H + GAP) + 8;
        }
        return y + 8;
    }

    private static String tierLabel(MonsterTier tier) {
        if (tier.isBoss()) {
            return "Boss · 单独排序";
        }
        return tier.labelZh + " · " + tier.labelEn;
    }

    private static void drawEntry(Graphics2D g,
                                  MonsterTierEntry entry,
                                  File root,
                                  int x,
                                  int y,
                                  Color tierColor) {
        g.setColor(PANEL2);
        g.fillRoundRect(x, y, ENTRY_W, ENTRY_H, 10, 10);
        g.setColor(LINE);
        g.setStroke(new BasicStroke(1.0F));
        g.drawRoundRect(x, y, ENTRY_W, ENTRY_H, 10, 10);

        int boxX = x + 8;
        int boxY = y + 8;
        int boxW = 76;
        int boxH = 62;
        g.setColor(IMAGE_BG);
        g.fillRoundRect(boxX, boxY, boxW, boxH, 6, 6);
        BufferedImage image = loadImage(root, entry.imagePath);
        if (image != null) {
            drawFitted(g, image, boxX, boxY, boxW, boxH);
        } else {
            g.setColor(MUTED);
            g.setFont(font(9, Font.PLAIN));
            g.drawString("NO IMAGE", boxX + 10, boxY + boxH / 2 + 4);
        }

        int textX = boxX + boxW + 9;
        int textW = x + ENTRY_W - textX - 8;
        g.setColor(TEXT);
        g.setFont(font(15, Font.BOLD));
        g.drawString(fit(g, entry.nameZh, textW, Font.BOLD, 15), textX, y + 26);
        g.setColor(MUTED);
        g.setFont(font(10, Font.PLAIN));
        g.drawString(fit(g, entry.nameEn, textW, Font.PLAIN, 10), textX, y + 42);

        g.setColor(tierColor);
        g.setFont(font(20, Font.BOLD));
        g.drawString(String.format(java.util.Locale.US, "%.2f", entry.meanHpLoss), textX, y + 68);
        int valueWidth = g.getFontMetrics().stringWidth(String.format(java.util.Locale.US, "%.2f", entry.meanHpLoss));
        g.setColor(MUTED);
        g.setFont(font(9, Font.PLAIN));
        g.drawString(" 平均战损", textX + valueWidth, y + 67);

        g.setColor(MUTED);
        g.setFont(font(9, Font.PLAIN));
        g.drawString("n=" + entry.sampleCount
                + "  95%CI " + fmt(entry.ci95Low) + " ~ " + fmt(entry.ci95High), textX, y + 84);
        g.drawString(fit(g, "样本 " + fmtShare(entry.sampleShare) + "% · 净 " + entry.totalHpLoss
                + " · 贡献 " + fmtShare(entry.lossShare) + "%", textW, Font.PLAIN, 9), textX, y + 98);
        String detail = entry.typeZh() + " / " + entry.typeEn()
                + (entry.isRanked()
                ? (entry.isBoss() ? "  Boss #" + entry.tierRank : "  总#" + entry.nonBossRank)
                : "  样本不足");
        g.drawString(fit(g, detail, textW, Font.PLAIN, 9), textX, y + 112);

        g.setColor(tierColor);
        g.setFont(font(10, Font.BOLD));
        String rank = !entry.isRanked()
                ? "n=" + entry.sampleCount
                : entry.isBoss() ? "BOSS #" + entry.tierRank : "#" + entry.nonBossRank;
        int rankWidth = g.getFontMetrics().stringWidth(rank);
        g.drawString(rank, x + ENTRY_W - rankWidth - 9, y + 16);
    }

    private static void drawFitted(Graphics2D g, BufferedImage image, int x, int y, int boxW, int boxH) {
        int width = image.getWidth();
        int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        double scale = Math.min((double) boxW / width, (double) boxH / height);
        int drawW = Math.max(1, (int) Math.round(width * scale));
        int drawH = Math.max(1, (int) Math.round(height * scale));
        int drawX = x + (boxW - drawW) / 2;
        int drawY = y + (boxH - drawH) / 2;
        g.drawImage(image, drawX, drawY, drawW, drawH, null);
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

    private static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }

    private static String fmtShare(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        return String.format(java.util.Locale.US, "%.1f", value);
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
