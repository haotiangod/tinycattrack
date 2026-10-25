package tinycat.tier;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 极简 OOXML .xlsx 写入器。
 *
 * 项目不引入 Apache POI 等第三方依赖，因此这里只实现导出所需的子集：
 * 多工作表、内联字符串、数字/百分比、基础样式、冻结表头、自动筛选和列宽。
 */
public final class XlsxWriter {
    /** 置信区间可信度配色：0 红（不可信）、1 绿（可信）、2 橙（一般）、-1 不上色。 */
    public static final int RELIABILITY_LOW = 0;
    public static final int RELIABILITY_HIGH = 1;
    public static final int RELIABILITY_MEDIUM = 2;
    public static final int RELIABILITY_NONE = -1;

    private final List<Sheet> sheets = new ArrayList<Sheet>();

    public Sheet addSheet(String name) {
        String unique = uniqueSheetName(sheetName(name));
        Sheet sheet = new Sheet(unique);
        sheets.add(sheet);
        return sheet;
    }

    private String uniqueSheetName(String base) {
        String candidate = base;
        int suffix = 2;
        while (hasSheetName(candidate)) {
            String tail = "(" + suffix + ")";
            int keep = Math.max(1, 31 - tail.length());
            candidate = (base.length() > keep ? base.substring(0, keep) : base) + tail;
            suffix++;
        }
        return candidate;
    }

    private boolean hasSheetName(String name) {
        for (Sheet sheet : sheets) {
            if (sheet.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    public void write(File file) throws IOException {
        if (sheets.isEmpty()) {
            throw new IOException("xlsx must contain at least one sheet");
        }
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            put(zip, "[Content_Types].xml", contentTypes());
            put(zip, "_rels/.rels", rootRelationships());
            put(zip, "xl/workbook.xml", workbook());
            put(zip, "xl/_rels/workbook.xml.rels", workbookRelationships());
            put(zip, "xl/styles.xml", styles());
            for (int i = 0; i < sheets.size(); i++) {
                put(zip, "xl/worksheets/sheet" + (i + 1) + ".xml", sheets.get(i).xml());
            }
        } finally {
            zip.close();
        }
    }

    private String contentTypes() {
        StringBuilder xml = new StringBuilder(2048);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">");
        xml.append("<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>");
        xml.append("<Default Extension=\"xml\" ContentType=\"application/xml\"/>");
        xml.append("<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>");
        xml.append("<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            xml.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
                    .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        xml.append("</Types>");
        return xml.toString();
    }

    private String rootRelationships() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
    }

    private String workbook() {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
        xml.append("<bookViews><workbookView/></bookViews><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            xml.append("<sheet name=\"").append(escape(sheets.get(i).name)).append("\" sheetId=\"")
                    .append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        xml.append("</sheets></workbook>");
        return xml.toString();
    }

    private String workbookRelationships() {
        StringBuilder xml = new StringBuilder(1024);
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
        xml.append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            xml.append("<Relationship Id=\"rId").append(i + 1)
                    .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
                    .append(i + 1).append(".xml\"/>");
        }
        xml.append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        xml.append("</Relationships>");
        return xml.toString();
    }

