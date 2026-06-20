package com.fujian.tobacco.data.parser;

import android.util.Log;

import com.fujian.tobacco.data.model.SupplyItem;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParserFactory;

/**
 * 纯 Android API 解析卷烟货源投放策略 .xlsx
 *
 * Excel 结构（档级投放 Sheet）：
 *   Row 3: 档位 | ... | 30档 | 29档 | ... | 1档 | 备注
 *   Row 4: 代码 | 卷烟策略数 | 品类 | 区域 | 投放户数 | ...
 *   Row 5+: 数据 (A=代码 B=名称 C=品类 D=区域 E=户数 F-AI=30~1档配额 AJ=备注)
 *
 * .xlsx = ZIP 包，内含 xl/sharedStrings.xml + xl/worksheets/sheet1.xml
 */
public class ExcelParser {
    private static final String TAG = "ExcelParser";

    private static final int TIER_START_COL = 5;  // F列 = 0-based索引5
    private static final int MAX_TIER = 30;
    private static final int MIN_TIER = 1;
    private static final int TOTAL_COLS = 36;      // A-AJ

    /**
     * 解析 Excel，返回指定挡位的投放策略
     *
     * @param filePath 本地文件路径
     * @param userTier 用户挡位 (1-30)，0=显示全部
     */
    public static List<SupplyItem> parse(String filePath, int userTier) throws Exception {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new Exception("文件不存在: " + filePath);
        }

