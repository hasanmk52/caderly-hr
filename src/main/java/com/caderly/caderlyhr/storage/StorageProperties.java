package com.caderly.caderlyhr.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * First {@code @ConfigurationProperties} class in this codebase (every prior tunable is a plain
 * {@code @Value}) — justified here by three related keys plus {@link DataSize} binding, which
 * {@code @Value} cannot parse on its own. See ADR 0012.
 */
@ConfigurationProperties(prefix = "caderly.storage")
public record StorageProperties(String backend, Local local, DataSize maxFileSize) {

    public record Local(String root) {}
}
