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
 * 怪物危险度梯度导出。独立于抓率/遗物 Tier List，也不改动原统计聚合。
 *
 * 输出：
 *   monster-tier-export/TinyCat_MonsterTier_<角色>_<时间>/
 *     怪物危险度.xlsx
 *     monster-tiers.csv / .json
 *     monster-tiers.html / .png / .jpg
 *     images/monsters/*.png
 *     README.txt
 *   同级 zip。
 */
public final class MonsterTierExporter {
    private static final String EXPORT_ROOT = "monster-tier-export";
    private static final String MOD_VERSION = "4.2.1";
    private static final String WORKBOOK = "怪物危险度.xlsx";
    private static final String[] HEADERS = {
            "梯度", "档内排名", "非Boss总排名", "类型", "中文名", "英文名",
            "平均战损", "中位数", "P90", "95%CI下限", "95%CI上限", "样本数",
            "样本占比(%)", "净战损合计", "战损贡献占比(%)", "备注"
    };

    private MonsterTierExporter() {
    }

    public static String export(MoreStatsScreen screen) throws Exception {
        MonsterTierModel model = MonsterTierBuilder.build(screen);
        File exportRoot = Gdx.files.local(EXPORT_ROOT).file();
        if (!ensureDir(exportRoot)) {
            exportRoot = Gdx.files.external("TinyCat/" + EXPORT_ROOT).file();
            if (!ensureDir(exportRoot)) {
                throw new IOException("cannot create export directory: " + exportRoot.getAbsolutePath());
            }
            tinycat.logger.warn("MonsterTier: game directory not writable, using "
                    + exportRoot.getAbsolutePath());
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
        ensureCacheReadme(cacheDir);
        MonsterImageLibrary images = new MonsterImageLibrary(imagesDir, cacheDir);
        for (MonsterTierGroup group : model.groups) {
            for (MonsterTierEntry entry : group.allEntries()) {
                entry.imagePath = images.export(entry);
            }
        }

        writeWorkbook(model, new File(root, WORKBOOK));
        writeCsv(model, new File(root, "monster-tiers.csv"));
        writeJson(model, new File(root, "monster-tiers.json"));
        writeHtml(model, new File(root, "monster-tiers.html"), WORKBOOK);
        writeReadme(model, new File(root, "README.txt"));
        try {
            MonsterTierImageRenderer.write(model, root);
        } catch (Exception t) {
            tinycat.logger.warn("MonsterTier: failed to render shareable image", t);
        }

        File zip = new File(exportRoot, root.getName() + ".zip");
        if (zip.exists() && !zip.delete()) {
            throw new IOException("cannot overwrite zip: " + zip.getAbsolutePath());
        }
        zipDirectory(root, zip);
        tinycat.logger.info("MonsterTier exported: " + zip.getAbsolutePath());
        return zip.getAbsolutePath();
    }

    private static String folderName(MonsterTierModel model) {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String character = model.isAggregate() ? "ALL_CHARACTERS" : model.characterId;
        return "TinyCat_MonsterTier_" + character + "_" + timestamp;
    }

    private static void writeWorkbook(MonsterTierModel model, File file) throws IOException {
        XlsxWriter wb = new XlsxWriter();
        for (MonsterTierGroup group : model.groups) {
            String extra = "Boss 门槛 " + model.bossMinSample + " 样本；非 Boss 门槛 "
                    + model.nonBossMinSample + " 样本"
                    + "；本幕低样本 " + group.lowSampleCount + " 项（保留展示，不参与分档）"
                    + "；事件战与第四幕固定战不计入";
            XlsxWriter.Sheet sheet = newSheet(wb,
                    group.labelZh,
                    group.labelZh + " · 怪物危险度梯度",
                    context(model, extra),
                    HEADERS);
            sheet.width(4, 22.0D);
            sheet.width(5, 30.0D);
            sheet.width(15, 44.0D);
            int rowCount = 0;
            for (MonsterTierEntry entry : group.bosses) {
                writeEntry(sheet, entry, "Boss 单独排序，不与非 Boss 比较");
                rowCount++;
            }
            for (MonsterTierEntry entry : group.nonBosses) {
                writeEntry(sheet, entry, "T1 最高危；按平均战损等人数分档");
                rowCount++;
            }
            for (MonsterTierEntry entry : group.lowSampleEntries) {
                writeEntry(sheet, entry, "样本不足，不参与分档");
                rowCount++;
            }
            if (rowCount == 0) {
                noData(sheet);
            }
        }

        XlsxWriter.Sheet rules = newSheet(wb, "说明", "怪物危险度梯度 · 口径说明",
                context(model, "Boss 单独 T0；非 Boss 含精英，按幕内排名等人数分 T1-T4"),
                "项目", "内容");
        rules.width(0, 26.0D);
        rules.width(1, 110.0D);
        rule(rules, "主指标", "平均实际战损 = 进入该层前 HP − 该层结束 HP。正值掉血，负值表示净回血。");
        rule(rules, "样本单位", "每次遭遇计 1 个样本；同一遭遇在不同对局中分别计入。");
        rule(rules, "样本门槛", "Boss 每项至少 " + model.bossMinSample + " 个样本；非 Boss 单角色至少 "
                + MonsterTierBuilder.SINGLE_CHARACTER_MIN_SAMPLE + " 个，全角色汇总至少 "
                + MonsterTierBuilder.AGGREGATE_MIN_SAMPLE
                + " 个。低于门槛的条目保留在每幕末尾，标注“样本不足”，不参与 T0-T4 分档。");
        rule(rules, "样本占比", "该遭遇样本数 ÷ 同幕同类（Boss 或非 Boss）样本总数，用于判断数据集中度。");
        rule(rules, "净战损合计", "该遭遇所有样本的实际战损之和；负值表示该遭遇总体净回血。");
        rule(rules, "战损贡献占比", "该遭遇净战损合计 ÷ 同幕同类净战损合计；同类净战损合计 ≤ 0 时显示 —。");
        rule(rules, "Boss", "每幕 Boss 单独列为 T0，只按平均战损降序，不与非 Boss 混排。");
        rule(rules, "非 Boss 分档", "包含普通怪与精英；按平均战损降序，在幕内按等人数排名切分为 T1-T4。T1 最高危。");
        rule(rules, "分档边界", "不使用固定战损阈值；随日期、角色、种子、放弃局筛选重新计算。");
        rule(rules, "排除范围", "事件战、第四幕固定战（盾与矛、腐化心脏）、宝箱/奖励层不参与。");
        rule(rules, "节点识别", "使用 .run 的 path_per_floor：M=普通、E=精英、B=Boss；?=事件不参与。");
        rule(rules, "辅助指标", "中位数、P90、95% 置信区间、样本占比和战损贡献只作参考，不参与分档。");
        rule(rules, "图片", "优先读取游戏目录 mods/TinyCat/monster-images；缺失时使用占位图。");
        wb.write(file);
    }

    private static void writeEntry(XlsxWriter.Sheet sheet, MonsterTierEntry entry, String note) {
        sheet.data(
                XlsxWriter.Cell.text(entry.tier == null ? "—" : entry.tier.key),
                entry.tierRank <= 0
                        ? XlsxWriter.Cell.text("—")
                        : XlsxWriter.Cell.integer(entry.tierRank),
                entry.nonBossRank <= 0
                        ? XlsxWriter.Cell.text("—")
                        : XlsxWriter.Cell.integer(entry.nonBossRank),
                XlsxWriter.Cell.text(entry.typeZh()),
                XlsxWriter.Cell.text(entry.nameZh),
                XlsxWriter.Cell.text(entry.nameEn),
                XlsxWriter.Cell.decimal(entry.meanHpLoss),
                XlsxWriter.Cell.decimal(entry.medianHpLoss),
                XlsxWriter.Cell.decimal(entry.p90HpLoss),
                XlsxWriter.Cell.decimal(entry.ci95Low),
                XlsxWriter.Cell.decimal(entry.ci95High),
                XlsxWriter.Cell.integer(entry.sampleCount),
                XlsxWriter.Cell.decimal(entry.sampleShare),
                XlsxWriter.Cell.integer(entry.totalHpLoss),
                shareCell(entry.lossShare),
                XlsxWriter.Cell.text(note));
    }

    private static XlsxWriter.Cell shareCell(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return XlsxWriter.Cell.text("—");
        }
        return XlsxWriter.Cell.decimal(value);
    }

