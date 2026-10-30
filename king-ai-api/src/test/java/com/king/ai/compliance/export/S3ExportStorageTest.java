package com.king.ai.compliance.export;

import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.UploadObjectArgs;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class S3ExportStorageTest {

    private final MinioClient client = mock(MinioClient.class);
    private final S3ExportStorage storage = new S3ExportStorage(client, "king-exports", "exports");

    @Test
    void storeUploadsUnderTheSpacePrefixAndReturnsTheObjectKey() throws Exception {
        S3ExportStorage.StoredFile stored = storage.store(7L, "CSV",
                out -> out.write("a,b".getBytes()));

        ArgumentCaptor<UploadObjectArgs> captor = ArgumentCaptor.forClass(UploadObjectArgs.class);
        verify(client).uploadObject(captor.capture());
        UploadObjectArgs args = captor.getValue();
        assertThat(args.bucket()).isEqualTo("king-exports");
        assertThat(args.object()).startsWith("exports/7/").endsWith(".csv");
        assertThat(stored.relativePath()).isEqualTo(args.object());
        assertThat(stored.size()).isEqualTo(3);
    }

    @Test
    void openRejectsKeysOutsideTheSpacePrefix() {
        assertThatThrownBy(() -> storage.open(7L, "exports/8/other.csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(7L, "exports/7/../8/other.csv"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> storage.open(0L, "exports/7/other.csv"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void deleteUsesTheSpaceScopedObjectKey() throws Exception {
        storage.delete(7L, "exports/7/file.csv");

        ArgumentCaptor<RemoveObjectArgs> captor = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(client).removeObject(captor.capture());
        assertThat(captor.getValue().object()).isEqualTo("exports/7/file.csv");
    }

    @Test
    void sizeReadsRemoteObjectMetadata() throws Exception {
        StatObjectResponse response = mock(StatObjectResponse.class);
        when(response.size()).thenReturn(512L);
        when(client.statObject(any(StatObjectArgs.class))).thenReturn(response);

        assertThat(storage.size(7L, "exports/7/file.csv")).isEqualTo(512L);
    }

    @Test
    void openReturnsTheRemoteStream() throws Exception {
        GetObjectResponse response = mock(GetObjectResponse.class);
        when(client.getObject(any(GetObjectArgs.class))).thenReturn(response);
        when(response.read()).thenReturn(-1);

        assertThat(storage.open(7L, "exports/7/file.csv")).isSameAs(response);
    }

    @Test
    void storePropagatesWriterFailuresWithoutUploading() throws Exception {
        assertThatThrownBy(() -> storage.store(7L, "csv", out -> {
            throw new IOException("disk full");
        })).isInstanceOf(IOException.class).hasMessageContaining("disk full");

        verify(client, org.mockito.Mockito.never()).uploadObject(any(UploadObjectArgs.class));
    }

    @Test
    void blankBucketFailsFast() {
        assertThatThrownBy(() -> new S3ExportStorage(client, "", "exports"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("bucket");
    }
}
