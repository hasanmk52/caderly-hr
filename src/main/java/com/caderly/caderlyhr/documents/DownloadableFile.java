package com.caderly.caderlyhr.documents;

import java.io.InputStream;

/**
 * What a controller needs to stream a response: the metadata for headers plus the open content
 * stream. Keeps {@code web} talking only to {@code documents} for this feature — {@code
 * storage.FileStorage} itself is never imported outside this package.
 */
public record DownloadableFile(String filename, String mime, long size, InputStream content) {}
