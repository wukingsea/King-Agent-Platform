package com.king.ai.compliance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.king.ai.compliance.export.ExportFormat;
import com.king.ai.compliance.export.ExportSink;
import com.king.ai.compliance.export.ExportSinkFactory;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ExportSinkTest {

    @Test
    void writesJsonLines() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.JSONL,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "你好"));
            sink.row(Map.of("id", 2, "text", "a\"b"));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text.lines().count()).isEqualTo(2);
        assertThat(text).contains("\"id\":1").contains("你好");
    }

    @Test
    void writesCsvHeaderAndEscapes() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.CSV,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "a,b"));
            sink.row(Map.of("id", 2, "text", "say \"hi\""));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("id,text\n");
        assertThat(text).contains("1,\"a,b\"");
        assertThat(text).contains("2,\"say \"\"hi\"\"\"");
    }

    @Test
    void neutralizesFormulaInjection() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.CSV,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "=HYPERLINK(\"http://evil\")"));
            sink.row(Map.of("id", 2, "text", "@SUM(A1)"));
            sink.row(Map.of("id", 3, "text", "+1"));
            sink.row(Map.of("id", 4, "text", "-1"));
            sink.row(Map.of("id", 5, "text", "\tleading-tab"));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).contains("1,\"'=HYPERLINK(\"\"http://evil\"\")\"");
        assertThat(text).contains("2,'@SUM(A1)");
        assertThat(text).contains("3,'+1");
        assertThat(text).contains("4,'-1");
        assertThat(text).contains("5,'\tleading-tab");
    }

    @Test
    void neutralizesCarriageReturnAndRendersEmptyCells() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.CSV,
                List.of("id", "text"), new ObjectMapper())) {
            sink.row(Map.of("id", 1, "text", "\r=cmd"));
            sink.row(Map.of("id", 2));
            sink.row(Map.of("id", 3, "text", ""));
        }

        String[] lines = out.toString(StandardCharsets.UTF_8).split("\n", -1);
        // CR-prefixed value: the neutralizer prepends "'", then RFC4180 quotes the CR cell.
        assertThat(lines[1]).isEqualTo("1,\"'\r=cmd\"");
        // Missing (null) value renders as an empty cell, with no stray "'" prefix.
        assertThat(lines[2]).isEqualTo("2,");
        // Empty string renders as an empty cell too.
        assertThat(lines[3]).isEqualTo("3,");
    }

    @Test
    void neutralizesFormulaInjectionInHeader() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ExportSink sink = ExportSinkFactory.open(out, ExportFormat.CSV,
                List.of("=cmd", "text"), new ObjectMapper())) {
            sink.row(Map.of("=cmd", "ok", "text", "value"));
        }

        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("'=cmd,text\n");
        assertThat(text).contains("ok,value");
    }
}
