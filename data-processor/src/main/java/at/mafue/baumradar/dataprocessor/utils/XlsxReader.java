package at.mafue.baumradar.dataprocessor.utils;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal, dependency-free reader for the first worksheet of an XLSX (Office
 * Open XML) workbook.
 *
 * <p>An {@code .xlsx} file is a ZIP archive of XML parts. This reader pulls out
 * {@code xl/worksheets/sheet1.xml} and {@code xl/sharedStrings.xml}, resolves
 * shared-string references, and returns the sheet as a list of string rows
 * (row 0 being the header). Empty cells are preserved by honoring each cell's
 * column reference (e.g. {@code "D2"}), so columns stay aligned even when the
 * source omits blank cells. Parsing uses the JDK's built-in StAX parser, so no
 * third-party library (e.g. Apache POI) is required.
 *
 * <p>The whole sheet is materialized in memory, which is appropriate for the
 * city-sized tree cadastres handled here (tens of thousands of rows).
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /**
     * Reads the first worksheet of an XLSX stream into rows of cell strings.
     *
     * @param xlsxStream the raw {@code .xlsx} bytes (ZIP container)
     * @return rows of the sheet; each row is a {@code String[]} indexed by
     *         zero-based column, with row 0 being the header
     * @throws Exception if the archive lacks a worksheet or XML parsing fails
     */
    public static List<String[]> read(InputStream xlsxStream) throws Exception {
        byte[] sharedXml = null;
        byte[] sheetXml = null;
        try (ZipInputStream zis = new ZipInputStream(xlsxStream)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();
                if ("xl/sharedStrings.xml".equals(name)) {
                    sharedXml = readAll(zis);
                } else if ("xl/worksheets/sheet1.xml".equals(name)) {
                    sheetXml = readAll(zis);
                }
            }
        }
        if (sheetXml == null) {
            throw new IOException("XLSX contains no xl/worksheets/sheet1.xml");
        }
        List<String> shared = sharedXml == null ? new ArrayList<>() : parseSharedStrings(sharedXml);
        return parseSheet(sheetXml, shared);
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.IS_COALESCING, true);
        // Harden against XML external-entity attacks; XLSX parts never need them.
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
        return f;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** Collects the text of every {@code <si>} (shared-string item), concatenating its {@code <t>} runs. */
    private static List<String> parseSharedStrings(byte[] xml) throws Exception {
        List<String> result = new ArrayList<>();
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder current = null;
        boolean inText = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("si".equals(ln)) {
                    current = new StringBuilder();
                } else if ("t".equals(ln)) {
                    inText = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inText && current != null) {
                current.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if ("t".equals(ln)) {
                    inText = false;
                } else if ("si".equals(ln)) {
                    result.add(current == null ? "" : current.toString());
                    current = null;
                }
            }
        }
        r.close();
        return result;
    }

    private static List<String[]> parseSheet(byte[] xml, List<String> shared) throws Exception {
        List<String[]> rows = new ArrayList<>();
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));

        List<String> rowValues = null;
        List<Integer> rowCols = null;
        String cellRef = null;
        String cellType = null;
        StringBuilder value = null;
        boolean inValue = false;

        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if ("row".equals(ln)) {
                    rowValues = new ArrayList<>();
                    rowCols = new ArrayList<>();
                } else if ("c".equals(ln)) {
                    cellRef = r.getAttributeValue(null, "r");
                    cellType = r.getAttributeValue(null, "t");
                    value = new StringBuilder();
                } else if ("v".equals(ln) || "t".equals(ln)) {
                    // <v> for numbers/shared-string indices, <t> for inline strings
                    inValue = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inValue && value != null) {
                value.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if ("v".equals(ln) || "t".equals(ln)) {
                    inValue = false;
                } else if ("c".equals(ln) && rowValues != null) {
                    String raw = value == null ? "" : value.toString();
                    String resolved;
                    if ("s".equals(cellType)) {
                        int idx = raw.trim().isEmpty() ? -1 : Integer.parseInt(raw.trim());
                        resolved = (idx >= 0 && idx < shared.size()) ? shared.get(idx) : "";
                    } else {
                        resolved = raw;
                    }
                    rowValues.add(resolved);
                    rowCols.add(columnIndex(cellRef));
                    cellRef = null;
                    cellType = null;
                    value = null;
                } else if ("row".equals(ln) && rowValues != null) {
                    int maxCol = -1;
                    for (int c : rowCols) {
                        maxCol = Math.max(maxCol, c);
                    }
                    String[] arr = new String[maxCol + 1];
                    Arrays.fill(arr, "");
                    for (int i = 0; i < rowValues.size(); i++) {
                        int c = rowCols.get(i);
                        if (c >= 0 && c < arr.length) {
                            arr[c] = rowValues.get(i);
                        }
                    }
                    rows.add(arr);
                    rowValues = null;
                    rowCols = null;
                }
            }
        }
        r.close();
        return rows;
    }

    /** Converts the letter part of a cell reference ("D2" → 3, zero-based) to a column index. */
    private static int columnIndex(String cellRef) {
        if (cellRef == null) {
            return -1;
        }
        int col = 0;
        for (int i = 0; i < cellRef.length(); i++) {
            char ch = cellRef.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                col = col * 26 + (ch - 'A' + 1);
            } else if (ch >= 'a' && ch <= 'z') {
                col = col * 26 + (ch - 'a' + 1);
            } else {
                break;
            }
        }
        return col - 1;
    }
}
