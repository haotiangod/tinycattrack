package tinycat.tier;

import tinycat.stats.Card;
import tinycat.stats.Neow;
import tinycat.stats.Rate;
import tinycat.stats.SeedFilter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 自检：用假数据跑一遍「简单率 / 标准化率 两份视图」的完整导出链路。
 *
 * 不依赖游戏进程与 .run 文件，纯 Java2D + 本地文件系统，所以能离线跑：
 *   java -cp "build/classes/java/main;<游戏 4 个 jar>" tinycat.tier.VariantSelfCheck build/selfcheck
 *
 * 断言的是行为，不是数字：两份视图必须都落盘、标准化视图必须按标准化率重排与重分档、
 * HTML 必须互相链接、文件名必须是 tierlist_standardized.* 与 *-标准化率.*。
 */
public final class VariantSelfCheck {
    private static int failed = 0;

    private static void check(boolean ok, String what) {
        System.out.println((ok ? "PASS  " : "FAIL  ") + what);
        if (!ok) {
            failed++;
        }
    }

    public static void main(String[] args) throws Exception {
        File root = new File(args.length > 0 ? args[0] : "build/selfcheck");
        wipe(root);
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("cannot create " + root.getAbsolutePath());
        }

        TierListModel plain = sample();
        TierListModel std = RateVariant.standardized(plain);

        check(RateVariant.hasStandardized(plain), "hasStandardized 认得出标准化率");
        check(std != null, "standardized() 生成了第二份视图");
        check(RateVariant.standardized(noStandard()) == null, "没有标准化率的表返回 null（不产出第二份）");

        List<TierEntry> entries = std == null ? new ArrayList<TierEntry>() : std.groups.get(0).entries;
        check(entries.size() == 4, "标准化视图保留全部 4 条：" + entries.size());
        check(!entries.isEmpty() && "B".equals(entries.get(0).id),
                "按标准化率重排：B(12.0) 排第一，实际 " + (entries.isEmpty() ? "-" : entries.get(0).id));
        check(!entries.isEmpty() && Math.abs(entries.get(0).rate - 12.0D) < 1e-9,
                "rate 字段被换成标准化率：" + (entries.isEmpty() ? "-" : entries.get(0).rate));
        check(!entries.isEmpty() && entries.get(0).context.contains("占比 20.00%"),
                "备注反转为「占比」：" + (entries.isEmpty() ? "-" : entries.get(0).context));
        check(std != null && std.standardized, "model.standardized 标记为 true");
        check(!plain.standardized, "原模型仍标记为简单率");

        TierListImageRenderer.write(plain, root);
        TierListImageRenderer.write(std, root);
        for (String name : Arrays.asList("tierlist.png", "tierlist.jpg",
                "tierlist_standardized.png", "tierlist_standardized.jpg")) {
            check(new File(root, name).isFile(), "生成 " + name);
        }

        ExportImagePack.write(plain, std, null, root);
        File charts = new File(root, ExportImagePack.DIR);
        check(new File(charts, "00-完整梯度图.png").isFile(), "图表/00-完整梯度图.png（简单率）");
        check(new File(charts, "00-完整梯度图-标准化率.png").isFile(), "图表/00-完整梯度图-标准化率.png");
        check(new File(charts, "00-完整梯度图-标准化率.jpg").isFile(), "图表/00-完整梯度图-标准化率.jpg");
        check(new File(charts, "01-尼奥开局选项（奖励 + 代价）-标准化率.png").isFile(),
                "图表/01-<分组名>-标准化率.png");

        TierListExporter.writeHtml(plain, new File(root, "tierlist.html"), true);
        TierListExporter.writeHtml(std, new File(root, "tierlist_standardized.html"), true);
        String plainHtml = read(new File(root, "tierlist.html"));
        String stdHtml = read(new File(root, "tierlist_standardized.html"));
        check(plainHtml.contains("tierlist_standardized.html"), "简单率 HTML 链到标准化视图");
        check(stdHtml.contains("标准化率，不是占比"), "标准化 HTML 明确声明口径");
        check(stdHtml.contains("tierlist.html\">简单率视图"), "标准化 HTML 反向链回简单率");
        check(stdHtml.contains("12.00%"), "标准化 HTML 显示的数值是标准化率");
        check(plainHtml.contains("计数占比") || plainHtml.contains("标准化"), "简单率 HTML 仍带标准化备注");

        checkCards();
        checkNeow();