        try (ZipFile zip = new ZipFile(file)) {
            List<String> ss = parseSharedStrings(zip);

            ZipEntry entry = zip.getEntry("xl/worksheets/sheet1.xml");
            if (entry == null) throw new Exception("未找到 sheet1.xml");

            try (InputStream is = zip.getInputStream(entry)) {
                SheetHandler handler = new SheetHandler(ss);
                SAXParserFactory factory = SAXParserFactory.newInstance();
                factory.setNamespaceAware(false);
                factory.setValidating(false);
                XMLReader reader = factory.newSAXParser().getXMLReader();
                reader.setContentHandler(handler);
                reader.parse(new InputSource(is));

                List<List<String>> rows = handler.getRows();
                Log.d(TAG, "读取到 " + rows.size() + " 行");

                if (rows.size() < 5) {
                    throw new Exception("不足5行，实际: " + rows.size());
                }

                // 数据从第5行开始(0-based index=4)
                // 挡位列: F(5)=30档 → 5+(30-userTier)
                int tierCol = (userTier >= MIN_TIER && userTier <= MAX_TIER)
                        ? TIER_START_COL + (MAX_TIER - userTier) : -1;

                Log.d(TAG, "挡位=" + userTier + " → 列" + colLabel(tierCol)
                        + " (idx=" + tierCol + ")");

                List<SupplyItem> items = new ArrayList<>();
                String lastBrand = "";

                for (int r = 4; r < rows.size(); r++) {
                    List<String> row = rows.get(r);
                    if (row == null) continue;

                    // 确保行至少有 TOTAL_COLS 个元素
                    while (row.size() < TOTAL_COLS) row.add("");

                    // 跳过完全空行
                    if (isRowEmpty(row)) continue;

                    String code = row.get(0).trim();    // A
                    String brand = row.get(1).trim();   // B
                    String category = row.get(2).trim(); // C
                    String region = row.get(3).trim();  // D
                    String note = row.get(35).trim();   // AJ

                    boolean hasCode = code.matches("\\d{7,9}");

                    if (hasCode) {
                        lastBrand = brand;

                        SupplyItem item = new SupplyItem();
                        item.setProductCode(code);
                        item.setBrandName(brand);
                        item.setTierCategory(category);
                        item.setRegion(region);
                        item.setSupplyNote(note);

                        // 投放户数
                        try {
                            String e = row.get(4).trim();
                            item.setTotalRetailers(e.isEmpty() ? 0 : (int) Double.parseDouble(e));
                        } catch (Exception e) { item.setTotalRetailers(0); }

                        // 挡位配额
                        int quota = 0;
                        if (tierCol >= 0) {
                            quota = parseInt(row.get(tierCol));
                        }
                        item.setPersonalQuota(quota);
                        item.setUserTier(userTier);

                        items.add(item);

                    } else if (!brand.isEmpty() && !brand.equals(lastBrand)) {
                        // B列有品牌名但A列无代码（子行/延续行）
                        SupplyItem item = new SupplyItem();
                        item.setBrandName(brand);
                        item.setTierCategory(row.get(2).trim());
                        item.setRegion(region);
                        item.setSupplyNote(note);
                        if (tierCol >= 0) {
                            item.setPersonalQuota(parseInt(row.get(tierCol)));
                        }
                        item.setUserTier(userTier);
                        items.add(item);
                    }
                }

                Log.d(TAG, "解析完成: " + items.size() + " 条, 有配额: "
                        + countWithQuota(items));
                return items;
            }
        }
    }

    // ========== 共享字符串 ==========

    private static List<String> parseSharedStrings(ZipFile zip) throws Exception {
        List<String> strings = new ArrayList<>();
        ZipEntry entry = zip.getEntry("xl/sharedStrings.xml");
        if (entry == null) return strings;
        try (InputStream is = zip.getInputStream(entry)) {
            SsHandler h = new SsHandler();
            parseXml(is, h);
            strings = h.strings;
        }
        return strings;
    }

    private static void parseXml(InputStream is, DefaultHandler handler) throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        f.setValidating(false);
        XMLReader r = f.newSAXParser().getXMLReader();
        r.setContentHandler(handler);
        r.parse(new InputSource(is));
    }

    // ========== SAX Handlers ==========

    /** 共享字符串表解析 */
    static class SsHandler extends DefaultHandler {
        List<String> strings = new ArrayList<>();
        StringBuilder buf;
        boolean inT;

        @Override
        public void startElement(String uri, String ln, String qn, Attributes a) {
            if (matches(ln, qn, "t")) { inT = true; buf = new StringBuilder(); }
        }
        @Override public void characters(char[] ch, int s, int len) {
            if (inT && buf != null) buf.append(ch, s, len);
        }
        @Override
        public void endElement(String uri, String ln, String qn) {
            if (matches(ln, qn, "t")) {
                inT = false;
                strings.add(buf != null ? buf.toString() : "");
                buf = null;
            }
        }
    }

    /** 工作表解析 —— ⭐ 根据列字母填充空位 */
    static class SheetHandler extends DefaultHandler {
        private final List<String> ss;
        private final List<List<String>> rows = new ArrayList<>();
        private List<String> curRow;
        private StringBuilder curText;
        private boolean inV;
        private String cellType;
        private int curColIdx;  // 当前列索引

        SheetHandler(List<String> ss) { this.ss = ss; }

        @Override
        public void startElement(String uri, String ln, String qn, Attributes a) {
            if (matches(ln, qn, "row")) {
                curRow = new ArrayList<>();
            } else if (matches(ln, qn, "c")) {
                cellType = a.getValue("t");
                // 从 cell reference 提取列字母 (如 "F5" → "F")
                String ref = a.getValue("r");
                if (ref != null) {
                    String colLetter = ref.replaceAll("\\d", "");
                    curColIdx = colToIndex(colLetter);
                }
            } else if (matches(ln, qn, "v")) {
                inV = true;
                curText = new StringBuilder();
            }
        }

        @Override public void characters(char[] ch, int s, int len) {
            if (inV && curText != null) curText.append(ch, s, len);
        }

        @Override
        public void endElement(String uri, String ln, String qn) {
            if (matches(ln, qn, "v")) {
                inV = false;
            } else if (matches(ln, qn, "c")) {
                if (curRow != null) {
                    // ⭐ 填充空位列：确保 row size 覆盖到当前列
                    while (curRow.size() <= curColIdx) curRow.add("");
                    // 取值
                    String val = "";
                    if (curText != null) {
                        if ("s".equals(cellType)) {
                            try {
                                int idx = Integer.parseInt(curText.toString().trim());
                                val = (idx >= 0 && idx < ss.size()) ? ss.get(idx) : "";
                            } catch (NumberFormatException e) { val = curText.toString(); }
                        } else {
                            val = curText.toString();
                        }
                    }
                    curRow.set(curColIdx, val);
                }
                curText = null;
                cellType = null;
            } else if (matches(ln, qn, "row")) {
                if (curRow != null) rows.add(curRow);
                curRow = null;
            }
        }

        List<List<String>> getRows() { return rows; }
    }

    // ========== 工具 ==========

    /** 列字母 → 0-based 索引: A=0, B=1, ..., Z=25, AA=26, AJ=35 */
    static int colToIndex(String col) {
        int n = 0;
        for (char c : col.toCharArray()) {
            n = n * 26 + (c - 'A' + 1);
        }
        return n - 1;
    }

    static String colLabel(int idx) {
        StringBuilder sb = new StringBuilder();
        int n = idx;
        while (n >= 0) {
            sb.insert(0, (char) ('A' + (n % 26)));
            n = n / 26 - 1;
        }
        return sb.toString();
    }

    private static boolean matches(String ln, String qn, String name) {
        return name.equals(ln) || name.equals(qn);
    }

    private static boolean isRowEmpty(List<String> row) {
        for (String s : row) if (s != null && !s.trim().isEmpty()) return false;
        return true;
    }

    private static int parseInt(String s) {
        if (s == null || s.isEmpty()) return 0;
        try { return (int) Double.parseDouble(s.replaceAll("[^0-9.-]", "")); }
        catch (NumberFormatException e) { return 0; }
    }

    private static int countWithQuota(List<SupplyItem> items) {
        int n = 0;
        for (SupplyItem item : items) if (item.getPersonalQuota() > 0) n++;
        return n;
    }
}
