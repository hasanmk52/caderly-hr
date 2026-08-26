package com.caderly.caderlyhr.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

class LocalFileStorageTest extends FileStorageContractTest {

    @TempDir Path tempDir;

    @Override
    protected FileStorage storage() {
        return new LocalFileStorage(
                new StorageProperties("local", new StorageProperties.Local(tempDir.toString()), DataSize.ofMegabytes(25)));
    }

    @Test
    void store_writesUnderTheGivenKeyBelowTheRoot() {
        FileStorage storage = storage();
        String tenantId = "11111111-1111-1111-1111-111111111111";
        String fileId = "22222222-2222-2222-2222-222222222222";
        storage.store(
                tenantId + "/" + fileId,
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)),
                1,
                "text/plain");

        assertThat(tempDir.resolve(tenantId).resolve(fileId)).exists();
    }

    @Test
    void open_keyContainingParentDirTraversal_isRejected() {
        assertThatThrownBy(() -> storage().open("11111111-1111-1111-1111-111111111111/../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void open_absolutePathKey_isRejected() {
        assertThatThrownBy(() -> storage().open("/etc/passwd")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void open_keyWithExtraPathSegments_isRejected() {
        assertThatThrownBy(
                        () ->
                                storage()
                                        .open(
                                                "11111111-1111-1111-1111-111111111111/sub/22222222-2222-2222-2222-222222222222"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
