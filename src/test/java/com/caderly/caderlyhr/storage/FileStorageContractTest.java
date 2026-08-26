package com.caderly.caderlyhr.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.caderly.caderlyhr.common.NotFoundException;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Behaviour every {@link FileStorage} implementation must satisfy — a future {@code
 * S3FileStorageTest} extends this instead of re-deriving the same cases (CLAUDE.md §8).
 */
abstract class FileStorageContractTest {

    protected abstract FileStorage storage();

    @Test
    void store_thenOpen_returnsTheSameBytes() throws Exception {
        FileStorage storage = storage();
        byte[] content = "hello world".getBytes(StandardCharsets.UTF_8);
        String key = randomKey();

        storage.store(key, new ByteArrayInputStream(content), content.length, "text/plain");

        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void open_unknownKey_throwsNotFound() {
        assertThatThrownBy(() -> storage().open(randomKey())).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_removesTheFile() {
        FileStorage storage = storage();
        String key = randomKey();
        storage.store(key, new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)), 1, "text/plain");

        storage.delete(key);

        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void delete_unknownKey_isANoOp() {
        assertThatCode(() -> storage().delete(randomKey())).doesNotThrowAnyException();
    }

    @Test
    void store_overwritesAnExistingKey() throws Exception {
        FileStorage storage = storage();
        String key = randomKey();
        storage.store(key, new ByteArrayInputStream("first".getBytes(StandardCharsets.UTF_8)), 5, "text/plain");

        byte[] second = "second-version".getBytes(StandardCharsets.UTF_8);
        storage.store(key, new ByteArrayInputStream(second), second.length, "text/plain");

        try (InputStream in = storage.open(key)) {
            assertThat(in.readAllBytes()).isEqualTo(second);
        }
    }

    private static String randomKey() {
        return java.util.UUID.randomUUID() + "/" + java.util.UUID.randomUUID();
    }
}