    private static void rule(XlsxWriter.Sheet sheet, String name, String value) {
        sheet.data(XlsxWriter.Cell.text(name), XlsxWriter.Cell.text(value));
    }

    private static void writeCsv(MonsterTierModel model, File file) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write('\uFEFF');
            writer.write("角色ID,角色类型,种子局筛选,幕,梯度,档内排名,非Boss总排名,类型,"
                    + "中文名,英文名,平均战损,中位数,P90,95%CI下限,95%CI上限,样本数,"
                    + "样本占比(%),净战损合计,战损贡献占比(%),低样本,图片\n");
            for (MonsterTierGroup group : model.groups) {
                for (MonsterTierEntry entry : group.allEntries()) {
                    writer.write(csv(model.characterId));
                    writer.write(',');
                    writer.write(csv(model.isAggregate() ? "全角色汇总" : "单角色"));
                    writer.write(',');
                    writer.write(csv(model.seedFilter.labelZh));
                    writer.write(',');
                    writer.write(csv(group.labelZh));
                    writer.write(',');
                    writer.write(csv(entry.tier == null ? "" : entry.tier.key));
                    writer.write(',');
                    writer.write(entry.tierRank <= 0 ? "" : Integer.toString(entry.tierRank));
                    writer.write(',');
                    writer.write(entry.nonBossRank <= 0 ? "" : Integer.toString(entry.nonBossRank));
                    writer.write(',');
                    writer.write(csv(entry.typeZh()));
                    writer.write(',');
                    writer.write(csv(entry.nameZh));
                    writer.write(',');
                    writer.write(csv(entry.nameEn));
                    writer.write(',');
                    writer.write(formatNumber(entry.meanHpLoss));
                    writer.write(',');
                    writer.write(formatNumber(entry.medianHpLoss));
                    writer.write(',');
                    writer.write(formatNumber(entry.p90HpLoss));
                    writer.write(',');
                    writer.write(formatNumber(entry.ci95Low));
                    writer.write(',');
                    writer.write(formatNumber(entry.ci95High));
                    writer.write(',');
                    writer.write(Integer.toString(entry.sampleCount));
                    writer.write(',');
                    writer.write(formatNumber(entry.sampleShare));
                    writer.write(',');
                    writer.write(Integer.toString(entry.totalHpLoss));
                    writer.write(',');
                    writer.write(formatShare(entry.lossShare));
                    writer.write(',');
                    writer.write(Boolean.toString(!entry.isRanked()));
                    writer.write(',');
                    writer.write(csv(imagePath(entry)));
                    writer.write('\n');
                }
            }
        } finally {
            writer.close();
        }
    }

    private static void writeJson(MonsterTierModel model, File file) throws IOException {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        root.put("schema_version", 2);
        root.put("generated_at", model.generatedAt);
        root.put("mod", "TinyCat");
        root.put("mod_version", MOD_VERSION);
        root.put("metric", "mean_actual_hp_loss");
        root.put("metric_note", "positive = HP lost, negative = net healing");

        Map<String, Object> character = new LinkedHashMap<String, Object>();
        character.put("id", model.characterId);
        character.put("kind", model.isAggregate() ? "aggregate" : "single");
        character.put("name_en", model.characterEn);
        character.put("name_zh", model.characterZh);
        character.put("rotate", model.rotate);
        root.put("character", character);

        Map<String, Object> dateRange = new LinkedHashMap<String, Object>();
        dateRange.put("start", model.startDate);
        dateRange.put("end", model.endDate);
        root.put("date_range", dateRange);

        Map<String, Object> filters = new LinkedHashMap<String, Object>();
        filters.put("include_abandons", model.includeAbandons);
        filters.put("seed_filter", model.seedFilter.key);
        filters.put("boss_min_sample", model.bossMinSample);
        filters.put("non_boss_min_sample", model.nonBossMinSample);
        filters.put("min_sample_rule",
                "boss >= 5; non-boss single character >= 10; aggregate >= 30");
        filters.put("a20_only", true);
        filters.put("current_save_slot_only", true);
        root.put("filters", filters);
        root.put("run_count", model.runCount);
        root.put("low_sample_count", model.lowSampleCount);

        Map<String, Object> tiering = new LinkedHashMap<String, Object>();
        tiering.put("boss_separate", true);
        tiering.put("boss_tier", "T0");
        tiering.put("non_boss_tiers", 4);
        tiering.put("highest_danger_tier", "T1");
        tiering.put("method", "equal-count rank slices by mean actual HP loss within each act");
        tiering.put("fixed_thresholds", false);
        tiering.put("low_sample_policy",
                "entries below the sample gate keep statistics and contribution fields, "
                        + "have tier=null, and are excluded from T0-T4");
        tiering.put("excluded", Arrays.asList("event encounters", "Act 4 fixed encounters",
                "treasure/reward floors"));
        root.put("tiering", tiering);

        List<Map<String, Object>> tiers = new ArrayList<Map<String, Object>>();
        for (MonsterTier tier : MonsterTier.values()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("key", tier.key);
            item.put("label_en", tier.labelEn);
            item.put("label_zh", tier.labelZh);
            item.put("color", tier.color);
            tiers.add(item);
        }
        root.put("tiers", tiers);

        List<Map<String, Object>> groups = new ArrayList<Map<String, Object>>();
        for (MonsterTierGroup group : model.groups) {
            Map<String, Object> groupJson = new LinkedHashMap<String, Object>();
            groupJson.put("act", group.act);
            groupJson.put("label_en", group.labelEn);
            groupJson.put("label_zh", group.labelZh);
            groupJson.put("boss_samples", group.bossSampleTotal);
            groupJson.put("non_boss_samples", group.nonBossSampleTotal);
            groupJson.put("boss_net_hp_loss", group.bossNetHpLoss);
            groupJson.put("non_boss_net_hp_loss", group.nonBossNetHpLoss);
            groupJson.put("low_sample_count", group.lowSampleCount);
            List<Map<String, Object>> entries = new ArrayList<Map<String, Object>>();
            for (MonsterTierEntry entry : group.allEntries()) {
                Map<String, Object> item = new LinkedHashMap<String, Object>();
                item.put("id", entry.id);
                item.put("name_en", entry.nameEn);
                item.put("name_zh", entry.nameZh);
                item.put("type", entry.type);
                item.put("low_sample", !entry.isRanked());
                item.put("tier", entry.tier == null ? null : entry.tier.key);
                item.put("tier_rank", entry.tierRank <= 0 ? null : entry.tierRank);
                item.put("non_boss_rank", entry.nonBossRank <= 0 ? null : entry.nonBossRank);
                item.put("samples", entry.sampleCount);
                item.put("sample_share_pct", round4(entry.sampleShare));
                item.put("total_hp_loss", entry.totalHpLoss);
                item.put("loss_share_pct",
                        Double.isNaN(entry.lossShare) ? null : round4(entry.lossShare));
                item.put("mean_hp_loss", round4(entry.meanHpLoss));
                item.put("median_hp_loss", round4(entry.medianHpLoss));
                item.put("p90_hp_loss", round4(entry.p90HpLoss));
                item.put("ci95_low", round4(entry.ci95Low));
                item.put("ci95_high", round4(entry.ci95High));
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
                .serializeNulls()
                .create()
                .toJson(root);
        writeString(file, json);
    }

    private static void writeHtml(MonsterTierModel model, File file, String workbookName) throws IOException {
        StringBuilder html = new StringBuilder(131072);
        html.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n");
        html.append("<meta charset=\"utf-8\">\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        html.append("<title>TinyCat 怪物危险度梯度</title>\n");
        html.append("<style>\n");
        html.append(":root{--bg:#0f1116;--panel:#171a21;--panel2:#1d212a;--text:#e7e2d4;--muted:#9aa1ad;--line:#2b303b;--gold:#e5c07b;--red:#e06c75}\n");
        html.append("*{box-sizing:border-box}\n");
        html.append("body{margin:0;padding:28px;background:radial-gradient(1200px 600px at 20% -10%,#1c2230 0%,var(--bg) 55%);color:var(--text);font-family:\"Segoe UI\",\"Microsoft YaHei\",\"PingFang SC\",Arial,sans-serif;line-height:1.5}\n");
        html.append(".wrap{max-width:1400px;margin:0 auto}\n");
        html.append("h1{margin:0 0 4px;font-size:30px;letter-spacing:.5px}\n");
        html.append("h1 small{color:var(--muted);font-size:15px;font-weight:400;margin-left:8px}\n");
        html.append(".sub{color:var(--muted);margin:0 0 18px;font-size:14px}\n");
        html.append(".chips{display:flex;flex-wrap:wrap;gap:8px;margin:14px 0 18px}\n");
        html.append(".chip{background:var(--panel);border:1px solid var(--line);border-radius:999px;padding:5px 11px;font-size:13px;color:var(--muted)}\n");
        html.append(".chip b{color:var(--text);font-weight:600}\n");
        html.append(".note{background:var(--panel);border:1px solid var(--line);border-left:3px solid var(--red);border-radius:8px;padding:12px 14px;margin:0 0 22px;color:var(--muted);font-size:13px}\n");
        html.append(".note b{color:var(--text)}\n");
        html.append(".links{display:flex;gap:14px;flex-wrap:wrap;margin:0 0 26px;font-size:13px}\n");
        html.append(".links a{color:var(--gold);text-decoration:none;border-bottom:1px dotted var(--gold)}\n");
        html.append("section.group{background:linear-gradient(180deg,var(--panel),#14171d);border:1px solid var(--line);border-radius:14px;padding:16px;margin:0 0 20px}\n");
        html.append("section.group h2{margin:0 0 6px;font-size:20px}\n");
        html.append("section.group h2 small{color:var(--muted);font-size:13px;font-weight:400;margin-left:8px}\n");
        html.append(".group-meta{color:var(--muted);font-size:12px;margin:0 0 12px}\n");
        html.append(".tier-row{display:grid;grid-template-columns:170px 1fr;gap:10px;padding:9px 0;border-top:1px solid var(--line)}\n");
        html.append(".tier-row:first-of-type{border-top:0}\n");
        html.append(".tier-label{display:flex;flex-direction:column;justify-content:flex-start;gap:2px;border-left:4px solid var(--line);padding:2px 0 2px 10px}\n");
        html.append(".tier-label .k{font-size:22px;font-weight:800;letter-spacing:.5px}\n");
        html.append(".tier-label .r{font-size:12px;color:var(--muted)}\n");
        html.append(".tier-label .c{font-size:11px;color:var(--muted)}\n");
        html.append(".entries{display:grid;grid-template-columns:repeat(auto-fill,minmax(230px,1fr));gap:8px}\n");
        html.append(".entry{display:flex;align-items:center;gap:9px;background:var(--panel2);border:1px solid var(--line);border-radius:9px;padding:6px;min-width:0}\n");
        html.append(".entry img{width:62px;height:62px;object-fit:contain;border-radius:6px;background:#0b0d11;flex:0 0 auto}\n");
        html.append(".entry .main{min-width:0;flex:1 1 auto}\n");
        html.append(".entry .zh{font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n");
        html.append(".entry .en{font-size:11px;color:var(--muted);white-space:nowrap;overflow:hidden;text-overflow:ellipsis}\n");
        html.append(".entry .stat{text-align:right;flex:0 0 auto}\n");
        html.append(".entry .hp{display:block;font-size:15px;font-weight:800;color:var(--gold);white-space:nowrap}\n");
        html.append(".entry .meta{display:block;font-size:10px;color:var(--muted);white-space:nowrap}\n");
        html.append(".empty{color:var(--muted);font-size:13px;padding:6px 0}\n");
        html.append(".entry.low{border-style:dashed;opacity:.92}\n");
        html.append("tr.low td{color:var(--muted)}\n");
        html.append("details.table-wrap{margin:8px 0 0;color:var(--muted);font-size:12px}\n");
        html.append("details.table-wrap summary{cursor:pointer;color:var(--gold);margin:4px 0}\n");
        html.append("table{border-collapse:collapse;width:100%;font-size:12px;margin:8px 0 0}\n");
        html.append("th,td{border-bottom:1px solid var(--line);padding:5px 6px;text-align:left;white-space:nowrap}\n");
        html.append("th{color:var(--muted);font-weight:600}\n");
        html.append("footer{color:var(--muted);font-size:12px;margin-top:26px;border-top:1px solid var(--line);padding-top:12px}\n");
        html.append("@media(max-width:760px){body{padding:16px}.tier-row{grid-template-columns:1fr}.tier-label{border-left:0;border-top:3px solid var(--line);padding:6px 0 0}h1{font-size:23px}}\n");
        html.append("</style>\n</head>\n<body>\n<div class=\"wrap\">\n");
        html.append("<h1>TinyCat 怪物危险度梯度 <small>Monster Danger Tiers</small></h1>\n");
        html.append("<p class=\"sub\">Slay the Spire · 按实际战损排序 · local run history export</p>\n");
        html.append("<div class=\"chips\">\n");
        html.append(chip("角色 / Character", model.characterZh + " / " + model.characterEn));
        html.append(chip("日期 / Date", empty(model.startDate) + " ~ " + empty(model.endDate)));
        html.append(chip("Runs / 对局", Integer.toString(model.runCount)));
        html.append(chip("Threshold / 样本门槛",
                "Boss " + model.bossMinSample + " · 非 Boss " + model.nonBossMinSample));
        html.append(chip("Abandons / 放弃局", model.includeAbandons ? "包含" : "排除"));
        html.append(chip("Seeds / 种子局", model.seedFilter.labelZh));
        html.append(chip("Generated / 生成时间", model.generatedAt));
        html.append("</div>\n");
        html.append("<div class=\"note\">\n");
        html.append("<b>口径：</b>主指标为<b>平均实际战损</b>（进入该层前 HP − 该层结束 HP）；正值掉血，负值表示净回血。");
        html.append("每幕 Boss 单独列为 <b>T0</b>，只按战损降序；非 Boss（含精英）按幕内平均战损降序<b>等人数</b>分为 <b>T1-T4</b>，T1 最高危。");
        html.append("不使用固定战损阈值，筛选变化后会重新分档。事件战、第四幕固定战、宝箱/奖励层不参与。");
        html.append("Boss 少于 ").append(model.bossMinSample).append(" 个样本、非 Boss 少于 ")
                .append(model.nonBossMinSample)
                .append(" 个样本的遭遇项不参与分档，但保留在每幕末尾的<b>“样本不足”</b>区。");
        if (model.lowSampleCount > 0) {
            html.append("本次低样本共 ").append(model.lowSampleCount).append(" 项。");
        }
        html.append("中位数、P90、95%CI、样本占比与战损贡献仅作辅助参考。<br>\n");
        html.append("<b>Images / 图片：</b>读取本地游戏目录 mods/TinyCat/monster-images；不访问网络。缺失图使用本地占位图。多敌遭遇使用代表图。");
        html.append("部分图包把 WebP 数据存成 .png 扩展名，Java 8 无法解码；请转换为真正的 PNG，否则该条目会回退到占位图或备用形态。\n");
        html.append("</div>\n");
        html.append("<div class=\"links\">");
        if (new File(file.getParentFile(), "monster-tiers.png").isFile()) {
            html.append("<a href=\"monster-tiers.png\">monster-tiers.png</a>");
        }
        if (new File(file.getParentFile(), "monster-tiers.jpg").isFile()) {
            html.append("<a href=\"monster-tiers.jpg\">monster-tiers.jpg</a>");
        }
        if (workbookName != null && new File(file.getParentFile(), workbookName).isFile()) {
            html.append("<a href=\"").append(escapeHtml(workbookName)).append("\">")
                    .append(escapeHtml(workbookName)).append("</a>");
        }
        html.append("<a href=\"monster-tiers.csv\">monster-tiers.csv</a>")
                .append("<a href=\"monster-tiers.json\">monster-tiers.json</a>")
                .append("<a href=\"README.txt\">README.txt</a></div>\n");

        for (MonsterTierGroup group : model.groups) {
            html.append("<section class=\"group\">\n");
            html.append("<h2>").append(escapeHtml(group.labelZh)).append("<small>")
                    .append(escapeHtml(group.labelEn)).append("</small></h2>\n");
            html.append("<p class=\"group-meta\">")
                    .append("Boss ").append(group.bosses.size()).append(" 项 · 非 Boss ")
                    .append(group.nonBosses.size()).append(" 项");
            if (group.lowSampleCount > 0) {
                html.append(" · 样本不足 ").append(group.lowSampleCount).append(" 项");
            }
            html.append(" · 同类样本 Boss ").append(group.bossSampleTotal)
                    .append(" / 非 Boss ").append(group.nonBossSampleTotal)
                    .append("</p>\n");
            if (group.isEmpty()) {
                html.append("<div class=\"empty\">No data / 无数据</div>\n");
            } else {
                for (MonsterTier tier : MonsterTier.values()) {
                    List<MonsterTierEntry> entries = group.entriesOf(tier);
                    html.append("<div class=\"tier-row\">\n");
                    html.append("<div class=\"tier-label\" style=\"border-left-color:")
                            .append(tier.color).append("\">\n");
                    html.append("<span class=\"k\" style=\"color:").append(tier.color).append("\">")
                            .append(tier.key).append("</span>\n");
                    html.append("<span class=\"r\">").append(escapeHtml(tier.labelZh)).append(" / ")
                            .append(escapeHtml(tier.labelEn)).append("</span>\n");
                    html.append("<span class=\"c\">").append(entries.size()).append(" 项</span>\n");
                    html.append("</div>\n<div class=\"entries\">\n");
                    if (entries.isEmpty()) {
                        html.append("<div class=\"empty\">-</div>\n");
                    } else {
                        for (MonsterTierEntry entry : entries) {
                            appendEntryCard(html, entry);
                        }
                    }
                    html.append("</div>\n</div>\n");
                }
                if (!group.lowSampleEntries.isEmpty()) {
                    html.append("<div class=\"tier-row\">\n");
                    html.append("<div class=\"tier-label\" style=\"border-left-color:#9aa1ad\">\n");
                    html.append("<span class=\"k\" style=\"color:#9aa1ad\">N/A</span>\n");
                    html.append("<span class=\"r\">样本不足 / Low sample</span>\n");
                    html.append("<span class=\"c\">").append(group.lowSampleCount)
                            .append(" 项 · 不参与分档</span>\n");
                    html.append("</div>\n<div class=\"entries\">\n");
                    for (MonsterTierEntry entry : group.lowSampleEntries) {
                        appendEntryCard(html, entry);
                    }
                    html.append("</div>\n</div>\n");
                }
            }
            html.append("<details class=\"table-wrap\"><summary>Table view / 表格视图</summary>\n");
            html.append("<table><thead><tr><th>Tier</th><th>#</th><th>Name / 名称</th><th>Type</th>")
                    .append("<th>Mean HP loss</th><th>Median</th><th>P90</th><th>95% CI</th><th>n</th>")
                    .append("<th>Sample %</th><th>Net HP loss</th><th>Contribution %</th></tr></thead><tbody>\n");
            for (MonsterTierEntry entry : group.allEntries()) {
                boolean low = !entry.isRanked();
                html.append(low ? "<tr class=\"low\">" : "<tr>");
                html.append("<td>").append(low ? "低样本" : escapeHtml(entry.tier.key))
                        .append("</td><td>")
                        .append(entry.tierRank <= 0 ? "—" : Integer.toString(entry.tierRank)).append("</td><td>")
                        .append(escapeHtml(entry.nameZh)).append(" / ")
                        .append(escapeHtml(entry.nameEn)).append("</td><td>")
                        .append(escapeHtml(entry.typeZh())).append("</td><td>")
                        .append(formatNumber(entry.meanHpLoss)).append("</td><td>")
                        .append(formatNumber(entry.medianHpLoss)).append("</td><td>")
                        .append(formatNumber(entry.p90HpLoss)).append("</td><td>")
                        .append(formatNumber(entry.ci95Low)).append(" ~ ")
                        .append(formatNumber(entry.ci95High)).append("</td><td>")
                        .append(entry.sampleCount).append("</td><td>")
                        .append(formatNumber(entry.sampleShare)).append("</td><td>")
                        .append(entry.totalHpLoss).append("</td><td>")
                        .append(formatShare(entry.lossShare)).append("</td></tr>\n");
            }
            html.append("</tbody></table></details>\n");
            html.append("</section>\n");
        }

        html.append("<footer>TinyCat ").append(MOD_VERSION)
                .append(" · 怪物危险度梯度 · local export · no network</footer>\n");
        html.append("</div>\n</body>\n</html>\n");
        writeString(file, html.toString());
    }

    private static void appendEntryCard(StringBuilder html, MonsterTierEntry entry) {
        boolean low = !entry.isRanked();
        html.append("<div class=\"entry").append(low ? " low" : "").append("\" title=\"")
                .append(escapeHtml(entry.nameEn)).append(" | ")
                .append(escapeHtml(entry.typeEn()))
                .append(" | n=").append(entry.sampleCount)
                .append(" | 净战损合计 ").append(entry.totalHpLoss)
                .append(" | 样本占比 ").append(formatShare(entry.sampleShare)).append("%")
                .append(" | 战损贡献 ").append(formatShare(entry.lossShare)).append("%")
                .append("\">\n");
        html.append("<img src=\"").append(escapeHtml(imagePath(entry)))
                .append("\" alt=\"").append(escapeHtml(entry.nameEn))
                .append("\" loading=\"lazy\">\n");
        html.append("<div class=\"main\"><div class=\"zh\">")
                .append(escapeHtml(entry.nameZh)).append("</div>");
        html.append("<div class=\"en\">").append(escapeHtml(entry.nameEn))
                .append(" · ").append(escapeHtml(entry.typeZh())).append("</div></div>\n");
        html.append("<div class=\"stat\"><span class=\"hp\">")
                .append(formatHp(entry.meanHpLoss)).append(" HP</span>");
        html.append("<span class=\"meta\">n=").append(entry.sampleCount)
                .append(" · 样本 ").append(formatShare(entry.sampleShare)).append("%")
                .append(" · 贡献 ").append(formatShare(entry.lossShare)).append("%</span>");
        html.append("<span class=\"meta\">CI ").append(formatNumber(entry.ci95Low))
                .append("~").append(formatNumber(entry.ci95High)).append("</span></div>\n");
        html.append("</div>\n");
    }

    private static void writeReadme(MonsterTierModel model, File file) throws IOException {
        StringBuilder text = new StringBuilder(12288);
        text.append("TinyCat Monster Danger Tiers\n");
        text.append("===========================\n\n");
        text.append("Mod version : TinyCat ").append(MOD_VERSION).append("\n");
        text.append("Generated   : ").append(model.generatedAt).append("\n");
        text.append("Character   : ").append(model.characterZh).append(" / ").append(model.characterEn).append("\n");
        text.append("Date range  : ").append(empty(model.startDate)).append(" ~ ").append(empty(model.endDate)).append("\n");
        text.append("Runs        : ").append(model.runCount).append("\n");
        text.append("Thresholds  : Boss >= ").append(model.bossMinSample)
                .append("; non-boss >= ").append(model.nonBossMinSample).append("\n");
        text.append("Low sample  : ").append(model.lowSampleCount)
                .append(" entries kept in the appendix, not tiered\n");
        text.append("Abandons    : ").append(model.includeAbandons ? "included" : "excluded").append("\n");
        text.append("Seed filter : ").append(model.seedFilter.labelEn).append(" / ")
                .append(model.seedFilter.labelZh).append("\n\n");

        text.append("Files\n");
        text.append("-----\n");
        text.append("怪物危险度.xlsx      - Chinese workbook, one sheet per act\n");
        text.append("monster-tiers.csv    - one row per encounter, UTF-8 with BOM\n");
        text.append("monster-tiers.json   - structured data and metadata\n");
        text.append("monster-tiers.html   - visual tier list, opens in any modern browser\n");
        text.append("monster-tiers.png    - shareable full image (lossless)\n");
        text.append("monster-tiers.jpg    - shareable full image (JPEG)\n");
        text.append("images/monsters/     - normalized local images or placeholder\n");
        text.append("README.txt           - this file\n\n");

        text.append("Metric / 指标\n");
        text.append("-------------\n");
        text.append("Primary metric is mean actual HP loss per encounter:\n");
        text.append("  actual HP loss = HP before the floor - HP at the end of the floor\n");
        text.append("Positive = HP lost. Negative = net healing during the encounter.\n");
        text.append("Median, P90, the 95% confidence interval, sample share and loss contribution\n");
        text.append("are auxiliary only.\n");
        text.append("主指标为每次遭遇的平均实际战损（战前 HP − 战后 HP）；中位数、P90、95%CI、\n");
        text.append("样本占比与战损贡献不参与分档。\n\n");

        text.append("Sample gates / 样本门槛\n");
        text.append("-----------------------\n");
        text.append("Boss: at least 5 samples per encounter.\n");
        text.append("Non-boss single character: at least 10 samples.\n");
        text.append("Non-boss all-character aggregate: at least 30 samples.\n");
        text.append("Entries below the gate are kept in a separate low-sample appendix with their\n");
        text.append("statistics and contribution fields, but tier is null and they never enter T0-T4.\n");
        text.append("Boss 每项至少 5 个样本；非 Boss 单角色至少 10 个，全角色汇总至少 30 个。\n");
        text.append("低于门槛的条目保留在“样本不足”附录，展示均值、中位数、P90、95%CI、样本占比\n");
        text.append("和净战损贡献，但不参与 T0-T4 分档。统计页的最小样本下拉框不控制本导出。\n\n");

        text.append("Tier rules / 分档\n");
        text.append("-----------------\n");
        text.append("T0 : Bosses only, per act, sorted by mean HP loss; never mixed with non-boss.\n");
        text.append("T1 : highest danger among non-boss encounters.\n");
        text.append("T2-T4 : lower danger, assigned by equal-count rank slices, not fixed HP thresholds.\n");
        text.append("Non-boss includes elites. Tiers are recalculated for every filter combination.\n");
        text.append("Events, Act 4 fixed fights, treasure/reward floors are excluded using path_per_floor.\n");
        text.append("T0：每幕 Boss 单独排序，不与非 Boss 混排；T1 最高危；\n");
        text.append("T1-T4：非 Boss（含精英）按幕内平均战损等人数切分，不用固定阈值，筛选后重算；\n");
        text.append("事件战、第四幕固定战、宝箱/奖励层不参与。\n\n");

        text.append("Contribution columns / 贡献列\n");
        text.append("-----------------------------\n");
        text.append("Sample share = encounter samples / all samples of the same category (boss or\n");
        text.append("non-boss) in the same act.\n");
        text.append("Total net HP loss = sum of actual HP loss across that encounter's samples.\n");
        text.append("Loss contribution = encounter net HP loss / same-category net HP loss in the act;\n");
        text.append("shown as - when the category total is zero or negative.\n");
        text.append("样本占比 = 该遭遇样本数 ÷ 同幕同类（Boss 或非 Boss）样本总数；\n");
        text.append("净战损合计 = 该遭遇所有样本的实际战损之和；\n");
        text.append("战损贡献占比 = 该遭遇净战损合计 ÷ 同幕同类净战损合计；同类合计 ≤ 0 时显示 —。\n\n");

        text.append("Images / 图片\n");
        text.append("-------------\n");
        text.append("Source lookup order:\n");
        text.append("1. <game>/mods/TinyCat/monster-images/ (recursive, recommended)\n");
        text.append("2. <game>/monster-images/ (recursive fallback)\n");
        text.append("3. tinycat/monsters/ inside the mod jar (optional custom build)\n");
        text.append("4. locally generated placeholder\n");
        text.append("Recommended layout after extracting a local image pack:\n");
        text.append("  mods/TinyCat/monster-images/Bosses/\n");
        text.append("  mods/TinyCat/monster-images/小怪/\n");
        text.append("  mods/TinyCat/monster-images/精英/\n");
        text.append("Images are trimmed, fitted to 256x256 and cached under\n");
        text.append("monster-tier-export/cache/v1/monsters; the cache is not included in this zip.\n");
        text.append("No network request is made. The mod does not bundle this image pack.\n");
        text.append("Make sure you have the right to use any external images you provide.\n");
        text.append("NOTE: some packs store WebP data with a .png extension. Java 8 ImageIO cannot\n");
        text.append("decode WebP; convert those files to real PNG. Unreadable entries fall back to a\n");
        text.append("placeholder or alternate art (Awakened One uses Unawakened_Form).\n");
        text.append("图片读取顺序：游戏目录 mods/TinyCat/monster-images → monster-images → jar 内资源 → 占位图。\n");
        text.append("注意：部分图包的 .png 实际是 WebP，Java 8 无法解码；请转换为真正的 PNG，\n");
        text.append("否则该条目会使用占位图或备用形态（觉醒者回退到未觉醒形态）。\n");
        text.append("图片包不随模组分发；请确认外部图源的使用授权。\n\n");

        text.append("How to open / 打开方式\n");
        text.append("----------------------\n");
        text.append("Double-click monster-tiers.html, or open it with any modern browser.\n");
        text.append("双击 monster-tiers.html，或用任意现代浏览器打开。\n");
        writeString(file, text.toString());
    }

    private static XlsxWriter.Sheet newSheet(XlsxWriter wb, String sheetName, String title,
                                             String context, String... headers) {
        XlsxWriter.Sheet sheet = wb.addSheet(sheetName);
        sheet.title(title, headers.length);
        sheet.context(context, headers.length);
        sheet.blank();
        sheet.header(headers);
        return sheet;
    }

    private static void noData(XlsxWriter.Sheet sheet) {
        sheet.data(XlsxWriter.Cell.text("无数据（当前筛选下没有达到最小样本的遭遇）"));
    }

    private static String context(MonsterTierModel model, String extra) {
        StringBuilder text = new StringBuilder(320);
        text.append("角色：").append(model.characterZh)
                .append(model.isAggregate() ? "（4 角色汇总，不是第 5 个角色）" : "")
                .append(" ｜ 对局：").append(model.runCount)
                .append(" ｜ 日期：").append(empty(model.startDate)).append(" ~ ").append(empty(model.endDate))
                .append(" ｜ 种子局：").append(model.seedFilter.labelZh)
                .append(" ｜ 放弃局：").append(model.includeAbandons ? "包含" : "排除")
                .append(" ｜ 样本门槛：Boss ").append(model.bossMinSample)
                .append(" / 非 Boss ").append(model.nonBossMinSample)
                .append(" ｜ 范围：A20 / 当前存档位 / 非每日与特殊局")
                .append(" ｜ 生成时间：").append(model.generatedAt);
        if (extra != null && !extra.isEmpty()) {
            text.append(" ｜ ").append(extra);
        }
        return text.toString();
    }

    private static String chip(String label, String value) {
        return "<span class=\"chip\">" + escapeHtml(label) + ": <b>" + escapeHtml(value) + "</b></span>\n";
    }

    private static String imagePath(MonsterTierEntry entry) {
        if (entry == null || entry.imagePath == null || entry.imagePath.isEmpty()) {
            return "images/monsters/placeholder.png";
        }
        return entry.imagePath.replace('\\', '/');
    }

    private static String formatHp(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private static String formatNumber(double value) {
        return String.format(Locale.US, "%.3f", value);
    }

    private static String formatShare(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "—";
        }
        return formatNumber(value);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0D) / 10000.0D;
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
                "TinyCat monster image cache\n"
                        + "==========================\n\n"
                        + "Shared by Monster Danger Tier exports. Not included in any export zip.\n"
                        + "Deleting this folder is safe; the next export will rebuild it.\n"
                        + "If you replaced an external monster image, delete this folder to refresh.\n");
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
