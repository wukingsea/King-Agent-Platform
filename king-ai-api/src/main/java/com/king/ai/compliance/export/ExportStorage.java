package com.king.ai.compliance.export;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public interface ExportStorage {

    record StoredFile(String relativePath, long size) {
    }

    @FunctionalInterface
    interface IOConsumer<T> {
        void accept(T value) throws IOException;
    }

    StoredFile store(Long spaceId, String extension, IOConsumer<OutputStream> writer) throws IOException;

    InputStream open(Long spaceId, String relativePath) throws IOException;

    long size(Long spaceId, String relativePath) throws IOException;

    void delete(Long spaceId, String relativePath) throws IOException;
}
