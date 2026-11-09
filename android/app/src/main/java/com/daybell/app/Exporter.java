package com.daybell.app;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.pdf.PdfDocument;
import android.icu.text.NumberFormat;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.json.JSONArray;
import org.json.JSONObject;

/** Turns the Money report (built by the page) into a PDF or an Excel (.xlsx) file. */
final class Exporter {
    private Exporter() {}

    private static NumberFormat numbers(String currency) {
        Locale loc = "₹".equals(currency) ? new Locale("en", "IN") : Locale.US;
        NumberFormat nf = NumberFormat.getNumberInstance(loc);
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return nf;
    }

    private static String amount(NumberFormat nf, String cur, double v, boolean signed) {
        String s = cur + (cur.length() > 1 ? " " : "") + nf.format(Math.abs(v));
        if (v < 0) return "−" + s;
        return signed && v > 0 ? "+" + s : s;
    }

    private static String prettyDate(String ymd) {
        try { return LocalDate.parse(ymd).format(DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH)); }
        catch (Exception e) { return ymd; }
    }

    /* ======================= PDF ======================= */

    private static final int W = 595, H = 842, M = 40; // A4 in points
    private static final int[] COLS = {72, 58, 86, 160, 64, 75}; // sums to 515
    private static final String[] HEAD = {"Date", "Type", "Category", "Note", "Paid by", "Amount"};

    private static final class Pdf {
        final PdfDocument doc = new PdfDocument();
        PdfDocument.Page page;
        Canvas c;
        float y;
        int pageNo = 0;
        final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint bold = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint muted = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint fill = new Paint();
        final String footer;

        Pdf(String footer) {
            this.footer = footer;
            text.setColor(0xFF17201B); text.setTextSize(9.5f);
            bold.setColor(0xFF17201B); bold.setTextSize(9.5f); bold.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            muted.setColor(0xFF5C6A62); muted.setTextSize(8.5f);
        }

        void newPage() {
            if (page != null) finishPage();
            pageNo++;
            page = doc.startPage(new PdfDocument.PageInfo.Builder(W, H, pageNo).create());
            c = page.getCanvas();
            y = M;
        }

        void finishPage() {
            muted.setTextAlign(Paint.Align.LEFT);
            c.drawText(footer, M, H - 24, muted);
            muted.setTextAlign(Paint.Align.RIGHT);
            c.drawText("Page " + pageNo, W - M, H - 24, muted);
            muted.setTextAlign(Paint.Align.LEFT);
            doc.finishPage(page);
        }

        boolean needs(float h) { return y + h > H - 50; }

        String fit(String s, Paint p, float width) {
            if (s == null) return "";
            if (p.measureText(s) <= width) return s;
            int n = p.breakText(s, true, width - p.measureText("…"), null);
            return s.substring(0, Math.max(0, n)) + "…";
        }

        void row(String[] cells, Paint p, int bg, int amountColor) {
            float h = 20;
            if (bg != 0) { fill.setColor(bg); c.drawRect(M, y, W - M, y + h, fill); }
            float x = M;
            for (int i = 0; i < cells.length; i++) {
                Paint use = p;
                if (i == cells.length - 1 && amountColor != 0) {
                    use = new Paint(p); use.setColor(amountColor);
                }
                String s = fit(cells[i], use, COLS[i] - 10);
                if (i == cells.length - 1) {
                    use.setTextAlign(Paint.Align.RIGHT);
                    c.drawText(s, x + COLS[i] - 6, y + 13.5f, use);
                    use.setTextAlign(Paint.Align.LEFT);
                } else {
                    c.drawText(s, x + 6, y + 13.5f, use);
                }
                x += COLS[i];
            }
            y += h;
        }

        void header() {
            Paint h = new Paint(bold); h.setColor(Color.WHITE);
            row(HEAD, h, 0xFFE4550D, 0);
        }
    }

    static byte[] pdf(JSONObject r) throws Exception {
        String cur = r.optString("currency", "₹");
        NumberFormat nf = numbers(cur);
        Pdf p = new Pdf("Daybell · generated " + r.optString("generated"));
        p.newPage();

        Paint title = new Paint(Paint.ANTI_ALIAS_FLAG);
        title.setColor(0xFF17201B); title.setTextSize(20); title.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.c.drawText(r.optString("title", "Money report"), M, p.y + 18, title);
        p.y += 34;
        Paint sub = new Paint(p.muted); sub.setTextSize(11);
        p.c.drawText(r.optString("period"), M, p.y, sub);
        p.y += 20;

        // Summary boxes
        String[][] boxes = {
                {"Income", amount(nf, cur, r.optDouble("income"), false)},
                {"Expenses", amount(nf, cur, r.optDouble("expense"), false)},
                {"Balance", amount(nf, cur, r.optDouble("balance"), false)}};
        int[] colors = {0xFF1E7356, 0xFFB83A32, r.optDouble("balance") < 0 ? 0xFFB83A32 : 0xFFE4550D};
        float bw = (W - 2 * M - 20) / 3f;
        for (int i = 0; i < 3; i++) {
            float x = M + i * (bw + 10);
            p.fill.setColor(0xFFFBF1EB);
            p.c.drawRoundRect(x, p.y, x + bw, p.y + 52, 8, 8, p.fill);
            p.c.drawText(boxes[i][0].toUpperCase(Locale.ROOT), x + 12, p.y + 18, p.muted);
            Paint v = new Paint(title); v.setTextSize(15); v.setColor(colors[i]);
            p.c.drawText(p.fit(boxes[i][1], v, bw - 24), x + 12, p.y + 40, v);
        }
        p.y += 72;

        // Transactions table
        Paint h2 = new Paint(title); h2.setTextSize(13);
        JSONArray rows = r.optJSONArray("rows");
        int n = rows == null ? 0 : rows.length();
        p.c.drawText("Transactions (" + n + ")", M, p.y, h2);
        p.y += 10;
        p.header();
        for (int i = 0; i < n; i++) {
            JSONObject t = rows.getJSONObject(i);
            if (p.needs(20)) { p.newPage(); p.header(); }
            double a = t.optDouble("amount");
            p.row(new String[]{prettyDate(t.optString("date")), t.optString("type"), t.optString("category"),
                            t.optString("note"), t.optString("method"), amount(nf, cur, a, true)},
                    p.text, i % 2 == 1 ? 0xFFFBF5F1 : 0, a < 0 ? 0xFFB83A32 : 0xFF1E7356);
        }
        // Total line
        if (p.needs(24)) p.newPage();
        p.fill.setColor(0xFFE4550D);
        p.c.drawRect(M, p.y, W - M, p.y + 1.2f, p.fill);
        p.y += 4;
        p.row(new String[]{"Total", "", "", "", "", amount(nf, cur, r.optDouble("balance"), true)}, p.bold, 0, 0);

        // Category summary
        JSONArray cats = r.optJSONArray("categories");
        if (cats != null && cats.length() > 0) {
            if (p.needs(60)) p.newPage(); else p.y += 22;
            p.c.drawText("Spending by category", M, p.y, h2);
            p.y += 10;
            Paint hh = new Paint(p.bold); hh.setColor(Color.WHITE);
            p.fill.setColor(0xFFE4550D);
            p.c.drawRect(M, p.y, W - M, p.y + 20, p.fill);
            p.c.drawText("Category", M + 6, p.y + 13.5f, hh);
            hh.setTextAlign(Paint.Align.RIGHT);
            p.c.drawText("Amount", M + 360, p.y + 13.5f, hh);
            p.c.drawText("Share", W - M - 6, p.y + 13.5f, hh);
            p.y += 20;
            for (int i = 0; i < cats.length(); i++) {
                if (p.needs(20)) p.newPage();
                JSONObject cObj = cats.getJSONObject(i);
                double share = cObj.optDouble("share");
                if (i % 2 == 1) { p.fill.setColor(0xFFFBF5F1); p.c.drawRect(M, p.y, W - M, p.y + 20, p.fill); }
                p.c.drawText(p.fit(cObj.optString("name"), p.text, 200), M + 6, p.y + 13.5f, p.text);
                // share bar
                p.fill.setColor(0xFFFDE4D6);
                p.c.drawRect(M + 375, p.y + 7, M + 465, p.y + 13, p.fill);
                p.fill.setColor(0xFFE4550D);
                p.c.drawRect(M + 375, p.y + 7, M + 375 + (float) (90 * Math.min(1, share)), p.y + 13, p.fill);
                Paint rt = new Paint(p.text); rt.setTextAlign(Paint.Align.RIGHT);
                p.c.drawText(amount(nf, cur, cObj.optDouble("amount"), false), M + 360, p.y + 13.5f, rt);
                p.c.drawText(Math.round(share * 100) + "%", W - M - 6, p.y + 13.5f, rt);
                p.y += 20;
            }
        }
        p.finishPage();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        p.doc.writeTo(out);
        p.doc.close();
        return out.toByteArray();
    }

    /* ======================= XLSX ======================= */

    private static String x(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '&': b.append("&amp;"); break;
                case '<': b.append("&lt;"); break;
                case '>': b.append("&gt;"); break;
                case '"': b.append("&quot;"); break;
                default:
                    if (ch >= 0x20 || ch == '\t' || ch == '\n') b.append(ch);
            }
        }
        return b.toString();
    }

    private static String col(int i) { return String.valueOf((char) ('A' + i)); }

    private static String str(String ref, String v, int style) {
        return "<c r=\"" + ref + "\" t=\"inlineStr\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><is><t xml:space=\"preserve\">" + x(v) + "</t></is></c>";
    }

    private static String num(String ref, double v, int style) {
        return "<c r=\"" + ref + "\"" + (style > 0 ? " s=\"" + style + "\"" : "") + "><v>" + v + "</v></c>";
    }

    private static long excelDate(String ymd) {
        return ChronoUnit.DAYS.between(LocalDate.of(1899, 12, 30), LocalDate.parse(ymd));
    }

    static byte[] xlsx(JSONObject r) throws Exception {
        String cur = r.optString("currency", "₹");
        String q = x("\"" + cur + "\"");
        String money = q + "#,##0.00;[Red]-" + q + "#,##0.00";

        // Styles: 0 normal, 1 header, 2 date, 3 money, 4 bold, 5 bold money, 6 percent, 7 title
        String styles = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"2\"><numFmt numFmtId=\"164\" formatCode=\"dd mmm yyyy\"/>"
                + "<numFmt numFmtId=\"165\" formatCode=\"" + money + "\"/></numFmts>"
                + "<fonts count=\"4\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><color rgb=\"FFFFFFFF\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"16\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFE4550D\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"8\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"165\" fontId=\"2\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"10\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"3\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles></styleSheet>";

        // Sheet 1: transactions
        JSONArray rows = r.optJSONArray("rows");
        int n = rows == null ? 0 : rows.length();
        StringBuilder s1 = new StringBuilder();
        s1.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>")
                .append("<cols><col min=\"1\" max=\"1\" width=\"14\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"10\" customWidth=\"1\"/>")
                .append("<col min=\"3\" max=\"3\" width=\"16\" customWidth=\"1\"/><col min=\"4\" max=\"4\" width=\"36\" customWidth=\"1\"/>")
                .append("<col min=\"5\" max=\"5\" width=\"14\" customWidth=\"1\"/><col min=\"6\" max=\"6\" width=\"16\" customWidth=\"1\"/></cols><sheetData>");
        s1.append("<row r=\"1\">");
        for (int i = 0; i < HEAD.length; i++) s1.append(str(col(i) + "1", HEAD[i], 1));
        s1.append("</row>");
        for (int i = 0; i < n; i++) {
            JSONObject t = rows.getJSONObject(i);
            int rr = i + 2;
            s1.append("<row r=\"").append(rr).append("\">");
            try { s1.append(num("A" + rr, excelDate(t.optString("date")), 2)); }
            catch (Exception e) { s1.append(str("A" + rr, t.optString("date"), 0)); }
            s1.append(str("B" + rr, t.optString("type"), 0))
                    .append(str("C" + rr, t.optString("category"), 0))
                    .append(str("D" + rr, t.optString("note"), 0))
                    .append(str("E" + rr, t.optString("method"), 0))
                    .append(num("F" + rr, t.optDouble("amount"), 3))
                    .append("</row>");
        }
        int tr = n + 2;
        s1.append("<row r=\"").append(tr).append("\">").append(str("E" + tr, "Total", 4))
                .append("<c r=\"F").append(tr).append("\" s=\"5\"><f>SUM(F2:F").append(n + 1).append(")</f><v>").append(r.optDouble("balance")).append("</v></c></row>");
        s1.append("</sheetData><autoFilter ref=\"A1:F").append(n + 1).append("\"/></worksheet>");

        // Sheet 2: summary
        StringBuilder s2 = new StringBuilder();
        s2.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                .append("<cols><col min=\"1\" max=\"1\" width=\"24\" customWidth=\"1\"/><col min=\"2\" max=\"2\" width=\"18\" customWidth=\"1\"/><col min=\"3\" max=\"3\" width=\"10\" customWidth=\"1\"/></cols><sheetData>");
        s2.append("<row r=\"1\">").append(str("A1", r.optString("title"), 7)).append("</row>");
        s2.append("<row r=\"2\">").append(str("A2", r.optString("period"), 0)).append("</row>");
        s2.append("<row r=\"4\">").append(str("A4", "Income", 4)).append(num("B4", r.optDouble("income"), 3)).append("</row>");
        s2.append("<row r=\"5\">").append(str("A5", "Expenses", 4)).append(num("B5", r.optDouble("expense"), 3)).append("</row>");
        s2.append("<row r=\"6\">").append(str("A6", "Balance", 4)).append(num("B6", r.optDouble("balance"), 5)).append("</row>");
        s2.append("<row r=\"8\">").append(str("A8", "Category", 1)).append(str("B8", "Spent", 1)).append(str("C8", "Share", 1)).append("</row>");
        JSONArray cats = r.optJSONArray("categories");
        int rr = 9;
        if (cats != null) {
            for (int i = 0; i < cats.length(); i++, rr++) {
                JSONObject c = cats.getJSONObject(i);
                s2.append("<row r=\"").append(rr).append("\">").append(str("A" + rr, c.optString("name"), 0))
                        .append(num("B" + rr, c.optDouble("amount"), 3)).append(num("C" + rr, c.optDouble("share"), 6)).append("</row>");
            }
        }
        s2.append("<row r=\"").append(rr + 1).append("\">").append(str("A" + (rr + 1), "Generated " + r.optString("generated") + " by Daybell", 0)).append("</row>");
        s2.append("</sheetData></worksheet>");

        String contentTypes = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/worksheets/sheet2.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>"
                + "</Types>";
        String rels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                + "</Relationships>";
        String workbook = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                + "<sheets><sheet name=\"Transactions\" sheetId=\"1\" r:id=\"rId1\"/><sheet name=\"Summary\" sheetId=\"2\" r:id=\"rId2\"/></sheets>"
                + "<definedNames><definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"0\" hidden=\"1\">Transactions!$A$1:$F$" + (n + 1) + "</definedName></definedNames>"
                + "</workbook>";
        String wbRels = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet2.xml\"/>"
                + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
                + "</Relationships>";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(out)) {
            put(z, "[Content_Types].xml", contentTypes);
            put(z, "_rels/.rels", rels);
            put(z, "xl/workbook.xml", workbook);
            put(z, "xl/_rels/workbook.xml.rels", wbRels);
            put(z, "xl/styles.xml", styles);
            put(z, "xl/worksheets/sheet1.xml", s1.toString());
            put(z, "xl/worksheets/sheet2.xml", s2.toString());
        }
        return out.toByteArray();
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }
}
