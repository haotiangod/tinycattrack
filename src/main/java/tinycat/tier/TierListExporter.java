package tinycat.tier;

import com.badlogic.gdx.Gdx;
import com.google.gson.GsonBuilder;
import tinycat.tinycat;
import tinycat.ui.MoreStatsScreen;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 生成 Tier List 导出包：
 *
 *   tierlist-export/TinyCat_TierList_<角色>_<时间>/
 *     ├── data.csv
 *     ├── data.json
 *     ├── tierlist.html
 *     ├── README.txt
 *     ├── 图表/                        # 00-完整梯度图 + 按指标页分图（png/jpg）
 *     ├── 总体统计.xlsx
 *     ├── 第一幕卡牌选取率.xlsx
 *     ├── 第二幕卡牌选取率.xlsx
 *     ├── 第三、四幕卡牌选取率.xlsx
 *     ├── 第一幕后卡牌选取率.xlsx
 *     ├── ...（其余按统计界面分页的中文 Excel）
 *     └── images/
 *          ├── cards/*.png
 *          ├── relics/*.png
 *          ├── skip.png
 *          └── placeholder.png
 *
 * 另外在同级目录生成同名 .zip。所有图片都来自本地游戏内存或本地 Pixmap，
 * 不依赖任何外部图源/网络。
 */
public final class TierListExporter {
    private static final String EXPORT_ROOT = "tierlist-export";
    /** 从 jar 里的 ModTheSpire.json 读版本，避免导出页面永远显示旧版本号。 */
    static final String MOD_VERSION = readModVersion();

    private static String readModVersion() {
        try (java.io.InputStream in = TierListExporter.class.getResourceAsStream("/ModTheSpire.json")) {
            if (in != null) {
                java.util.Scanner scanner = new java.util.Scanner(in, "UTF-8").useDelimiter("\\A");
                String text = scanner.hasNext() ? scanner.next() : "";
                java.util.regex.Matcher matcher = java.util.regex.Pattern
                        .compile("\"version\"\\s*:\\s*\"([^\"]+)\"").matcher(text);
                if (matcher.find()) {
                    return matcher.group(1);
                }
            }
        } catch (Throwable ignored) {
            // 读不到就退回下面的常量
        }
        return "4.5.1";
    }
    private static final String[] STAT_FILES = {
            "总体统计.xlsx",
            "第一幕卡牌选取率.xlsx",
            "第二幕卡牌选取率.xlsx",
            "第三、四幕卡牌选取率.xlsx",
            "第一幕后卡牌选取率.xlsx",
            "卡组中的卡牌胜率.xlsx",
            "尼奥奖励.xlsx",
            "BOSS遗物.xlsx",
            "遭遇战伤害.xlsx",
            "怪物危险度.xlsx",
            "遭遇战死亡率与药水.xlsx",
            "遗物胜率.xlsx",
            "各幕事件胜率.xlsx",
            "火堆行动.xlsx",
            "商店行动.xlsx"
    };

    private TierListExporter() {
    }

    /**
     * 该范围用哪些档位渲染。
     *
     * 抓率型（卡牌 / BOSS 遗物）用 S..F + 0；排名型（火堆 / 商店 / 尼奥）用 RS..RD。
     * 标准化率是强度占比，量级与抓率完全不同（不是 0~100% 的实际被选比例），
     * 所以标准化视图统一用排名档，否则卡牌表会拿 S..F 去套 RS..RZ 的条目，整张表全空。
     */
    public static List<Tier> tiersFor(TierListScope scope, boolean standardized) {
        List<Tier> out = new ArrayList<Tier>();
        boolean rank = standardized || (scope != null && scope.isRankBased());
        // R00 只出现在卡牌标准化视图里：长尾切成两档后整表 7 档，
        // 其它视图没有这么多 <1% 条目，列出来也只会是空行。
        boolean splitTail = standardized && (scope == null || scope.splitsStandardTail());
        for (Tier tier : Tier.values()) {
            if (tier.isRankBased() == rank && (tier != Tier.RZZ || splitTail)) {
                out.add(tier);
            }
        }
        return out;
    }

    public static List<Tier> tiersFor(TierListScope scope) {
        return tiersFor(scope, false);
    }

    public static String export(MoreStatsScreen screen) throws Exception {
        return export(screen, TierListScope.ALL);
    }

    public static String export(MoreStatsScreen screen, TierListScope scope) throws Exception {
        TierListModel model = TierListBuilder.build(screen, scope);
        File exportRoot = Gdx.files.local(EXPORT_ROOT).file();
        if (!ensureDir(exportRoot)) {
            // 游戏目录可能不可写；退回用户目录，仍然完全本地
            exportRoot = Gdx.files.external("TinyCat/" + EXPORT_ROOT).file();
            if (!ensureDir(exportRoot)) {
                throw new IOException("cannot create export directory: " + exportRoot.getAbsolutePath());
            }
            tinycat.logger.warn("TierList: game directory not writable, using " + exportRoot.getAbsolutePath());
        }

        String baseName = folderName(model);
        File root = new File(exportRoot, baseName);
        int suffix = 2;
        while (root.exists()) {
            root = new File(exportRoot, baseName + "_" + suffix);
            suffix++;
        }
        if (!root.mkdirs() && !root.isDirectory()) {
            throw new IOException("cannot create export directory: " + root.getAbsolutePath());
        }

        File imagesDir = new File(root, "images");
        File cacheDir = new File(exportRoot, "cache/v1");
        TierImageExporter imageExporter = new TierImageExporter(imagesDir, cacheDir);
        ensureCacheReadme(cacheDir);
        try {
            for (TierListGroup group : model.groups) {
                for (TierEntry entry : group.entries) {
                    entry.imagePath = imageExporter.export(entry);
                }
            }
            // 必须在图片导出之后构建：标准化视图复制条目时会把 imagePath 一起带走，
            // 早建会让第二份视图每张卡都缺图
            TierListModel standard = RateVariant.standardized(model);
            writeCsv(model, new File(root, "data.csv"));
            writeJson(model, new File(root, "data.json"));
            AllStatsExporter.write(screen, model, root, model.scope);
            MonsterTierModel monsterTier = null;
            if (model.scope == null || model.scope == TierListScope.ALL) {
                // 「全部导出」把怪物危险度梯度表一并写进同一个包；
                // 单独导出卡牌/Boss 遗物时不带战损梯度表。
                try {
                    monsterTier = MonsterTierExporter.writeInto(screen, root);
                } catch (Exception t) {
                    tinycat.logger.warn("TierList: monster danger tiers not bundled", t);
                }
            }
            try {
                TierListImageRenderer.write(model, root);
                if (standard != null) {
                    // 第二张整图：同一份数据的标准化率视图（tierlist_standardized.png / .jpg）
                    TierListImageRenderer.write(standard, root);
                }
            } catch (Exception t) {
                tinycat.logger.warn("TierList: failed to render shareable image", t);
            }
            try {
                // 图表/：一张完整图 + 按前端指标页的分图（含怪物危险度）
                ExportImagePack.write(model, standard, monsterTier, root);
            } catch (Exception t) {
                tinycat.logger.warn("TierList: failed to render chart pack", t);
            }
            writeHtml(model, new File(root, "tierlist.html"), standard != null);
            if (standard != null) {
                writeHtml(standard, new File(root, "tierlist" + RateVariant.FILE_SUFFIX + ".html"), true);
            }
            writeReadme(model, new File(root, "README.txt"), standard != null);
        } finally {
            imageExporter.dispose();
        }

        File zip = new File(exportRoot, root.getName() + ".zip");
        if (zip.exists() && !zip.delete()) {
            throw new IOException("cannot overwrite zip: " + zip.getAbsolutePath());
        }
        zipDirectory(root, zip);
        tinycat.logger.info("TierList exported: " + zip.getAbsolutePath());
        return zip.getAbsolutePath();
    }

