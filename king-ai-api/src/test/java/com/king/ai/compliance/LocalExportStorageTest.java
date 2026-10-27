package com.king.ai.compliance;

import com.king.ai.compliance.export.LocalExportStorage;
import com.king.ai.compliance.export.ExportStorage;
import com.king.ai.config.KingAiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalExportStorageTest {

    @TempDir
    Path root;

    private LocalExportStorage storage() {
        KingAiProperties properties = new KingAiProperties();
        properties.getCompliance().getExport().setRootDir(root.toString());
        return new LocalExportStorage(properties);
    }

    @Test
    void storesAndReadsFileUnderSpaceDirectory() throws Exception {
        LocalExportStorage storage = storage();

        ExportStorage.StoredFile file = storage.store(7L, "jsonl",
                out -> out.write("{\"a\":1}\n".getBytes(StandardCharsets.UTF_8)));

        assertThat(file.relativePath()).startsWith("7/").endsWith(".jsonl");
        assertThat(file.size()).isEqualTo(8);
        try (InputStream input = storage.open(7L, file.relativePath())) {
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("{\"a\":1}\n");
        }
        storage.delete(7L, file.relativePath());
        assertThatThrownBy(() -> storage.open(7L, file.relativePath()))
                .isInstanceOf(Exception.class);
    }

    @Test
    void rejectsPathTraversalAndCrossSpaceAccess() throws Exception {
        LocalExportStorage storage = storage();
        ExportStorage.StoredFile file = storage.store(7L, "csv", out -> out.write("x".getBytes()));

        assertThatThrownBy(() -> storage.open(7L, "../8/" + file.relativePath()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(8L, file.relativePath()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
