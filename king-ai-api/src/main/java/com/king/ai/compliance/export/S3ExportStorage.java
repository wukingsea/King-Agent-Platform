package com.king.ai.compliance.export;

import com.king.ai.config.KingAiProperties;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.UploadObjectArgs;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;

/**
 * S3 兼容对象存储（MinIO）。对象键形如 {@code <prefix>/<spaceId>/<uuid>.<ext>}，
 * 所有读写操作都被约束在调用方空间的前缀之下。
 */
@Component
@ConditionalOnProperty(name = "king-ai.compliance.export.storage", havingValue = "s3")
public class S3ExportStorage implements ExportStorage {

    private final MinioClient client;
    private final String bucket;
    private final String prefix;

    public S3ExportStorage(KingAiProperties properties) {
        this(buildClient(properties),
                properties.getCompliance().getExport().getS3().getBucket(),
                properties.getCompliance().getExport().getS3().getPrefix());
    }

    S3ExportStorage(MinioClient client, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("未配置导出对象存储 bucket");
        }
        this.client = client;
        this.bucket = bucket;
        this.prefix = normalizePrefix(prefix);
    }

    @Override
    public StoredFile store(Long spaceId, String extension, IOConsumer<OutputStream> writer) throws IOException {
        String key = objectKey(spaceId, extension);
        String ext = sanitize(extension);
        Path temp = Files.createTempFile("king-export-", ext.isEmpty() ? ".tmp" : "." + ext);
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.accept(out);
            }
            long size = Files.size(temp);
            upload(temp, key);
            return new StoredFile(key, size);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Never mask the original failure with a cleanup failure.
            }
        }
    }

    @Override
    public InputStream open(Long spaceId, String relativePath) throws IOException {
        String key = requireObjectKey(spaceId, relativePath);
        try {
            return client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) {
            throw asIOException("导出文件读取失败", ex);
        }
    }

    @Override
    public long size(Long spaceId, String relativePath) throws IOException {
        String key = requireObjectKey(spaceId, relativePath);
        try {
            return client.statObject(StatObjectArgs.builder().bucket(bucket).object(key).build()).size();
        } catch (Exception ex) {
            throw asIOException("导出文件信息读取失败", ex);
        }
    }

    @Override
    public void delete(Long spaceId, String relativePath) throws IOException {
        String key = requireObjectKey(spaceId, relativePath);
        try {
            client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
        } catch (Exception ex) {
            throw asIOException("导出文件删除失败", ex);
        }
    }

    private void upload(Path temp, String key) throws IOException {
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(bucket)
                    .object(key)
                    .filename(temp.toString())
                    .build());
        } catch (Exception ex) {
            throw asIOException("导出文件上传失败", ex);
        }
    }

    /**
     * Validates the space id and builds the key of a fresh object for that space.
     */
    private String objectKey(Long spaceId, String extension) {
        String ext = sanitize(extension);
        String name = UUID.randomUUID() + (ext.isEmpty() ? "" : "." + ext);
        return spacePrefix(spaceId) + name;
    }

    /**
     * Resolves a caller-supplied key and rejects anything outside the space prefix.
     */
    private String requireObjectKey(Long spaceId, String relativePath) {
        String spacePrefix = spacePrefix(spaceId);
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("导出文件路径为空");
        }
        String key = relativePath.replace('\\', '/');
        if (!key.startsWith(spacePrefix) || key.length() == spacePrefix.length() || escapes(key)) {
            throw new IllegalArgumentException("非法导出文件路径");
        }
        return key;
    }

    private String spacePrefix(Long spaceId) {
        if (spaceId == null || spaceId <= 0) {
            throw new IllegalArgumentException("非法空间 ID");
        }
        return prefix.isEmpty() ? spaceId + "/" : prefix + "/" + spaceId + "/";
    }

    private static boolean escapes(String key) {
        for (String segment : key.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }
        String trimmed = prefix.replace('\\', '/');
        int start = 0;
        int end = trimmed.length();
        while (start < end && trimmed.charAt(start) == '/') {
            start++;
        }
        while (end > start && trimmed.charAt(end - 1) == '/') {
            end--;
        }
        return trimmed.substring(start, end);
    }

    private static String sanitize(String extension) {
        if (extension == null) {
            return "";
        }
        return extension.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static MinioClient buildClient(KingAiProperties properties) {
        KingAiProperties.S3 s3 = properties.getCompliance().getExport().getS3();
        if (s3.getEndpoint() == null || s3.getEndpoint().isBlank()) {
            throw new IllegalStateException("未配置导出对象存储 endpoint");
        }
        if (s3.getAccessKey() == null || s3.getAccessKey().isBlank()
                || s3.getSecretKey() == null || s3.getSecretKey().isBlank()) {
            throw new IllegalStateException("未配置导出对象存储凭据");
        }
        MinioClient.Builder builder = MinioClient.builder()
                .endpoint(s3.getEndpoint())
                .credentials(s3.getAccessKey(), s3.getSecretKey());
        if (s3.getRegion() != null && !s3.getRegion().isBlank()) {
            builder.region(s3.getRegion());
        }
        return builder.build();
    }

    /**
     * Keeps storage-layer failures free of credentials; only the exception type is kept in the message.
     */
    private static IOException asIOException(String action, Exception ex) {
        if (ex instanceof IOException io) {
            return io;
        }
        return new IOException(action + "（" + ex.getClass().getSimpleName() + "）", ex);
    }
}
