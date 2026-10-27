package com.king.ai.compliance.export;

import com.king.ai.config.KingAiProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Locale;
import java.util.UUID;

@Component
public class LocalExportStorage implements ExportStorage {

    private final Path root;

    public LocalExportStorage(KingAiProperties properties) {
        this.root = Paths.get(properties.getCompliance().getExport().getRootDir())
                .toAbsolutePath().normalize();
    }

    /**
     * {@inheritDoc}
     *
     * <p>Contract: the {@code writer} callback MUST close or flush the sink before returning.
     * The storage closes the raw {@link OutputStream} afterwards only, so an unflushed
     * buffered sink silently loses its tail.</p>
     *
     * @throws IllegalArgumentException if {@code spaceId} is {@code null} or not positive
     */
    @Override
    public StoredFile store(Long spaceId, String extension,
                            IOConsumer<OutputStream> writer) throws IOException {
        Path directory = spaceRoot(spaceId);
        ensureUnderRoot(directory);
        Files.createDirectories(directory);
        String ext = sanitize(extension);
        String name = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        Path target = directory.resolve(name).normalize();
        Path temp = Files.createTempFile(directory, "export-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                writer.accept(out);
            }
            moveIntoPlace(temp, target);
        } catch (IOException | RuntimeException ex) {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Never mask the original failure with a cleanup failure.
            }
            throw ex;
        }
        return new StoredFile(root.relativize(target).toString().replace('\\', '/'),
                Files.size(target));
    }

    @Override
    public InputStream open(Long spaceId, String relativePath) throws IOException {
        return Files.newInputStream(resolve(spaceId, relativePath));
    }

    @Override
    public long size(Long spaceId, String relativePath) throws IOException {
        return Files.size(resolve(spaceId, relativePath));
    }

    @Override
    public void delete(Long spaceId, String relativePath) throws IOException {
        Files.deleteIfExists(resolve(spaceId, relativePath));
    }

    private Path resolve(Long spaceId, String relativePath) {
        Path spaceRoot = spaceRoot(spaceId);
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("导出文件路径为空");
        }
        Path candidate = root.resolve(relativePath).normalize();
        if (!candidate.startsWith(spaceRoot) || candidate.equals(spaceRoot)) {
            throw new IllegalArgumentException("非法导出文件路径");
        }
        return candidate;
    }

    /**
     * Validates the space id and returns the normalized per-space directory.
     */
    private Path spaceRoot(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            throw new IllegalArgumentException("非法空间 ID");
        }
        return root.resolve(String.valueOf(spaceId)).normalize();
    }

    /**
     * Moves the temp file into place. Names are fresh UUIDs, so no replacement is expected;
     * file systems without atomic move support fall back to a plain move.
     */
    private static void moveIntoPlace(Path temp, Path target) throws IOException {
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(temp, target);
        }
    }

    private void ensureUnderRoot(Path path) {
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("非法导出目录");
        }
    }

    private static String sanitize(String extension) {
        if (extension == null) {
            return "";
        }
        return extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }
}