    private static String folderName(TierListModel model) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String character = model.isAggregate() ? "ALL_CHARACTERS" : model.characterId;
        if (character == null) {
            character = "UNKNOWN";
        }
        String scope = model.scope == null ? "all" : model.scope.key;
        return "TinyCat_TierList_" + character + "_" + scope + "_" + timestamp;
    }

    private static void writeCsv(TierListModel model, File file) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write('\uFEFF');
            boolean rank = model.scope != null && model.scope.isRankBased();
            if (rank && model.scope == TierListScope.NEOW) {
                writer.write("角色ID,角色类型,种子局筛选,分组,类型,条目ID,英文名,中文名,分母(尼奥选择总次数),次数,占比(%),梯度,备注,图片\n");
            } else if (rank && model.scope == TierListScope.CAMPFIRE_UPGRADE) {
                writer.write("角色ID,角色类型,种子局筛选,分组,类型,条目ID,英文名,中文名,候选次数,被敲次数,火堆权重(%),梯度,备注(占锻造/占火堆),图片\n");
            } else {
                // 标准化率三列对没有标准化口径的表（BOSS 遗物）留空，但列位固定：
                // 同一份导出的 CSV 只该有一种列顺序，读者不该按 scope 猜第几列是什么
                writer.write(rank
                        ? "角色ID,角色类型,种子局筛选,分组,类型,条目ID,英文名,中文名,分母(总次数),次数,占比(%),梯度,备注,图片\n"
                        : "角色ID,角色类型,种子局筛选,分组,类型,条目ID,英文名,中文名,提供次数,选取次数,选取率(%),梯度,"
                        + "标准化率(%),标准化CI下(%),标准化CI上(%),备注,图片\n");
            }
            for (TierListGroup group : model.groups) {
                for (TierEntry entry : group.entries) {
                    writer.write(csv(model.characterId));
                    writer.write(',');
                    writer.write(csv(model.isAggregate() ? "全角色汇总" : "单角色"));
                    writer.write(',');
                    writer.write(csv(model.seedFilter.labelZh));
                    writer.write(',');
                    writer.write(csv(group.labelZh));
                    writer.write(',');
                    writer.write(csv(typeZh(entry.type)));
                    writer.write(',');
                    writer.write(csv(entry.id));
                    writer.write(',');
                    writer.write(csv(entry.nameEn));
                    writer.write(',');
                    writer.write(csv(entry.nameZh));
                    writer.write(',');
                    writer.write(Integer.toString(entry.offered));
                    writer.write(',');
                    writer.write(Integer.toString(entry.picked));
                    writer.write(',');
                    writer.write(formatRate(entry.rate));
                    writer.write(',');
                    writer.write(csv(entry.tier == null ? "" : entry.tier.key));
                    writer.write(',');
                    if (rank) {
                        writer.write(csv(entry.context));
                        writer.write(',');
                    } else {
                        boolean hasStandard = entry.standardized >= 0.0D;
                        writer.write(hasStandard ? formatRate(entry.standardized) : "");
                        writer.write(',');
                        writer.write(hasStandard ? formatRate(entry.stdLow) : "");
                        writer.write(',');
                        writer.write(hasStandard ? formatRate(entry.stdHigh) : "");
                        writer.write(',');
                        writer.write(csv(entry.context));
                        writer.write(',');
                    }
                    writer.write(csv(imagePath(entry)));
                    writer.write('\n');
                }
            }
        } finally {
            writer.close();
        }
    }

    private static void writeJson(TierListModel model, File file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 2);
        root.put("generated_at", model.generatedAt);
        root.put("mod", "TinyCat");
        root.put("mod_version", MOD_VERSION);

        Map<String, Object> character = new LinkedHashMap<String, Object>();
        character.put("id", model.characterId);
        character.put("kind", model.characterKind());
        character.put("count", model.characterCount());
        character.put("name_en", model.characterEn);
        character.put("name_zh", model.characterZh);
        character.put("rotate", model.rotate);
        root.put("character", character);
        root.put("character_count", model.characterCount());

        Map<String, Object> scope = new LinkedHashMap<String, Object>();
        scope.put("key", model.scope == null ? "all" : model.scope.key);
        scope.put("label_en", model.scope == null ? "All pick rates" : model.scope.labelEn);
        scope.put("label_zh", model.scope == null ? "全部抓率" : model.scope.labelZh);
        root.put("scope", scope);

        Map<String, Object> dateRange = new LinkedHashMap<String, Object>();
        dateRange.put("start", model.startDate);
        dateRange.put("end", model.endDate);
        root.put("date_range", dateRange);

        Map<String, Object> filters = new LinkedHashMap<String, Object>();
        filters.put("include_abandons", model.includeAbandons);
        filters.put("seed_filter", model.seedFilter.key);
        filters.put("min_sample", model.minSample);
        filters.put("a20_only", true);
        filters.put("current_save_slot_only", true);
        root.put("filters", filters);
        root.put("run_count", model.runCount);

        List<Map<String, Object>> tiers = new ArrayList<Map<String, Object>>();
        for (Tier tier : tiersFor(model.scope, model.standardized)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", tier.key);
            item.put("label_en", tier.labelEn);
            item.put("label_zh", tier.labelZh);
            item.put("min_rate", tier.minRate);
            item.put("max_rate", tier.maxRate);
            item.put("color", tier.color);
            tiers.add(item);
        }
        root.put("tiers", tiers);

        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (TierListGroup group : model.groups) {
            Map<String, Object> groupJson = new LinkedHashMap<String, Object>();
            groupJson.put("key", group.key);
            groupJson.put("label_en", group.labelEn);
            groupJson.put("label_zh", group.labelZh);
            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
            for (TierEntry entry : group.entries) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", entry.id);
                item.put("type", entry.type);
                item.put("name_en", entry.nameEn);
                item.put("name_zh", entry.nameZh);
                item.put("offered", entry.offered);
                item.put("picked", entry.picked);
                item.put("pick_rate_pct", round2(entry.rate));
                item.put("tier", entry.tier == null ? "" : entry.tier.key);
                if (entry.standardized >= 0.0D) {
                    // 尼奥与卡牌表都有标准化口径（Plackett-Luce 强度占比，合计 100%）
                    item.put("standardized_pct", round2(entry.standardized));
                    item.put("standardized_ci_low_pct", round2(entry.stdLow));
                    item.put("standardized_ci_high_pct", round2(entry.stdHigh));
                }
                if (entry.context != null && !entry.context.isEmpty()) {
                    item.put("context", entry.context);
                }
                item.put("image", imagePath(entry));
                entries.add(item);
            }
            groupJson.put("entries", entries);
            groups.add(groupJson);
        }
        root.put("groups", groups);

        String json = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(root);
        writeString(file, json);
    }

    /** 包内可见：导出与自检（scripts 里的假模型测试）共用同一份渲染逻辑。 */
    static void writeHtml(TierListModel model, File file) throws IOException {
        writeHtml(model, file, false);
    }

    /**
     * @param hasOtherVariant 本包里是否还有另一种口径的 HTML。不能靠「文件是否存在」判断：
     *                        两份 HTML 在同一轮导出里先后写出，先写的那份看不到后写的那份。
     */
    static void writeHtml(TierListModel model, File file, boolean hasOtherVariant) throws IOException {
        StringBuilder html = new StringBuilder(131072);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        String titleSuffix = htmlTitleSuffix(model.scope)
                + (model.standardized ? " · 标准化率 Standardized" : "");
        html.append("<title>TinyCat Tier List / ").append(titleSuffix).append("</title>\n");
        html.append("<style>\n");
        html.append(":root{--bg:#0f1116;--panel:#171a21;--panel2:#1d212a;--text:#e7e2d4;--muted:#9aa1ad;--line:#2b303b;--gold:#e5c07b;}\n");
        html.append("*{box-sizing:border-box}\n");
        html.append("body{margin:0;padding:28px;background:radial-gradient(1200px 600px at 20% -10%,#1c2230 0%,var(--bg) 55%);color:var(--text);font-family:\"Segoe UI\",\"Microsoft YaHei\",\"PingFang SC\",Arial,sans-serif;line-height:1.5}\n");
        html.append(".wrap{max-width:1400px;margin:0 auto}\n");
        html.append("h1{margin:0 0 4px;font-size:30px;letter-spacing:.5px}\n");
        html.append("h1 small{color:var(--muted);font-size:15px;font-weight:400;margin-left:8px}\n");
        html.append(".sub{color:var(--muted);margin:0 0 18px;font-size:14px}\n");
        html.append(".chips{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 18px}\n");
        html.append(".chip{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:5px 11px;font-size:13px;color:var(--muted)}\n");
        html.append(".chip b{color:var(--text);font-weight:600}\n");
        html.append(".note{background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--gold);border-radius:8px;padding:12px 14px;margin:0 0 22px;color:var(--muted);font-size:13px}\n");
        html.append(".note b{color:var(--text)}\n");
        html.append(".links{display:flex;gap:14px;flex-wrap:wrap;margin:0 0 26px;font-size:13px}\n");
        html.append(".links a{color:var(--gold);text-decoration:none;border-bottom:1px dotted var(--gold)}\n");
        html.append("section.group{background:linear-gradient(180deg,var(--panel),#14171d);border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 20px}\n");
        html.append("section.group h2{margin:0 0 12px;font-size:20px}\n");
        html.append("section.group h2 small{color:var(--muted);font-size:13px;font-weight:400;margin-left:8px}\n");
        html.append(".tier-row{display:grid;grid-template-columns:156px 1fr;gap:10px;padding:9px 0;border-top:1px solid var(--line)}\n");
        html.append(".tier-row:first-of-type{border-top:0}\n");
        html.append(".tier-label{display:flex;flex-direction:column;justify-content:flex-start;gap:2px;border-left:4px solid var(--line);padding:2px 0 2px 10px}\n");
        html.append(".tier-label .k{font-size:22px;font-weight:800;letter-spacing:.5px}\n");
        html.append(".tier-label .r{font-size:12px;color:var(--muted)}\n");
        html.append(".tier-label .c{font-size:11px;color:var(--muted)}\n");
        html.append(".entries{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:8px;overflow:hidden}\n");
        html.append(".entry{display:flex;align-items:center;gap:8px;background:var(--panel2);border:1px solid var(--line);border-radius:9px;padding:6px;min-width:0;overflow:hidden}\n");
        html.append(".entry img{width:58px;height:44px;object-fit:cover;border-radius:6px;background:#0b0d11;flex:0 0 auto}\n");
        html.append(".entry .main{min-width:0;flex:1 1 auto}\n");
        html.append(".entry .zh{font-size:13px;font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n");
        html.append(".entry .en{font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n");
        html.append(".entry .stat{text-align:right;flex:0 0 auto;max-width:46%;min-width:0}\n");
        html.append(".entry .pct{display:block;font-size:14px;font-weight:700;color:var(--gold)}\n");
        html.append(".entry .cnt{display:block;font-size:10px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n");
        html.append(".empty{color:var(--muted);font-size:13px;padding:6px 0}\n");
        html.append("details.table-wrap{margin:4px 0 0;color:var(--muted);font-size:12px}\n");
        html.append("details.table-wrap summary{cursor:pointer;color:var(--gold);margin:4px 0}\n");
        html.append("table{border-collapse:collapse;width:100%;font-size:12px;margin:8px 0 0}\n");
        html.append("th,td{border-bottom:1px solid var(--line);padding:5px 6px;text-align:left;white-space:nowrap}\n");
        html.append("th{color:var(--muted);font-weight:600}\n");
        html.append("footer{color:var(--muted);font-size:12px;margin-top:26px;border-top:1px solid var(--line);padding-top:12px}\n");
        html.append("@media(max-width:760px){body{padding:16px}.tier-row{grid-template-columns:1fr}.tier-label{border-left:0;border-top:3px solid var(--line);padding:6px 0 0}h1{font-size:23px}}\n");
        html.append("</style>\n</head>\n<body>\n<div class=\"wrap\">\n");
        boolean rankScope = model.scope != null && model.scope.isRankBased();
        html.append("<h1>TinyCat Tier List <small>").append(titleSuffix).append("</small></h1>\n");
        html.append("<p class=\"sub\">Slay the Spire · local run history export / 杀戮尖塔本地对局历史导出</p>\n");
        html.append("<div class=\"chips\">\n");
        html.append(chip("Character / 角色", model.characterZh + " / " + model.characterEn));
        html.append(chip("Character type / 类型", model.isAggregate()
                ? "Aggregate of 4 characters / 4 角色汇总"
                : "Single character / 单角色"));
        html.append(chip("Scope / 范围", (model.scope == null ? "全部抓率" : model.scope.labelZh) + " / "
                + (model.scope == null ? "All pick rates" : model.scope.labelEn)));
        html.append(chip("Date / 日期", empty(model.startDate) + " ~ " + empty(model.endDate)));
        html.append(chip("Runs / 对局", Integer.toString(model.runCount)));
        html.append(chip("Min sample / 最小样本", Integer.toString(model.minSample)));
        html.append(chip("Abandons / 放弃局", model.includeAbandons ? "Included / 包含" : "Excluded / 排除"));
        html.append(chip("Seeds / 种子局", model.seedFilter.labelEn + " / " + model.seedFilter.labelZh));
        html.append(chip("Generated / 生成时间", model.generatedAt));
        html.append("</div>\n");
        html.append("<div class=\"note\">\n");
        if (model.standardized) {
            html.append("<b>本页数值 = 标准化率，不是占比。</b>")
                    .append("标准化率由 Plackett-Luce 模型估出：每次「几选一」是一个选择集，")
                    .append("选中某个的概率 = 它的强度 ÷ 同屏所有选项强度之和；用全部对局一起拟合，再归一到合计 100%。")
                    .append("它扣掉了「这个选项总和强卡一起出现」「这个选项只在前几层出现」之类的偏差，")
                    .append("回答的是<b>谁更值得选</b>；卡片副标给的是原始计数占比与 95%CI"
                            + "（一次选择整组重采样的 bootstrap），")
                    .append("两者回答的是不同问题，<b>不能用这份替换游戏内页面上的占比</b>。<br>\n");
            boolean splitStdTail = model.scope == null || model.scope.splitsStandardTail();
            html.append("<b>分档：</b>");
            if (splitStdTail) {
                html.append("≥1% 的条目用 Jenks 自然断点（k=").append(RankRule.JENKS_K)
                        .append("）；<1% 的长尾按对数尺度再切一档（R0 / R00），整表共 7 档")
                        .append("（按标准化率重切，不沿用占比切出来的档）。<br>\n");
                html.append("<b>Tier rule:</b> Jenks breaks (k=").append(RankRule.JENKS_K)
                        .append(") on entries ≥1%; the <1% tail is split once more on a log scale ")
                        .append("(R0 / R00), 7 tiers in total.<br>\n");
            } else {
                html.append(RankRule.ACTIVE.labelZh).append(" —— ")
                        .append(RankRule.ACTIVE.noteZh).append("（按标准化率重切，不沿用占比切出来的档）。<br>\n");
            }
            html.append("<b>Method / 口径：</b> standardized rate = Plackett-Luce on whole choice sets "
                    + "(one reward screen = one decision; skip included); ")
                    .append("95% CI from a cluster bootstrap that resamples whole choice events (1000 draws). ")
                    .append("This answers <b>which option is worth picking</b>, ")
                    .append("not <b>which one players actually clicked</b>; the in-game screen always keeps the plain share.<br>\n");
            if (model.scope == null || model.scope == TierListScope.ALL
                    || model.scope.isCards()) {
                html.append("<b>卡牌：</b>每次奖励是「3 张牌 + 跳过」的一个选择集，整屏一起进似然；")
                        .append("持有颂钵时的跳过并入 SKIP 桶，所以跳过在每一次奖励里都是对手。")
                        .append("一幕、二幕、三、四幕分别拟合，另给第二至四幕合并的「第一幕后」；")
                        .append("每组单独拟合、各自合计 100%，<b>不能跨组相加</b>。")
                        .append("一幕包含 floor 0 与一幕 Boss 后的奖励屏，分幕按每局实际 Boss 楼层判定。")
                        .append("<b>0 次被选的卡</b>强度只剩「它输给了谁」这一点信息，")
                        .append("彼此之间的名次不要当强度差异看。")
                        .append("<b>未校正：</b>同一局里同一张卡第 2 次被提供时抓取率平均低 12pp（已有一张，边际价值下降），")
                        .append("这对名次的影响约 0.5~1 位（最大 6 位），本表不做张数校正。<br>\n");
                html.append("<b>Cards:</b> each reward is one choice set of \"3 cards + skip\" (not 3 pairwise duels); ")
                        .append("skip (with Singing Bowl merged in) competes on every reward. ")
                        .append("Acts 1, 2 and 3-4 are fitted separately, plus a merged after-Act-1 view; ")
                        .append("each view sums to 100% on its own and must not be added across views. ")
                        .append("Act 1 includes floor 0 and the Act 1 boss reward, with acts split by each run's real boss floors. ")
                        .append("Zero-pick cards carry no pick signal beyond whom they lost to — do not read their order as strength.<br>\n");
            }
        } else if (rankScope) {
            html.append("<b>Method / 口径：</b>").append(RankRule.ACTIVE.labelZh).append(" —— ")
                    .append(RankRule.ACTIVE.noteZh).append("。");
            if (model.scope == TierListScope.CAMPFIRE_UPGRADE) {
                html.append("<b>指标 = 火堆权重</b>（每次锻造时，分母是当时卡组里还有未升级副本的卡牌<b>种类数</b>，")
                        .append("同名多张只算 1 个选项；分子是实际被敲的那一张），分档依据也是该权重；")
                        .append("每张牌另附<b>占锻造</b>（被敲次数 ÷ 该幕锻造次数）与<b>占火堆</b>（被敲次数 ÷ 该幕火堆访问次数）。")
                        .append("<b>A 列为替补口径</b>：被敲次数 ÷ 到该幕结束累计拥过的副本数，只依赖获得记录、不受删牌影响，")
                        .append("卡组重建失败的条目（标 *）以它为准。")
                        .append("只统计火堆锻造，已升级的副本不会再次被敲，所以第二幕起同一张卡的权重会自然下降。")
                        .append("候选不足 10 次的条目不参与本表。<br>\n");
                html.append("<b>Tier rule:</b> ").append(RankRule.ACTIVE.labelEn)
                        .append(". Tiers are cut on the <b>campfire weight</b>; counts are shown as chosen / candidates; ")
                        .append("share-of-Smith and share-of-visits are listed per card; column A is the fallback rate (chosen / copies ever owned).<br>\n");
            } else {
                html.append("<b>分档依据是原始次数</b>（不是胜率、也不是除以局数）；")
                        .append("同表内「次数 ÷ 总数」是等比缩放，切出来的档位与按次数完全一致，")
                        .append("所以图片主标写次数、副标写占比，两者都标注分母。");
                if (model.scope == TierListScope.NEOW) {
                    html.append("尼奥一行 = 一个「奖励 + 代价」选项，绝不按奖励合并；")
                            .append("分母统一取「尼奥选择总次数」，因为 .run 里被跳过选项的代价全是 NONE，")
                            .append("带代价的选项算不出真实选取率。<br>\n");
                } else {
                    html.append("<br>\n");
                }
                html.append("<b>Tier rule:</b> ").append(RankRule.ACTIVE.labelEn).append(". ")
                        .append("Tiers are cut on <b>raw counts</b>; ")
                        .append("share = count / total is shown as the secondary number. ");
                if (model.scope == TierListScope.NEOW) {
                    html.append("Each Neow row is one <b>bonus + cost</b> option; the denominator is all Neow selections, ")
                            .append("not offers, because skipped costs are not recorded in .run files.<br>\n");
                } else {
                    html.append("<br>\n");
                }
            }
        }
        if (!rankScope) {
        html.append("<b>Method / 口径：</b> pick rate = <b>picked / offered</b> (被选取次数 / 被提供次数). ");
        html.append("Card acts follow each run's real boss floors (Run.getAct): floor 0 and the Act 1 boss reward belong to Act 1; Acts 1, 2, 3-4 and the merged after-Act-1 view are exported together. ");
        html.append("Only A20, non-daily, non-special runs from the current save slot are included (same filter as the in-game stats). ");
        html.append("Seed filter: <b>").append(escapeHtml(model.seedFilter.labelEn)).append("</b>. ");
        html.append("Chinese is the default language for data.csv and every .xlsx workbook. There are 4 base characters; ALL is an aggregate, not a fifth character. ");
        html.append("SKIP is kept as a real entry; Singing Bowl is merged into SKIP. CURSE/STATUS cards are excluded.<br>\n");
        html.append("<b>口径：</b>抓率 = 被选取次数 ÷ 被提供次数；分幕按每局实际 Boss 楼层（Run.getAct）判定，");
        html.append("第一幕包含 floor 0 与一幕 Boss 后的奖励屏；一幕、二幕、三、四幕与第二至四幕合并的「第一幕后」一起导出。");
        html.append("只统计当前存档位的 A20、非每日/特殊局（与游戏内统计一致），种子局筛选：<b>")
                .append(escapeHtml(model.seedFilter.labelZh)).append("</b>。");
        html.append("data.csv 与所有 .xlsx 均默认中文；基础角色 4 个，ALL 是汇总而非第 5 个角色。");
        html.append("SKIP 保留，颂钵并入 SKIP；CURSE/STATUS 卡剔除。");
        if (hasOtherVariant) {
            html.append("同一份数据另有一份<b>标准化率</b>视图（回答「谁更值得选」，不是占比），见页脚链接。");
        }
        html.append("<br>\n");
        }
        html.append("<b>Images / 图片：</b> all images are extracted from the local game at runtime (CardLibrary / RelicLibrary); SKIP and missing images use a locally generated placeholder. No network or external image source. ");
        html.append("图片全部来自本地游戏内存（CardLibrary / RelicLibrary），SKIP 和缺失图使用本地生成的占位图；不访问网络、不依赖外部图源。\n");
        html.append("</div>\n");
        html.append("<div class=\"links\">");
        File parentDir = file.getParentFile();
        String stdBase = "tierlist" + RateVariant.FILE_SUFFIX;
        // 两种口径互链：读者必须能一眼跳到另一份，否则很容易把一份当成全部
        String otherHtml = model.standardized ? "tierlist.html" : stdBase + ".html";
        if (hasOtherVariant) {
            html.append("<a href=\"").append(otherHtml).append("\">")
                    .append(model.standardized ? "简单率视图 tierlist.html" : "标准化率视图 " + otherHtml)
                    .append("</a>");
        }
        String selfBase = model.standardized ? stdBase : "tierlist";
        if (new File(parentDir, selfBase + ".jpg").isFile()) {
            html.append("<a href=\"").append(selfBase).append(".jpg\">").append(selfBase).append(".jpg</a>");
        }
        if (new File(parentDir, selfBase + ".png").isFile()) {
            html.append("<a href=\"").append(selfBase).append(".png\">").append(selfBase).append(".png</a>");
        }
        File charts = new File(parentDir, ExportImagePack.DIR);
        if (charts.isDirectory()) {
            String full = ExportImagePack.DIR + "/00-完整梯度图"
                    + (model.standardized ? RateVariant.CHART_SUFFIX : "") + ".jpg";
            if (new File(parentDir, full).isFile()) {
                html.append("<a href=\"").append(escapeHtml(full)).append("\">完整梯度图（含怪物危险度）</a>");
            }
        }
        for (String statFile : STAT_FILES) {
            if (new File(parentDir, statFile).isFile()) {
                html.append("<a href=\"").append(escapeHtml(statFile)).append("\">")
                        .append(escapeHtml(statFile)).append("</a>");
            }
        }
        html.append("<a href=\"data.csv\">data.csv</a><a href=\"data.json\">data.json</a><a href=\"README.txt\">README.txt</a></div>\n");

        for (TierListGroup group : model.groups) {
            html.append("<section class=\"group\">\n");
            html.append("<h2>").append(escapeHtml(group.labelZh)).append("<small>").append(escapeHtml(group.labelEn)).append("</small></h2>\n");
            if (group.note != null && !group.note.isEmpty()) {
                // 睡觉不参与竞争榜，但数值重要，挂在组标题下
                html.append("<div class=\"note\">").append(escapeHtml(group.note)).append("</div>\n");
            }
            if (group.entries.isEmpty()) {
                html.append("<div class=\"empty\">No data / 无数据</div>\n");
            } else {
                for (Tier tier : tiersFor(model.scope, model.standardized)) {
                    List<TierEntry> entries = group.entriesOf(tier);
                    html.append("<div class=\"tier-row\">\n");
                    html.append("<div class=\"tier-label\" style=\"border-left-color:").append(tier.color).append("\">\n");
                    html.append("<span class=\"k\" style=\"color:").append(tier.color).append("\">").append(escapeHtml(tier.key)).append("</span>\n");
                    html.append("<span class=\"r\">").append(escapeHtml(tier.labelEn)).append("</span>\n");
                    html.append("<span class=\"c\">").append(escapeHtml(tier.labelZh)).append(" · ").append(entries.size()).append("</span>\n");
                    html.append("</div>\n<div class=\"entries\">\n");
                    if (entries.isEmpty()) {
                        html.append("<div class=\"empty\">-</div>\n");
                    } else {
                        for (TierEntry entry : entries) {
                            html.append("<div class=\"entry\" title=\"").append(escapeHtml(entry.nameEn)).append("\">\n");
                            html.append("<img src=\"").append(escapeHtml(imagePath(entry))).append("\" alt=\"").append(escapeHtml(entry.nameEn)).append("\" loading=\"lazy\">\n");
                            html.append("<div class=\"main\"><div class=\"zh\">").append(escapeHtml(entry.nameZh)).append("</div>");
                            html.append("<div class=\"en\">").append(escapeHtml(entry.nameEn)).append("</div></div>\n");
                            html.append("<div class=\"stat\"><span class=\"pct\">").append(formatRate(entry.rate)).append("%</span>");
                            String cnt = model.scope == TierListScope.CAMPFIRE_UPGRADE
                                    ? "被敲 " + entry.picked + " / 候选 " + entry.offered
                                    : entry.picked + " / " + entry.offered;
                            if (entry.context != null && !entry.context.isEmpty()) {
                                cnt += " · " + entry.context;
                            }
                            html.append("<span class=\"cnt\">").append(escapeHtml(cnt)).append("</span></div>\n");
                            html.append("</div>\n");
                        }
                    }
                    html.append("</div>\n</div>\n");
                }
            }
            html.append("<details class=\"table-wrap\"><summary>Table view / 表格视图</summary>\n");
            html.append("<table><thead><tr><th>#</th><th>Name / 名称</th><th>Type</th><th>")
                    .append(model.scope == TierListScope.CAMPFIRE_UPGRADE
                            ? "Chosen / Candidates 被敲/候选" : "Picked / Offered 选取/提供")
                    .append("</th><th>").append(model.standardized ? "标准化率 / Standardized %" : "Rate")
                    .append("</th><th>Tier</th></tr></thead><tbody>\n");
            int index = 1;
            for (TierEntry entry : group.entries) {
                html.append("<tr><td>").append(index++).append("</td><td>").append(escapeHtml(entry.nameZh)).append(" / ").append(escapeHtml(entry.nameEn));
                html.append("</td><td>").append(escapeHtml(entry.type)).append("</td><td>").append(entry.picked).append(" / ").append(entry.offered);
                if (entry.context != null && !entry.context.isEmpty()) {
                    html.append("<br><small>").append(escapeHtml(entry.context)).append("</small>");
                }
                html.append("</td><td>").append(formatRate(entry.rate)).append("%");
                // 标准化视图的 CI 不进图片副标（宽度不够），表格里补上
                if (model.standardized && entry.stdHigh > 0.0D) {
                    html.append(" <small>[").append(formatRate(entry.stdLow))
                            .append("–").append(formatRate(entry.stdHigh)).append("]</small>");
                }
                html.append("</td><td>").append(entry.tier == null ? "" : escapeHtml(entry.tier.key)).append("</td></tr>\n");
            }
            html.append("</tbody></table></details>\n");
            html.append("</section>\n");
        }

        html.append("<footer>TinyCat ").append(MOD_VERSION).append(" · local export · data.csv / data.json / 中文统计 Excel / images/ · no external image source.</footer>\n");
        html.append("</div>\n</body>\n</html>\n");
        writeString(file, html.toString());
    }

    private static String htmlTitleSuffix(TierListScope scope) {
        if (scope == TierListScope.CAMPFIRE) {
            return "火堆行动梯度";
        }
        if (scope == TierListScope.CAMPFIRE_UPGRADE) {
            return "火堆偏好";
        }
        if (scope == TierListScope.SHOP) {
            return "商店行动梯度";
        }
        if (scope == TierListScope.NEOW) {
            return "尼奥选项梯度";
        }
        if (scope != null && scope.isRankBased()) {
            return "排名梯度";
        }
        if (scope == null) {
            return "全量梯度";
        }
        return "抓率梯度";
    }

    private static void writeReadme(TierListModel model, File file, boolean hasStandard) throws IOException {
        StringBuilder text = new StringBuilder(8192);
        text.append("TinyCat Tier List Export\n");
        text.append("========================\n\n");
        text.append("Mod version : TinyCat ").append(MOD_VERSION).append("\n");
        text.append("Generated   : ").append(model.generatedAt).append("\n");
        text.append("Character   : ").append(model.characterZh).append(" / ").append(model.characterEn).append("\n");
        text.append("Type        : ").append(model.isAggregate()
                ? "aggregate of 4 characters（4 角色汇总）" : "single character（单角色）").append("\n");
        text.append("Scope       : ").append(model.scope == null ? "全部抓率" : model.scope.labelZh)
                .append(" / ").append(model.scope == null ? "All pick rates" : model.scope.labelEn).append("\n");
        text.append("Date range  : ").append(empty(model.startDate)).append(" ~ ").append(empty(model.endDate)).append("\n");
        text.append("Runs        : ").append(model.runCount).append("\n");
        text.append("Min sample  : ").append(model.minSample).append("\n");
        text.append("Abandons    : ").append(model.includeAbandons ? "included" : "excluded").append("\n");
        text.append("Seed filter : ").append(model.seedFilter.labelEn)
                .append(" / ").append(model.seedFilter.labelZh).append("\n\n");

        text.append("Files\n");
        text.append("-----\n");
        text.append("data.csv      - one row per entry, Chinese headers, UTF-8 with BOM (Excel friendly)\n");
        text.append("data.json     - structured data with tier/group metadata\n");
        text.append("*.xlsx        - Chinese Excel workbooks following the in-game stats pages:\n");
        text.append("                总体统计 / 第一幕卡牌选取率 / 第二幕卡牌选取率 /\n");
        text.append("                第三、四幕卡牌选取率 / 第一幕后卡牌选取率（第二至四幕合并） /\n");
        text.append("                卡组中的卡牌胜率 /\n");
        text.append("                尼奥奖励 / BOSS遗物 / 遭遇战伤害（战损、受到伤害、有无回复）/\n");
        text.append("                怪物危险度（怪物危险度梯度，按幕 T0-T4 分档）/ \n");
        text.append("                遭遇战死亡率与药水 / 遗物胜率 / 各幕事件胜率 / 火堆行动 / 商店行动 /\n");
        text.append("                火堆偏好（选项权重 + 被敲次数 ÷ 当前候选卡种数，附 A 升级率）\n");
        text.append("                同类页面合并为一个工作簿，每个统计页对应独立工作表；\n");
        text.append("                全部导出时生成以上全部文件，单页导出只生成该页对应的工作簿\n");
        text.append("tierlist.html - self-contained visual tier list (open in any browser)\n");
        text.append("tierlist.jpg  - shareable full tier-list image (JPEG)\n");
        text.append("tierlist.png  - shareable full tier-list image (lossless PNG)\n");
        if (hasStandard) {
            text.append("tierlist_standardized.html / .jpg / .png\n");
            text.append("              - 同一份数据的「标准化率」视图（Plackett-Luce），与上面三个同名文件一一对应\n");
        }
        text.append("图表/         - 一图完整（00-完整梯度图，含怪物危险度）+ 按指标页分图（01 起）\n");
        text.append("                每张都有 .png（无损）和 .jpg（体积小）两种\n");
        if (hasStandard) {
            text.append("                带「-标准化率」后缀的是同一张表的标准化率版本\n");
        }
        text.append("images/       - card portraits, boss relic icons, SKIP placeholder\n");
        text.append("README.txt    - this file\n\n");

        text.append("Data source / 数据来源\n");
        text.append("----------------------\n");
        text.append("Local .run files loaded by RunHistoryManager and aggregated by ClassStat.\n");
        text.append("Only A20, non-daily, non-special runs from the current save slot are included.\n");
        text.append("Seed filter: ").append(model.seedFilter.labelEn).append(" / ")
                .append(model.seedFilter.labelZh).append(".\n");
        text.append("Character count: 4 base-game characters; ALL is an aggregate, not a fifth character.\n");
        text.append("This is the same filter used by the in-game statistics screen.\n");
        text.append("数据来自本地 .run 文件，经 RunHistoryManager 加载、ClassStat 聚合；\n");
        text.append("只统计当前存档位的 A20、非每日/特殊局；种子局筛选：")
                .append(model.seedFilter.labelZh).append("，与游戏内统计口径一致。\n");
        text.append("基础角色共 4 个；ALL 是四个角色的汇总，不是第 5 个角色。\n\n");

        text.append("Tier rules / 分档\n");
        text.append("-----------------\n");
        if (model.scope != null && model.scope.isRankBased()) {
            text.append(RankRule.ACTIVE.describeZh()).append("\n");
            text.append(RankRule.ACTIVE.describeEn()).append("\n");
            text.append("排名型表不用固定百分比阈值：火堆头名占比约 24.8%、商店约 10.6%、尼奥选项占比按选中次数计，\n");
            text.append("同一套阈值会全挤在一档。档内仍按指标降序，并标出实际占比，方便看档内跨度。\n");
            text.append("Metrics: 火堆 = 次数 ÷ 该幕火堆行动总数；商店 = 次数 ÷ 该幕购买总次数；\n");
            text.append("火堆偏好 = 同一批火堆访问上的三层分母：\n");
            text.append("  行动构成 = 锻造/睡觉/回忆/抽烟/举重/挖掘次数 ÷ 火堆访问次数（见工作簿的火堆构成表）；\n");
            text.append("  火堆权重(W) = 该卡被敲次数 ÷ 它出现在候选里的次数（每次锻造的分母 = 当时未升级卡牌种类数，同名算 1 个）；\n");
            text.append("  随机基线 = 100 ÷ 该幕平均候选卡种数；倍数 = 火堆权重 ÷ 随机基线；\n");
            text.append("  A 升级率(替补) = 该幕锻造次数 ÷ 到该幕结束累计拥过副本数，不受删牌影响，标 * 的条目以它为准；\n");
            text.append("  桥接层 = 升级次数 ÷ 锻造次数（占锻造）、升级次数 ÷ 火堆访问次数（占火堆）。\n");
            text.append("  统一榜：把「升级」拆回每张可敲的牌，和抽烟/举重/挖掘/回忆放在同一张榜上按占比排序；\n");
            text.append("         分母 = 该次火堆的「池子」= 可敲卡种 + 可用遗物行动 + 可用回忆，随机基线 = 100 ÷ 平均池子大小；\n");
            text.append("         所以卡牌和遗物行动的倍数可以互相比较。\n");
            text.append("  睡觉单列：它的约束是血量，不属于「从这些里挑一个」的竞争，不进池子，分母是火堆访问次数。\n");
            text.append("尼奥 = 该选项被选中次数 ÷ 尼奥选择总次数（.run 里被跳过选项的代价全是 NONE，\n");
            text.append("带代价的选项无法还原真实提供次数，所以不标「选取率」）。\n");
            text.append("火堆偏好只统计火堆锻造（不含磨刀石/战锤/课程/事件升级）；\n");
            text.append("候选按初始牌组 + 抓取 + 商店 + 事件 + 变牌（含星盘/空鸟笼双向补差）逐层重放。\n");
            text.append("  · 蛋（Molten/Toxic/Frozen）升级的牌：card_choices 的 picked 带 +1，已识别，不会进候选；\n");
            text.append("  · War Paint/Whetstone 只记遗物、不记升了哪两张，这部分候选偏大约 1.5%，只影响排名尾部；\n");
            text.append("  · B 口径（÷当时拥有）已剔除：分子含已被删的副本、分母不含，会饱和到 100% 以上。\n\n");

            text.append("Sample size / 样本量\n");
            text.append("--------------------\n");
            text.append("游戏内不设下限，n=1 也会算出来；但倍数的置信区间随 n 迅速变宽，按下面的量级读：\n");
            text.append("  候选次数 n      95% 置信区间（p≈50% 时）   怎么用\n");
            text.append("  n < 10          看不出来方向               只看「有没有被敲过」\n");
            text.append("  10 ≤ n < 30     ±20~30pp                   大致量级，别排名\n");
            text.append("  30 ≤ n < 100    ±10~18pp                   能排序，相邻档可能互换\n");
            text.append("  n ≥ 100         ±10pp 以内                稳定\n");
            text.append("经验值：单卡候选 30 次能看出量级，100 次排名稳定；\n");
            text.append("分幕看时每幕至少 50 局，否则该幕的单卡候选普遍不足 30 次。\n");
            text.append("原理：权重 p 的标准误 = sqrt(p(1-p)/n)，倍数 = p ÷ 基线，p 抖 ±5pp，倍数就抖 ±0.35。\n");
            text.append("Sample size: every entry is listed regardless of n; read multipliers at n >= 30 and rank at n >= 100.\n\n");
        } else {
            for (Tier tier : tiersFor(model.scope, model.standardized)) {
                text.append(String.format(Locale.US, "%-5s %-16s %s\n", tier.key, tier.labelEn, tier.labelZh));
            }
            text.append("Pick rate = picked / offered (raw counts, not rounded before tiering).\n");
            text.append("0% tier is exactly picked == 0 with offered > 0.\n");
            text.append("F covers 0 < rate < 15% (including 0.1%-0.9%).\n");
            text.append("SKIP is kept; Singing Bowl is merged into SKIP; CURSE/STATUS cards are excluded.\n\n");
        }

        if (hasStandard) {
            text.append("Rate definitions / 两种口径（本包同时提供）\n");
            text.append("--------------------------------------\n");
            text.append("简单率 / 选取率 = 次数 ÷ 分母（卡牌是被选 ÷ 被提供；尼奥是被选 ÷ 出现）。\n");
            text.append("  它就是游戏内页面上的数字，也是能直接对着 .run 原始记录逐条核对的数字。\n\n");
            text.append("标准化率 = Plackett-Luce 强度占比（合计 100%）。\n");
            text.append("  每次「几选一」是一个选择集，选中某个的概率 = 它的强度 ÷ 同屏所有选项强度之和；\n");
            text.append("  用全部对局一起拟合每个选项的相对强度，再归一到合计 100%。\n");
            text.append("  为什么需要它：简单率会被「出场环境」污染——总和强卡一起出现的选项被系统性低估，\n");
            text.append("  只在特定楼层出现的选项被系统性高估；三选一这种小样本尤其明显，标准化后才可比。\n");
            text.append("  它和选取率不是一个量纲：全表合计 100%，条目越多单个数值越小，只看相对高低。\n");
            text.append("  95%CI 来自整屏 bootstrap(1000)：一次「几选一」整组重采样，\n");
            text.append("  不会把同一个决定当成多份独立证据（旧算法按格子重采样会窄约 1.5 倍）。\n\n");
            text.append("两份回答的是不同问题，谁也不能替代谁：\n");
            text.append("  简单率 = 玩家实际点了哪个；标准化率 = 扣掉环境因素后谁更值得选。\n");
            text.append("  游戏内页面一律用简单率（前端不显示标准化率），导出包才两份都给。\n\n");
            text.append("卡牌表的三个注意点：\n");
            text.append("  1) SKIP 与颂钵（并入 SKIP）每次奖励都在场，必须进选择集；剔除它会让名次整体偏 1~2 位。\n");
            text.append("  2) 一幕 / 二幕 / 三、四幕分别拟合，另给第二至四幕合并的「第一幕后」；\n");
            text.append("     一幕包含 floor 0 与一幕 Boss 后的奖励屏；四组各自合计 100%，不能跨组相加\n");
            text.append("     （第一幕后不是二幕 + 三、四幕相加）。\n");
            text.append("  3) 0 次被选的卡只剩「它输给了谁」这一条信息，彼此名次不代表强度差异。\n");
            text.append("     未校正：同一局第 2 次被提供时平均少抓 12pp，对名次影响约 0.5~1 位（最大 6 位）。\n\n");
            text.append("Eng: standardized rate = Plackett-Luce fit on every whole \"pick one of N\" choice set;\n");
            text.append("95% CI from a 1000-draw cluster bootstrap that resamples whole choice events.\n");
            text.append("across the table, so it is a relative strength share, not a pick probability.\n");
            text.append("The plain share answers \"what did players click\"; the standardized rate answers\n");
            text.append("\"what is worth picking\". The in-game screen always shows the plain share.\n");
            text.append("Cards: skip (with Singing Bowl) competes on every reward; Acts 1, 2, 3-4 and the\n");
            text.append("merged after-Act-1 view are fitted separately; each view sums to 100% on its own\n");
            text.append("and must not be added across views; zero-pick cards differ only by whom they lost to.\n\n");
            text.append("Excel color coding / Excel 配色（仅 xlsx）\n");
            text.append("--------------------------------------\n");
            text.append("卡片与尼奥工作簿按 95%CI 可信度整行上色：\n");
            text.append("  绿色 = 可信：CI 半宽 ≤ 点估计的 20%，且出现 ≥ 10 次、被选 ≥ 3 次；\n");
            text.append("  橙色 = 一般：半宽 ≤ 40%；\n");
            text.append("  红色 = 不可信：半宽 > 40%，或出现 < 10 次、被选 < 3 次。\n");
            text.append("零被选的卡 CI 会退化为 0 宽（看起来最可信），所以先按被选次数兜底，统一标红。\n");
            text.append("Eng: rows in the card and Neow workbooks are tinted by 95% CI reliability:\n");
            text.append("green = half-width <= 20% of the estimate with >= 10 offers and >= 3 picks;\n");
            text.append("orange = half-width <= 40%; red = wider, or fewer than 10 offers / 3 picks.\n\n");
        }

        text.append("Images / 图片\n");
        text.append("-------------\n");
        text.append("All images come from the local game at runtime:\n");
        text.append("- Cards: AbstractCard.portrait via CardLibrary\n");
        text.append("- Boss relics: AbstractRelic.img via RelicLibrary\n");
        text.append("- SKIP / missing images: generated locally with libGDX Pixmap + PixelFont\n");
        text.append("No network access, no external image folder, no new third-party dependency.\n");
        text.append("所有图片均来自本地游戏内存；SKIP 和缺失图使用本地 Pixmap 生成。\n");
        text.append("不访问网络、不依赖外部图源、不引入新第三方依赖。\n\n");

        text.append("How to open / 打开方式\n");
        text.append("----------------------\n");
        text.append("Double-click tierlist.html, or open it with any modern browser.\n");
        text.append("双击 tierlist.html，或用任意现代浏览器打开。\n");
        writeString(file, text.toString());
    }

    private static String chip(String label, String value) {
        return "<span class=\"chip\">" + escapeHtml(label) + ": <b>" + escapeHtml(value) + "</b></span>\n";
    }

    private static String imagePath(TierEntry entry) {
        if (entry == null || entry.imagePath == null || entry.imagePath.isEmpty()) {
            return "images/placeholder.png";
        }
        return entry.imagePath.replace('\\', '/');
    }

    private static String typeZh(String type) {
        if ("card".equals(type)) {
            return "卡牌";
        }
        if ("boss_relic".equals(type)) {
            return "BOSS遗物";
        }
        if ("skip".equals(type)) {
            return "跳过";
        }
        return type == null ? "" : type;
    }

    private static String formatRate(double rate) {
        return String.format(Locale.US, "%.2f", rate);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0D) / 100.0D;
    }

    private static String empty(String value) {
        return value == null ? "" : value;
    }

    private static String csv(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.indexOf(',') >= 0 || escaped.indexOf('"') >= 0
                || escaped.indexOf('\n') >= 0 || escaped.indexOf('\r') >= 0) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&':
                    out.append("&amp;");
                    break;
                case '<':
                    out.append("&lt;");
                    break;
                case '>':
                    out.append("&gt;");
                    break;
                case '"':
                    out.append("&quot;");
                    break;
                case '\'':
                    out.append("&#39;");
                    break;
                default:
                    out.append(c);
            }
        }
        return out.toString();
    }

    private static void writeString(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(content);
        } finally {
            writer.close();
        }
    }

    private static boolean ensureDir(File dir) {
        return dir.isDirectory() ? dir.canWrite() : dir.mkdirs();
    }

    private static void ensureCacheReadme(File cacheDir) throws IOException {
        File readme = new File(cacheDir, "README.txt");
        if (readme.isFile() && readme.length() > 0L) {
            return;
        }
        writeString(readme,
                "TinyCat image cache\n"
                        + "==================\n\n"
                        + "This folder is a persistent cache shared by all Tier List exports.\n"
                        + "It avoids re-extracting the same card/relic image on every export.\n\n"
                        + "It is NOT included in the export zip. Deleting this folder is safe;\n"
                        + "the next export will rebuild it.\n"
                        + "If you updated the game or a card/relic mod and see stale images,\n"
                        + "delete this cache folder and export again.\n");
    }

    private static void zipDirectory(File root, File zipFile) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(zipFile), StandardCharsets.UTF_8);
        try {
            ZipHelper.addToZip(root, root, zip);
        } finally {
            zip.close();
        }
    }

    private static final class ZipHelper {
        private ZipHelper() {
        }

        private static void addToZip(File root, File current, ZipOutputStream zip) throws IOException {
            File[] files = current.listFiles();
            if (files == null) {
                return;
            }
            Arrays.sort(files);
            for (File file : files) {
                if (file.isDirectory()) {
                    addToZip(root, file, zip);
                } else {
                    String name = root.toURI().relativize(file.toURI()).getPath();
                    ZipEntry entry = new ZipEntry(name);
                    zip.putNextEntry(entry);
                    FileInputStream in = new FileInputStream(file);
                    try {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            zip.write(buffer, 0, read);
                        }
                    } finally {
                        in.close();
                    }
                    zip.closeEntry();
                }
            }
        }
    }
}
