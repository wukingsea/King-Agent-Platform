package com.king.ai.compliance.export;

import java.io.IOException;

public interface ExportSink extends AutoCloseable {

    void row(java.util.Map<String, Object> values) throws IOException;

    long count();

    @Override
    void close() throws IOException;
}
