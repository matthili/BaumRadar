package at.mafue.baumradar.dataprocessor.utils;

import org.junit.Test;
import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Tests for {@link XlsxReader}, the dependency-free XLSX parser. A minimal
 * workbook is assembled in memory to verify shared-string resolution, numeric
 * cells, and — crucially — column alignment when blank cells are omitted.
 */
public class XlsxReaderTest {

    @Test
    public void resolvesSharedStringsNumbersAndOmittedCells() throws Exception {
        String shared = "<?xml version=\"1.0\"?><sst>"
            + "<si><t>Alpha</t></si><si><t>Beta</t></si></sst>";
        // Header row skips column B; data row 2 has a number in A and a shared string in B.
        String sheet = "<?xml version=\"1.0\"?><worksheet><sheetData>"
            + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"C1\" t=\"s\"><v>1</v></c></row>"
            + "<row r=\"2\"><c r=\"A2\"><v>42</v></c><c r=\"B2\" t=\"s\"><v>0</v></c></row>"
            + "</sheetData></worksheet>";

        List<String[]> rows = XlsxReader.read(new ByteArrayInputStream(buildXlsx(shared, sheet)));

        assertEquals(2, rows.size());
        // Row 0: column B was omitted → must be an empty string to keep alignment.
        assertArrayEquals(new String[]{"Alpha", "", "Beta"}, rows.get(0));
        // Row 1: raw number kept as-is; shared string resolved.
        assertEquals("42", rows.get(1)[0]);
        assertEquals("Alpha", rows.get(1)[1]);
    }

    private static byte[] buildXlsx(String sharedStringsXml, String sheetXml) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            zos.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            zos.write(sharedStringsXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            zos.write(sheetXml.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        return bos.toByteArray();
    }
}
