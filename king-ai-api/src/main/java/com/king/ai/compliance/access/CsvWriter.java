package com.king.ai.compliance.access;

import java.util.List;

public final class CsvWriter {

    private CsvWriter() {
    }

    public static String write(List<String> headers, List<List<String>> rows) {
        StringBuilder out = new StringBuilder();
        out.append(join(headers)).append('\n');
        for (List<String> row : rows) {
            out.append(join(row)).append('\n');
        }
        return out.toString();
    }

    private static String join(List<String> values) {
        return values.stream().map(CsvWriter::escape)
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static String escape(String value) {
        String text = value == null ? "" : value;
        // 防止 Excel/LibreOffice 把用户可控文本当作公式执行。
        if (!text.isEmpty() && "=+-@\t\r".indexOf(text.charAt(0)) >= 0) {
            text = "'" + text;
        }
        if (text.contains(",") || text.contains("\"") || text.contains("\n") || text.contains("\r")) {
            return "\"" + text.replace("\"", "\"\"") + "\"";
        }
        return text;
    }
}
