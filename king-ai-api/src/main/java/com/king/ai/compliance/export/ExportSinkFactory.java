package com.king.ai.compliance.export;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public final class ExportSinkFactory {

    private ExportSinkFactory() {
    }

    public static ExportSink open(OutputStream out, ExportFormat format,
                                  List<String> headers, ObjectMapper mapper) throws IOException {
        return format == ExportFormat.CSV
                ? new CsvSink(out, headers)
                : new JsonlSink(out, mapper);
    }

    private static final class JsonlSink implements ExportSink {
        private final BufferedWriter writer;
        private final ObjectMapper mapper;
        private long count;

        private JsonlSink(OutputStream out, ObjectMapper mapper) {
            this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            this.mapper = mapper;
        }

        @Override
        public void row(Map<String, Object> values) throws IOException {
            writer.write(mapper.writeValueAsString(values));
            writer.write('\n');
            count++;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }
    }

    private static final class CsvSink implements ExportSink {
        private static final String FORMULA_PREFIXES = "=+-@\t\r";

        private final BufferedWriter writer;
        private final List<String> headers;
        private long count;

        private CsvSink(OutputStream out, List<String> headers) throws IOException {
            this.writer = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));
            this.headers = List.copyOf(headers);
            writer.write(String.join(",", headers.stream().map(CsvSink::cell).toList()));
            writer.write('\n');
        }

        @Override
        public void row(Map<String, Object> values) throws IOException {
            writer.write(String.join(",", headers.stream()
                    .map(header -> cell(stringify(values.get(header)))).toList()));
            writer.write('\n');
            count++;
        }

        @Override
        public long count() {
            return count;
        }

        @Override
        public void close() throws IOException {
            writer.flush();
        }

        private static String stringify(Object value) {
            return value == null ? "" : String.valueOf(value);
        }

        /**
         * Neutralizes spreadsheet formula injection before RFC4180 quoting:
         * a cell starting with =, +, -, @, TAB or CR is prefixed with a single quote.
         */
        private static String cell(String value) {
            return escape(neutralize(value));
        }

        private static String neutralize(String value) {
            if (!value.isEmpty() && FORMULA_PREFIXES.indexOf(value.charAt(0)) >= 0) {
                return "'" + value;
            }
            return value;
        }

        private static String escape(String value) {
            if (value.contains(",") || value.contains("\"") || value.contains("\n")
                    || value.contains("\r")) {
                return "\"" + value.replace("\"", "\"\"") + "\"";
            }
            return value;
        }
    }
}
