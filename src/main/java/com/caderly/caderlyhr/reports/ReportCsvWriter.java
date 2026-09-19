package com.caderly.caderlyhr.reports;

import com.opencsv.CSVWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Serializes a report's header row and data rows to CSV bytes (Phase 1.12). One shared writer for
 * all three reports rather than one per report — the serialization step is identical regardless
 * of what the columns mean.
 */
public final class ReportCsvWriter {

    /**
     * UTF-8 BOM: without it, Excel guesses the file's encoding from its own locale rather than
     * reading it as UTF-8, and any accented character in an employee's name renders as mojibake.
     * Every other consumer of a UTF-8 CSV (including Excel itself) tolerates a leading BOM.
     */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private ReportCsvWriter() {}

    public static byte[] write(String[] headers, List<String[]> rows) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        buffer.writeBytes(UTF8_BOM);
        try (CSVWriter writer = new CSVWriter(new OutputStreamWriter(buffer, StandardCharsets.UTF_8))) {
            writer.writeNext(headers);
            for (String[] row : rows) {
                writer.writeNext(row);
            }
        } catch (IOException e) {
            // CSVWriter only declares IOException because it wraps a Writer; nothing here does
            // I/O that can actually fail (an in-memory ByteArrayOutputStream never does).
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }
}
