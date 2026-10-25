package tinycat.tier;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Collections;
import java.util.List;

import tinycat.tinycat;

/**
 * 导出包里的「图表」目录：一图完整 + 按前端指标页分图。
 *
 * <pre>
 * 图表/
 *   00-完整梯度图.png / .jpg          卡牌抓率 + BOSS 遗物 + 怪物危险度，全部拼在一张图上
 *   01-第一幕卡牌选取率.png / .jpg
 *   02-第一幕后卡牌选取率.png / .jpg
 *   03-BOSS遗物选取率.png / .jpg
 *   04-怪物危险度-第一幕.png / .jpg
 *   05-怪物危险度-第二幕.png / .jpg
 *   06-怪物危险度-第三幕.png / .jpg
 * </pre>
 *
 * 只用 Java2D 拼图：先按各段预算高度建一张总画布，再 translate 逐段绘制，
 * 全程只有一张大图在内存里，不产生中间副本。
 */
final class ExportImagePack {
    static final String DIR = "图表";

    private ExportImagePack() {
    }

    static void write(TierListModel tier, MonsterTierModel monster, File root) throws Exception {
        File dir = new File(root, DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            tinycat.logger.warn("ExportImagePack: cannot create " + dir.getAbsolutePath());
            return;
        }

        // 一图完整
        int tierHeight = TierListImageRenderer.sectionHeight(tier.groups);
        int monsterHeight = monster == null ? 0
                : MonsterTierImageRenderer.sectionHeight(monster.groups);
        int width = Math.max(TierListImageRenderer.WIDTH, MonsterTierImageRenderer.WIDTH);
        BufferedImage full = new BufferedImage(width, tierHeight + monsterHeight,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = full.createGraphics();
        try {
            TierListImageRenderer.drawSection(g, tier, root, tier.groups, tierHeight);
            if (monster != null && monsterHeight > 0) {
                g.translate(0, tierHeight);
                MonsterTierImageRenderer.drawSection(g, monster, root, monster.groups, monsterHeight);
                g.translate(0, -tierHeight);
            }
        } finally {
            g.dispose();
        }
        writePair(full, dir, "00-完整梯度图");

        // 按前端指标页分图
        int index = 1;
        List<TierListGroup> tierGroups = tier.groups;
        for (int i = 0; i < tierGroups.size(); i++) {
            TierListGroup group = tierGroups.get(i);
            BufferedImage section = TierListImageRenderer.render(tier, root,
                    Collections.singletonList(group));
            writePair(section, dir, name(index++, group.labelZh));
        }
        if (monster != null) {
            List<MonsterTierGroup> monsterGroups = monster.groups;
            for (int i = 0; i < monsterGroups.size(); i++) {
                MonsterTierGroup group = monsterGroups.get(i);
                BufferedImage section = MonsterTierImageRenderer.render(monster, root,
                        Collections.singletonList(group));
                writePair(section, dir, name(index++, "怪物危险度-" + group.labelZh));
            }
        }

        writeReadme(dir);
    }

    private static String name(int index, String label) {
        return String.format(java.util.Locale.US, "%02d-%s", index, safe(label));
    }

    private static String safe(String label) {
        if (label == null || label.isEmpty()) {
            return "未命名";
        }
        String value = label.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return value.length() > 60 ? value.substring(0, 60) : value;
    }

    private static void writePair(BufferedImage image, File dir, String baseName) throws Exception {
        ImageIO.write(image, "png", new File(dir, baseName + ".png"));
        TierListImageRenderer.writeJpeg(image, new File(dir, baseName + ".jpg"), 0.9F);
    }

    private static void writeReadme(File dir) throws Exception {
        StringBuilder text = new StringBuilder(1024);
        text.append("TinyCat 图表 / charts\n");
        text.append("====================\n\n");
        text.append("00-完整梯度图  卡牌抓率 + BOSS 遗物 + 怪物危险度，全部拼在一张图上（发图用这张）。\n");
        text.append("01 起          按前端指标页拆开的单页图，方便只看某一页时单独发。\n\n");
        text.append("*.png 无损，*.jpg 体积小（质量 90），内容完全一致。\n");
        text.append("数据与同一导出包里的 .xlsx / data.csv / data.json 同源，改任何一处都必须重新导出。\n");
        text.append("图片素材全部来自本地游戏与本地图包，不访问网络。\n");
        java.io.OutputStreamWriter writer = new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(new File(dir, "README.txt")),
                java.nio.charset.StandardCharsets.UTF_8);
        try {
            writer.write(text.toString());
        } finally {
            writer.close();
        }
    }
}
