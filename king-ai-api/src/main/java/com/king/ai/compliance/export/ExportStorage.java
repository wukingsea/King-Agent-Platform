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

    /**
     * Persists a new file owned by the given space.
     *
     * <p>Contract: the {@code writer} callback MUST close or flush the sink before returning.
     * The storage closes the raw stream afterwards only, so an unflushed buffered sink
     * silently loses its tail.</p>
     *
     * @param spaceId   owning space; must be non-null and positive
     * @param extension requested file extension; normalized by the implementation
     * @param writer    writes the content and must close or flush its sink before returning
     * @return the file location relative to the storage root, plus its size
     * @throws IOException              if writing or moving the file fails
     * @throws IllegalArgumentException if {@code spaceId} is {@code null} or not positive
     */
    StoredFile store(Long spaceId, String extension, IOConsumer<OutputStream> writer) throws IOException;

    InputStream open(Long spaceId, String relativePath) throws IOException;

    long size(Long spaceId, String relativePath) throws IOException;

    void delete(Long spaceId, String relativePath) throws IOException;
}
