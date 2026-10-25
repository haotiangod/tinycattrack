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
    static final int WIDTH = 1600;
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
        BufferedImage image = render(model, root, model.groups);
        String base = model.standardized ? "tierlist" + RateVariant.FILE_SUFFIX : "tierlist";
        ImageIO.write(image, "png", new File(root, base + ".png"));
        writeJpeg(image, new File(root, base + ".jpg"), 0.92F);
    }

    /** 渲染指定分组；供「一图完整」与「按指标分图」复用。 */
    static BufferedImage render(TierListModel model, File root, List<TierListGroup> groups) {
        int height = layoutHeight(groups, model.scope, model.standardized);
        BufferedImage image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            drawSection(g, model, root, groups, height);
        } finally {
            g.dispose();
        }
        return image;
    }

    /** 单段高度（不含其它段落），供整图拼接预算画布。 */
    static int sectionHeight(List<TierListGroup> groups) {
        return sectionHeight(groups, null, false);
    }

    /** 标准化视图用排名档（RS..RZ，卡牌另有 R00），高度必须按同一套档位算，否则画布会短一截。 */
    static int sectionHeight(List<TierListGroup> groups, TierListScope scope, boolean standardized) {
        return layoutHeight(groups, scope, standardized);
    }

    /**
     * 把一段内容画进现成的画布：调用方负责坐标（可先 translate），
     * 背景由本方法自己铺，所以多段拼接时不会互相覆盖。
     */
    static void drawSection(Graphics2D g,
                            TierListModel model,
                            File root,
                            List<TierListGroup> groups,
                            int height) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, height);

        int y = PAD;
        y = drawHeader(g, model, y);
        for (TierListGroup group : groups) {
            y = drawGroup(g, model, group, root, y);
        }
        g.setColor(MUTED);
        g.setFont(font(12, Font.PLAIN));
        g.drawString("TinyCat " + TierListExporter.MOD_VERSION
                + " · local export · no external image source", PAD, height - 24);
    }

    private static int layoutHeight(List<TierListGroup> groups, TierListScope scope, boolean standardized) {
        List<Tier> tiers = TierListExporter.tiersFor(scope, standardized);
        int height = PAD + 150;
        for (TierListGroup group : groups) {
            height += 54;
            if (group.entries.isEmpty()) {
                height += 40;
                continue;
            }
            for (Tier tier : tiers) {
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
        if (model.standardized) {
            // 同一份数据的第二张图，必须一眼看出是哪个口径，否则两张图会被当成重名
            g.setColor(new Color(0x7fd1e8));
            g.setFont(font(22, Font.BOLD));
            g.drawString("标准化率 STANDARDIZED", PAD + 340, y + 32);
        }
        g.setColor(MUTED);
        g.setFont(font(16, Font.PLAIN));
        g.drawString(metricSubtitle(model), PAD, y + 60);

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
        g.drawString(metricNote(model) + "图片全部来自本地游戏，不依赖外部图源。", PAD, y);
        return y + 22;
    }

    private static String metricSubtitle(TierListModel model) {
        String base = metricSubtitle(model.scope);
        return model.standardized ? base + " · 标准化率" : base;
    }

    private static String metricSubtitle(TierListScope scope) {
        if (scope == TierListScope.CAMPFIRE) {
            return "火堆行动梯度 · local run history export";
        }
        if (scope == TierListScope.CAMPFIRE_UPGRADE) {
            return "火堆偏好 · 被敲次数 / 候选次数";
        }
        if (scope == TierListScope.SHOP) {
            return "商店购买梯度 · local run history export";
        }
        if (scope == TierListScope.NEOW) {
            return "尼奥选项梯度 · local run history export";
        }
        if (scope == null) {
            return "全量梯度 · local run history export";
        }
        return "抓率梯度 · local run history export";
    }

    private static String metricNote(TierListModel model) {
        if (!model.standardized) {
            return metricNote(model.scope);
        }
        return "数值 = Plackett-Luce 标准化率（整屏选择拟合，合计 100%）；副标 = 原始计数与 95%CI。"
                + "每组独立拟合、各自合计 100%，不能跨组相加。"
                + "它回答「谁更值得选」，与界面占比回答的「实际点了哪个」不是同一个问题。";
    }

    private static String metricNote(TierListScope scope) {
        if (scope == TierListScope.CAMPFIRE) {
            return "占比 = 该行动次数 / 该幕火堆行动总数；每幕都含升级、睡觉、回忆、删牌、挖掘、举重。";
        }
        if (scope == TierListScope.CAMPFIRE_UPGRADE) {
            return "火堆偏好：主标 = 被敲次数 / 候选次数（每次锻造时，卡组里还能敲的卡牌种类数，同名只算一个选项）；"
                    + "分档按权重%，副标 12/16 = 被敲/候选，并附占锻造、占火堆；只统计火堆锻造。";
        }
        if (scope == TierListScope.SHOP) {
            return "占比 = 购买次数 / 该幕购买总次数（商店总数、有效商店数不计入分母）；删牌单独成组。";
        }
        if (scope == TierListScope.NEOW) {
            return "占比 = 该选项被选中次数 / 尼奥选择总次数；一行 = 一个「奖励+代价」选项，不做合并。";
        }
        return "抓率 = 被选取次数 / 被提供次数；0 档 = 从未被选取；SKIP 保留，颂钵并入 SKIP。";
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

        if (group.note != null && !group.note.isEmpty()) {
            // 睡觉不参与竞争榜，但数值重要，单独挂在组标题下
            g.setColor(MUTED);
            g.setFont(font(12, Font.PLAIN));
            g.drawString(group.note, PAD + LABEL_W, y + 6);
            y += 24;
        }

        if (group.entries.isEmpty()) {
            g.setColor(MUTED);
            g.setFont(font(14, Font.PLAIN));
            g.drawString("No data / 无数据", PAD + LABEL_W, y + 20);
            return y + 40;
        }

        boolean countPrimary = countPrimary(model.scope);
        for (Tier tier : TierListExporter.tiersFor(model.scope, model.standardized)) {
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
                drawEntry(g, entries.get(i), root, x, ey, tierColor, countPrimary, model.scope);
            }
            int rows = (count + COLS - 1) / COLS;
            y += rows * (ENTRY_H + GAP) + 8;
        }
        return y + 8;
    }

    /** 火堆 / 商店主标写次数（分档就是按次数切的），尼奥与抓率表主标写百分比。 */
    /**
     * 卡片主标是否显示「次数」。
     *
     * 火堆、商店是重尾计数（一件事做几十上百次），次数比百分比直观；
     * 尼奥每局只选一次、各选项分母也不一样，次数会误导（21 次 vs 11.09% 谁更值得选？），
     * 所以尼奥改成主标显示百分比，次数退到副标。
     */
    private static boolean countPrimary(TierListScope scope) {
        return scope == TierListScope.CAMPFIRE || scope == TierListScope.SHOP;
    }

    private static void drawEntry(Graphics2D g, TierEntry entry, File root, int x, int y, Color tierColor,
                                  boolean countPrimary, TierListScope scope) {
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
        if (countPrimary) {
            // 主标 = 原始行动次数（与分档同源）；副标 = 占该幕总数 % + 分子/分母
            g.drawString(entry.picked + " 次", textX, y + 66);
            g.setColor(MUTED);
            g.setFont(font(9, Font.PLAIN));
            String secondary = String.format(java.util.Locale.US, "%.2f%% · %d/%d",
                    entry.rate, entry.picked, entry.offered);
            if (entry.context != null && !entry.context.isEmpty()) {
                secondary += " · " + entry.context;
            }
            g.drawString(fit(g, secondary, textW, Font.PLAIN, 9), textX, y + 80);
        } else {
            g.drawString(String.format(java.util.Locale.US, "%.2f%%", entry.rate), textX, y + 66);
            g.setColor(MUTED);
            g.setFont(font(9, Font.PLAIN));
            String secondary = scope == TierListScope.CAMPFIRE_UPGRADE
                    ? entry.picked + "/" + entry.offered
                    + (entry.context == null || entry.context.isEmpty() ? "" : " · " + entry.context)
                    : entry.picked + " / " + entry.offered;
            if (scope != TierListScope.CAMPFIRE_UPGRADE
                    && entry.context != null && !entry.context.isEmpty()) {
                secondary += " · " + entry.context;
            }
            g.drawString(fit(g, secondary, textW, Font.PLAIN, 9), textX, y + 80);
        }
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

    static void writeJpeg(BufferedImage image, File file, float quality) throws Exception {
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