        System.out.println();
        System.out.println(failed == 0 ? "ALL PASS" : (failed + " FAILED"));
        if (failed != 0) {
            System.exit(1);
        }
    }

    /**
     * 卡牌表：SKIP 必须参与、颂钵并入 SKIP、高分牌不能被高频牌反超。
     *
     * 这些是数值口径的回归线：换模型时最先坏掉的就是这几条。
     */
    private static void checkCards() {
        // 用户报告的原始反例：快检 97.9%（96 次出现、2 次输）对冷头 80%（304 次出现、64 次输），
        // 且快检对冷头直接交手 4:0。旧的两两 BT + prior=1 会把冷头排在快检前面。
        List<Card[]> blocks = new ArrayList<Card[]>();
        addBlock(blocks, 90, "Fast", "Junk", "SKIP");
        addBlock(blocks, 2, "Dark", "Fast", "SKIP");
        addBlock(blocks, 240, "Cold", "Junk", "SKIP");
        addBlock(blocks, 60, "Junk", "Cold", "SKIP");
        addBlock(blocks, 4, "Fast", "Cold", "SKIP");

        List<TierEntry> entries = cardEntries();
        CardRanker.attach(blocks, entries);
        double sum = 0.0D;
        for (TierEntry entry : entries) {
            sum += entry.standardized;
        }
        check(Math.abs(sum - 100.0D) < 1e-6, "卡牌标准化率合计 100%：" + sum);
        check(share(entries, "Fast") > share(entries, "Cold"),
                "直接交手全胜的高分牌排在堆出场次数的牌之前：Fast "
                        + share(entries, "Fast") + " vs Cold " + share(entries, "Cold"));
        check(lookup(entries, "SKIP").standardized > 0.0D, "SKIP 作为对手拿到非零份额");
        check(lookup(entries, "SKIP").stdHigh > lookup(entries, "SKIP").stdLow, "卡牌带 95%CI");
        check(lookup(entries, "Fast").context.startsWith("标准化 "), "卡牌备注写标准化率");

        // 颂钵并入 SKIP：全写成 SKIP 后结果必须一模一样
        List<Card[]> merged = new ArrayList<Card[]>();
        addBlock(merged, 90, "Fast", "Junk", "Singing Bowl");
        addBlock(merged, 2, "Dark", "Fast", "Singing Bowl");
        addBlock(merged, 240, "Cold", "Junk", "Singing Bowl");
        addBlock(merged, 60, "Junk", "Cold", "Singing Bowl");
        addBlock(merged, 4, "Fast", "Cold", "Singing Bowl");
        List<TierEntry> same = cardEntries();
        CardRanker.attach(merged, same);
        check(Math.abs(share(same, "SKIP") - share(entries, "SKIP")) < 1e-9,
                "颂钵并入 SKIP：份额与直接写成 SKIP 一致");

        // 被最小样本过滤掉的卡仍然是对手：多一个只赢 A 的强敌，A 的份额必须下降
        List<Card[]> extra = new ArrayList<Card[]>(blocks);
        addBlock(extra, 40, "Rare", "Fast", "Junk");
        List<TierEntry> pruned = cardEntries();
        CardRanker.attach(extra, pruned);
        check(Math.abs(share(pruned, "Fast") - share(entries, "Fast")) > 1e-6,
                "不在表里的卡仍然参与分母（Fast 份额被强敌压低）");

        // 卡牌视图的副标只写占比：图片宽度不够，CI 走表格列
        TierListModel view = RateVariant.standardized(cardModel(entries));
        check(view != null && view.scope == TierListScope.CARDS_ACT1, "卡牌表也能生成标准化视图");
        check(view != null && view.groups.get(0).entries.get(0).context.startsWith("占比 "),
                "卡牌标准化视图副标只写占比："
                        + (view == null ? "-" : view.groups.get(0).entries.get(0).context));

        // 分幕与合并视图都必须按卡牌口径处理（备注写占比，而不是 CI）
        check(TierListScope.CARDS_ACT1.isCards() && TierListScope.CARDS_ACT2.isCards()
                        && TierListScope.CARDS_ACT3_4.isCards() && TierListScope.CARDS_AFTER_ACT1.isCards()
                        && TierListScope.CARDS_ALL_ACTS.isCards(),
                "四个分幕范围与打包范围都认作卡牌表");
        check(!TierListScope.CARDS_ALL_ACTS.isRankBased(), "卡牌打包视图不是排名型");
        for (TierListScope scope : Arrays.asList(TierListScope.CARDS_ACT2,
                TierListScope.CARDS_ACT3_4, TierListScope.CARDS_AFTER_ACT1, TierListScope.CARDS_ALL_ACTS)) {
            TierListModel variant = RateVariant.standardized(scopeModel(entries, scope));
            check(variant != null && variant.groups.get(0).entries.get(0).context.startsWith("占比 "),
                    scope.key + " 的标准化视图副标写占比");
        }

        // 标准化卡牌必须用排名档（RS..RZ），否则图片里每一档都是空的——这就是空图那个 bug
        check(view != null, "标准化卡牌视图存在");
        if (view != null) {
            List<TierEntry> viewEntries = view.groups.get(0).entries;
            int placed = 0;
            for (Tier tier : TierListExporter.tiersFor(TierListScope.CARDS_ACT1, true)) {
                placed += view.groups.get(0).entriesOf(tier).size();
            }
            check(!viewEntries.isEmpty() && placed == viewEntries.size(),
                    "标准化卡牌的每一条都落进排名档：" + placed + "/" + viewEntries.size());
            check(TierListExporter.tiersFor(TierListScope.CARDS_ACT1).get(0) == Tier.S,
                    "简单率卡牌视图仍用 S..F 档");
        }

        // 图片路径必须在 standardized() 之前写好（导出器已改成先导图、后建视图）
        List<TierEntry> withImages = cardEntries();
        CardRanker.attach(blocks, withImages);
        for (TierEntry entry : withImages) {
            entry.imagePath = "images/" + entry.id + ".png";
        }
        TierListModel copy = RateVariant.standardized(cardModel(withImages));
        check(copy != null && "images/Fast.png".equals(lookup(copy.groups.get(0).entries, "Fast").imagePath),
                "标准化视图复制图片路径："
                        + (copy == null ? "-" : lookup(copy.groups.get(0).entries, "Fast").imagePath));
    }

    /**
     * 尼奥：全胜项分不出先后时必须并列；并列组内按出现次数从多到少排（样本大的更可信）。
     *
     * 造的数：A 8/8，且每次都和强项 F 同屏（后验均值会更高）；B 21/21。
     * 两者「谁更强」的把握不足 95%，所以并列——此时排在前面的必须是 21 次的 B，
     * 而不是后验均值更高的 A。这正是用户要求的「相等时大样本在前」。
     */
    private static void checkNeow() {
        Neow a = new Neow("TWO_FIFTY_GOLD", "PERCENT_DAMAGE");
        Neow b = new Neow("REMOVE_TWO", "TEN_PERCENT_HP_LOSS");
        Neow c = new Neow("HUNDRED_GOLD", "NONE");
        Neow d = new Neow("REMOVE_CARD", "NONE");
        Neow e = new Neow("UPGRADE_CARD", "NONE");
        Neow f = new Neow("ONE_RARE_RELIC", "NONE");

        List<Neow[]> choices = new ArrayList<Neow[]>();
        for (int i = 0; i < 8; i++) {
            choices.add(new Neow[]{a, f, e});
        }
        for (int i = 0; i < 21; i++) {
            choices.add(new Neow[]{b, d, e});
        }
        for (int i = 0; i < 20; i++) {
            choices.add(new Neow[]{f, d, e});
        }
        for (int i = 0; i < 20; i++) {
            choices.add(new Neow[]{c, d, e});
        }
        for (int i = 0; i < 10; i++) {
            choices.add(new Neow[]{d, c, e});
        }

        Map<Neow, int[]> counts = new LinkedHashMap<Neow, int[]>();
        for (Neow[] block : choices) {
            for (Neow neow : block) {
                int[] count = counts.get(neow);
                if (count == null) {
                    count = new int[2];
                    counts.put(neow, count);
                }
                count[1]++;
            }
            counts.get(block[0])[0]++;
        }
        List<Rate<Neow>> rates = new ArrayList<Rate<Neow>>();
        for (Map.Entry<Neow, int[]> entry : counts.entrySet()) {
            Rate<Neow> rate = new Rate<Neow>(entry.getKey());
            rate.win = entry.getValue()[0];
            rate.loss = entry.getValue()[1] - rate.win;
            rates.add(rate);
        }

        List<NeowRanker.Row> rows = NeowRanker.rank(rates, choices, 0);
        check(rows.size() == 6, "尼奥表保留全部 6 个选项：" + rows.size());
        if (rows.size() < 2) {
            return;
        }
        NeowRanker.Row first = rows.get(0);
        NeowRanker.Row second = rows.get(1);
        check(first.neow.equals(b), "并列组内出现次数多的排前面（B 21 次）：" + neowLabel(first));
        check(second.neow.equals(a), "8 次的 A 排第二：" + neowLabel(second));
        check(second.tieGroup == first.tieGroup, "A / B 同一并列组：" + first.tieGroup);
        check(first.tied() && second.tied(), "并列组标记为并列");
        check(first.tieStart == 1 && first.tieEnd == 2, "并列范围写成 1–2："
                + first.tieStart + "–" + first.tieEnd);
        check(first.standardized < second.standardized,
                "排序不是按后验均值（B " + first.standardized + " < A " + second.standardized + "）");
        check(first.rankOne >= 0.0D && first.rankOne <= 1.0D, "P(第一) 在 0~1：" + first.rankOne);
        NeowRanker.Row mid = null;
        for (NeowRanker.Row row : rows) {
            if (row.neow.equals(c)) {
                mid = row;
            }
        }
        check(mid != null && mid.tieGroup > first.tieGroup, "C 不与全胜组并列");
    }

    private static String neowLabel(NeowRanker.Row row) {
        return row.neow.bonus + "+" + row.neow.cost + " (" + row.offered + " 次)";
    }

    private static List<TierEntry> cardEntries() {
        List<TierEntry> entries = new ArrayList<TierEntry>();
        entries.add(new TierEntry("Fast", "Fast", "快检", 96, 94, 97.9D, Tier.RS, "card"));
        entries.add(new TierEntry("Cold", "Cold", "冷头", 304, 240, 80.0D, Tier.RS, "card"));
        entries.add(new TierEntry("Junk", "Junk", "杂项", 390, 60, 15.4D, Tier.RS, "card"));
        entries.add(new TierEntry("Dark", "Dark", "漆黑", 2, 2, 100.0D, Tier.RS, "card"));
        entries.add(new TierEntry("SKIP", "SKIP", "跳过", 396, 0, 0.0D, Tier.RS, "card"));
        return entries;
    }

    private static TierEntry lookup(List<TierEntry> entries, String id) {
        for (TierEntry entry : entries) {
            if (entry.id.equals(id)) {
                return entry;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static double share(List<TierEntry> entries, String id) {
        return lookup(entries, id).standardized;
    }

    /** 造 count 次「第一个被选中、其余同屏未选」的选择块。 */
    private static void addBlock(List<Card[]> blocks, int count, String... names) {
        for (int i = 0; i < count; i++) {
            Card[] block = new Card[names.length];
            for (int j = 0; j < names.length; j++) {
                block[j] = new Card(names[j], 0);
            }
            blocks.add(block);
        }
    }

    private static TierListModel cardModel(List<TierEntry> entries) {
        return scopeModel(entries, TierListScope.CARDS_ACT1);
    }

    private static TierListModel scopeModel(List<TierEntry> entries, TierListScope scope) {
        List<TierListGroup> groups = new ArrayList<TierListGroup>();
        groups.add(new TierListGroup(scope.key, scope.labelEn, scope.labelZh, entries));
        return new TierListModel("2026-01-01 00:00:00", "DEFECT", "Defect", "机器人", false,
                "2025-01", "2026-10", true, SeedFilter.ALL, 0, 142, scope, groups);
    }

    private static TierListModel sample() {
        List<TierEntry> entries = new ArrayList<TierEntry>();
        entries.add(entry("A", 100, 50, 50.0D, 8.0D));
        entries.add(entry("B", 200, 40, 20.0D, 12.0D));
        entries.add(entry("C", 50, 45, 90.0D, 3.0D));
        entries.add(entry("D", 30, 0, 0.0D, 0.0D));
        return model(entries);
    }

    private static TierListModel noStandard() {
        List<TierEntry> entries = new ArrayList<TierEntry>();
        TierEntry e = new TierEntry("X", "X", "X", 100, 50, 50.0D, Tier.RS, "card");
        entries.add(e);
        return model(entries);
    }

    private static TierEntry entry(String id, int offered, int picked, double rate, double std) {
        TierEntry entry = new TierEntry(id, id, id, offered, picked, rate, Tier.RS, "neow");
        entry.standardized = std;
        entry.stdLow = Math.max(0.0D, std - 1.0D);
        entry.stdHigh = std + 1.0D;
        entry.context = "标准化 " + std + "%";
        return entry;
    }

    private static TierListModel model(List<TierEntry> entries) {
        List<TierListGroup> groups = new ArrayList<TierListGroup>();
        groups.add(new TierListGroup("neow_options", "Neow options (bonus + cost)",
                "尼奥开局选项（奖励 + 代价）", entries));
        return new TierListModel("2026-01-01 00:00:00", "DEFECT", "Defect", "机器人", false,
                "2025-01", "2026-10", true, SeedFilter.ALL, 0, 142, TierListScope.NEOW, groups);
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void wipe(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                wipe(child);
            }
        }
        file.delete();
    }
}
