package com.caderly.caderlyhr.storage;

import com.caderly.caderlyhr.common.NotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Writes bytes under a configured root directory. Keys are always server-generated ({@code
 * {tenantId}/{uuid}}, never derived from a user-supplied filename — see {@code
 * documents.UploadValidator} and the callers that build keys) but {@link #resolve} still checks
 * the shape and re-verifies the resolved path stays under the root, as defense in depth against a
 * future caller reintroducing path traversal.
 */
@Component
@ConditionalOnProperty(name = "caderly.storage.backend", havingValue = "local", matchIfMissing = true)
class LocalFileStorage implements FileStorage {

    private static final Pattern KEY_PATTERN = Pattern.compile("[A-Za-z0-9-]+/[A-Za-z0-9-]+");

    private final Path root;

    LocalFileStorage(StorageProperties properties) {
        this.root = Path.of(properties.local().root()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not create storage root " + root, e);
        }
    }

    @Override
    public void store(String key, InputStream content, long size, String contentType) {
        Path target = resolve(key);
        try {
            Files.createDirectories(target.getParent());
            Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store file at " + key, e);
        }
    }

    @Override
    public InputStream open(String key) {
        Path target = resolve(key);
        try {
            return Files.newInputStream(target);
        } catch (NoSuchFileException e) {
            throw new NotFoundException("FILE_NOT_FOUND", "File not found");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read file at " + key, e);
        }
    }

    @Override
    public void delete(String key) {
        Path target = resolve(key);
        try {
            Files.deleteIfExists(target);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not delete file at " + key, e);
        }
    }

    private Path resolve(String key) {
        if (!KEY_PATTERN.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        Path target = root.resolve(key).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("Invalid storage key: " + key);
        }
        return target;
    }
}
