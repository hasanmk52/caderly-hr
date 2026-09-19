package com.caderly.caderlyhr.reports;

import static org.assertj.core.api.Assertions.assertThat;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Round-trips through opencsv's own reader rather than eyeballing raw bytes, to catch
 * encoding/quoting regressions (Phase 1.12 DoD: "opens cleanly in Excel").
 */
class ReportCsvWriterTest {

    @Test
    void write_prependsUtf8BomForExcelCompatibility() {
        byte[] csv = ReportCsvWriter.write(new String[] {"A"}, List.<String[]>of(new String[] {"1"}));

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    void write_roundTripsHeaderAndRowsThroughOpencsvReader() throws IOException, CsvException {
        String[] headers = {"Employee", "Department", "Granted"};
        List<String[]> rows =
                List.of(
                        new String[] {"Jane Doe", "Engineering", "20"},
                        new String[] {"Comma, Name", "Sales, EMEA", "15.50"});

        byte[] csv = ReportCsvWriter.write(headers, rows);

        try (CSVReader reader =
                new CSVReader(
                        new InputStreamReader(
                                // Skip the 3-byte BOM before handing the stream to opencsv, exactly
                                // as a real CSV consumer (Excel included) does.
                                new ByteArrayInputStream(csv, 3, csv.length - 3), StandardCharsets.UTF_8))) {
            List<String[]> parsed = reader.readAll();
            assertThat(parsed).hasSize(3);
            assertThat(parsed.get(0)).containsExactly("Employee", "Department", "Granted");
            assertThat(parsed.get(1)).containsExactly("Jane Doe", "Engineering", "20");
            // A comma inside a value must survive the round trip as one field, not split in two —
            // proof opencsv is doing real quoting/escaping, the whole reason this phase pulled it
            // in instead of hand-rolling (ADR 0018 vs. PublicHolidayService's hand-rolled parser).
            assertThat(parsed.get(2)).containsExactly("Comma, Name", "Sales, EMEA", "15.50");
        }
    }
}