    private String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"2\">"
                + "<numFmt numFmtId=\"164\" formatCode=\"0.000\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"0.0\"/>"
                + "</numFmts>"
                + "<fonts count=\"8\">"
                + "<font><sz val=\"11\"/><color rgb=\"FF1F2937\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"16\"/><color rgb=\"FF111827\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><i/><sz val=\"10\"/><color rgb=\"FF6B7280\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FF92400E\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FF15803D\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFB45309\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFB91C1C\"/><name val=\"Microsoft YaHei\"/><family val=\"2\"/></font>"
                + "</fonts>"
                + "<fills count=\"8\">"
                + "<fill><patternFill patternType=\"none\"/></fill>"
                + "<fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF1F2937\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFF3F4F6\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF7E6\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE8F5E9\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFFF4E0\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFFDEBEC\"/><bgColor indexed=\"64\"/></patternFill></fill>"
                + "</fills>"
                + "<borders count=\"2\">"
                + "<border><left/><right/><top/><bottom/><diagonal/></border>"
                + "<border><left style=\"thin\"><color rgb=\"FFE5E7EB\"/></left><right style=\"thin\"><color rgb=\"FFE5E7EB\"/></right>"
                + "<top style=\"thin\"><color rgb=\"FFE5E7EB\"/></top><bottom style=\"thin\"><color rgb=\"FFE5E7EB\"/></bottom><diagonal/></border>"
                + "</borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"25\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyAlignment=\"1\"><alignment vertical=\"top\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"2\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\" wrapText=\"1\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"4\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"3\" fontId=\"4\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"4\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"10\" fontId=\"4\" fillId=\"4\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"7\" borderId=\"1\" xfId=\"0\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"3\" fontId=\"0\" fillId=\"7\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"7\" borderId=\"1\" xfId=\"0\" applyNumberFormat=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"right\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"5\" fillId=\"5\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"6\" fillId=\"6\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"0\" fontId=\"7\" fillId=\"7\" borderId=\"1\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\" applyAlignment=\"1\"><alignment horizontal=\"center\" vertical=\"center\"/></xf>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "<dxfs count=\"0\"/>"
                + "<tableStyles count=\"0\" defaultTableStyle=\"TableStyleMedium2\" defaultPivotStyle=\"PivotStyleLight16\"/>"
                + "</styleSheet>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String sheetName(String raw) {
        String name = raw == null ? "Sheet" : raw.trim();
        name = name.replaceAll("[\\\\/:*?\\[\\]]", " ");
        if (name.isEmpty()) {
            name = "Sheet";
        }
        if (name.length() > 31) {
            name = name.substring(0, 31);
        }
        return name;
    }

    private static String escape(String value) {
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
                    out.append("&apos;");
                    break;
                default:
                    if (c == '\t' || c == '\n' || c == '\r' || c >= 0x20) {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    public static final class Cell {
        private final String text;
        private final Double number;
        private final int style;

        private Cell(String text, Double number, int style) {
            this.text = text;
            this.number = number;
            this.style = style;
        }

        public static Cell text(String value) {
            return new Cell(value == null ? "" : value, null, 4);
        }

        public static Cell integer(Number value) {
            return value == null ? null : new Cell(null, value.doubleValue(), 5);
        }

        public static Cell decimal(double value) {
            return valid(value) ? new Cell(null, value, 7) : null;
        }

        public static Cell decimal1(double value) {
            return valid(value) ? new Cell(null, value, 6) : null;
        }

        public static Cell percent(double fraction) {
            return valid(fraction) ? new Cell(null, fraction, 8) : null;
        }

        public static Cell summaryText(String value) {
            return new Cell(value == null ? "" : value, null, 9);
        }

        public static Cell summaryInteger(Number value) {
            return value == null ? null : new Cell(null, value.doubleValue(), 10);
        }

        public static Cell summaryDecimal(double value) {
            return valid(value) ? new Cell(null, value, 11) : null;
        }

        public static Cell summaryPercent(double fraction) {
            return valid(fraction) ? new Cell(null, fraction, 12) : null;
        }

        /** 可信度列：绿 = 可信、橙 = 一般、红 = 不可信。 */
        public static Cell reliability(String value, int level) {
            int style = level == RELIABILITY_HIGH ? 22 : level == RELIABILITY_MEDIUM ? 23 : 24;
            return new Cell(value == null ? "" : value, null, style);
        }

        private Cell tint(int level) {
            int mapped = tintStyle(style, level);
            return mapped == style ? this : new Cell(text, number, mapped);
        }

        private static int tintStyle(int style, int level) {
            if (level == RELIABILITY_HIGH) {
                if (style == 4) return 13;
                if (style == 5) return 14;
                if (style == 8) return 15;
            } else if (level == RELIABILITY_MEDIUM) {
                if (style == 4) return 16;
                if (style == 5) return 17;
                if (style == 8) return 18;
            } else if (level == RELIABILITY_LOW) {
                if (style == 4) return 19;
                if (style == 5) return 20;
                if (style == 8) return 21;
            }
            return style;
        }


        private static boolean valid(double value) {
            return !Double.isNaN(value) && !Double.isInfinite(value);
        }
    }

    public static final class Sheet {
        private final String name;
        private final List<List<Cell>> rows = new ArrayList<List<Cell>>();
        private int headerRow = 0;
        private int titleSpan = 0;
        private int contextSpan = 0;
        private int maxColumns = 0;
        private final List<Double> widths = new ArrayList<Double>();
        private final Map<Integer, Double> widthOverrides = new HashMap<Integer, Double>();

        private Sheet(String name) {
            this.name = name;
        }

        public Sheet title(String text, int span) {
            ensureRow();
            List<Cell> row = new ArrayList<Cell>(1);
            row.add(new Cell(text == null ? "" : text, null, 1));
            rows.add(row);
            titleSpan = Math.max(1, span);
            maxColumns = Math.max(maxColumns, titleSpan);
            return this;
        }

        public Sheet context(String text, int span) {
            ensureRow();
            List<Cell> row = new ArrayList<Cell>(1);
            row.add(new Cell(text == null ? "" : text, null, 2));
            rows.add(row);
            contextSpan = Math.max(1, span);
            maxColumns = Math.max(maxColumns, contextSpan);
            return this;
        }

        public Sheet blank() {
            ensureRow();
            rows.add(new ArrayList<Cell>());
            return this;
        }

        /** 固定列宽，覆盖自动估算。 */
        public Sheet width(int column, double width) {
            widthOverrides.put(column, width);
            return this;
        }

        public Sheet header(String... values) {
            List<Cell> row = new ArrayList<Cell>(values.length);
            for (int i = 0; i < values.length; i++) {
                String value = values[i];
                row.add(new Cell(value == null ? "" : value, null, 3));
                measureCell(i, value);
            }
            rows.add(row);
            headerRow = rows.size();
            maxColumns = Math.max(maxColumns, values.length);
            return this;
        }

        public Sheet data(Cell... values) {
            List<Cell> row = new ArrayList<Cell>(values.length);
            for (int i = 0; i < values.length; i++) {
                Cell cell = values[i];
                row.add(cell);
                if (cell != null) {
                    measureCell(i, cell.text != null ? cell.text : "00000");
                }
            }
            rows.add(row);
            maxColumns = Math.max(maxColumns, values.length);
            return this;
        }

        /** 整行按可信度上色；只改文本/整数/百分比三种数据样式，其他单元格保持原样。 */
        public Sheet dataTinted(int level, Cell... values) {
            List<Cell> row = new ArrayList<Cell>(values.length);
            for (int i = 0; i < values.length; i++) {
                Cell cell = values[i] == null ? null : values[i].tint(level);
                row.add(cell);
                if (cell != null) {
                    measureCell(i, cell.text != null ? cell.text : "00000");
                }
            }
            rows.add(row);
            maxColumns = Math.max(maxColumns, values.length);
            return this;
        }

        private void ensureRow() {
            if (headerRow > 0) {
                throw new IllegalStateException("title/context must be written before data");
            }
        }

        private void measureCell(int column, String value) {
            double width = displayWidth(value);
            while (widths.size() <= column) {
                widths.add(8.0D);
            }
            if (width > widths.get(column)) {
                widths.set(column, width);
            }
        }

        private static double displayWidth(String value) {
            if (value == null) {
                return 8.0D;
            }
            double width = 0.0D;
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                width += c >= 0x2E80 ? 2.0D : 1.0D;
            }
            return Math.min(55.0D, Math.max(8.0D, width));
        }

        private String xml() {
            // 重新计算列宽（data 写入时已记录，这里统一截断到合理范围）
            int columns = Math.max(1, maxColumns);
            while (widths.size() < columns) {
                widths.add(8.0D);
            }
            StringBuilder xml = new StringBuilder(16384);
            xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>");
            xml.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" ")
                    .append("xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">");
            int lastRow = Math.max(1, rows.size());
            xml.append("<dimension ref=\"A1:").append(column(columns - 1)).append(lastRow).append("\"/>");
            xml.append("<sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\">");
            if (headerRow > 0 && lastRow > headerRow) {
                xml.append("<pane ySplit=\"").append(headerRow).append("\" topLeftCell=\"A")
                        .append(headerRow + 1).append("\" activePane=\"bottomLeft\" state=\"frozen\"/>");
                xml.append("<selection pane=\"bottomLeft\" activeCell=\"A").append(headerRow + 1)
                        .append("\" sqref=\"A").append(headerRow + 1).append("\"/>");
            }
            xml.append("</sheetView></sheetViews>");
            xml.append("<sheetFormatPr defaultRowHeight=\"15\"/>");
            xml.append("<cols>");
            for (int i = 0; i < columns; i++) {
                Double override = widthOverrides.get(i);
                double width = override != null ? override
                        : Math.min(55.0D, Math.max(8.0D, widths.get(i) + 2.0D));
                xml.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                        .append("\" width=\"").append(String.format(Locale.US, "%.1f", width))
                        .append("\" customWidth=\"1\"/>");
            }
            xml.append("</cols><sheetData>");
            for (int r = 0; r < rows.size(); r++) {
                List<Cell> row = rows.get(r);
                int rowNumber = r + 1;
                if (row.isEmpty()) {
                    xml.append("<row r=\"").append(rowNumber).append("\"/>");
                    continue;
                }
                xml.append("<row r=\"").append(rowNumber).append("\"");
                if (rowNumber == 1) {
                    xml.append(" ht=\"26\" customHeight=\"1\"");
                } else if (rowNumber == 2) {
                    xml.append(" ht=\"34\" customHeight=\"1\"");
                } else if (rowNumber == headerRow) {
                    xml.append(" ht=\"24\" customHeight=\"1\"");
                }
                xml.append(">");
                for (int c = 0; c < row.size(); c++) {
                    Cell cell = row.get(c);
                    if (cell == null) {
                        continue;
                    }
                    xml.append("<c r=\"").append(column(c)).append(rowNumber).append("\" s=\"")
                            .append(cell.style).append("\"");
                    if (cell.number != null) {
                        xml.append("><v>").append(String.format(Locale.US, "%.10f", cell.number)
                                .replaceAll("0+$", "").replaceAll("\\.$", "")).append("</v></c>");
                    } else {
                        xml.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">")
                                .append(escape(cell.text)).append("</t></is></c>");
                    }
                }
                xml.append("</row>");
            }
            xml.append("</sheetData>");
            if (headerRow > 0 && lastRow > headerRow) {
                String range = "A" + headerRow + ":" + column(columns - 1) + lastRow;
                xml.append("<autoFilter ref=\"").append(range).append("\"/>");
            }
            if (titleSpan > 1 || contextSpan > 1) {
                xml.append("<mergeCells count=\"");
                xml.append((titleSpan > 1 ? 1 : 0) + (contextSpan > 1 ? 1 : 0)).append("\">");
                if (titleSpan > 1) {
                    xml.append("<mergeCell ref=\"A1:").append(column(titleSpan - 1)).append("1\"/>");
                }
                if (contextSpan > 1) {
                    xml.append("<mergeCell ref=\"A2:").append(column(contextSpan - 1)).append("2\"/>");
                }
                xml.append("</mergeCells>");
            }
            xml.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>");
            xml.append("<pageSetup orientation=\"landscape\"/>");
            xml.append("</worksheet>");
            return xml.toString();
        }

        private static String column(int index) {
            StringBuilder name = new StringBuilder(3);
            int value = index + 1;
            while (value > 0) {
                int remainder = (value - 1) % 26;
                name.insert(0, (char) ('A' + remainder));
                value = (value - 1) / 26;
            }
            return name.toString();
        }
    }
}
