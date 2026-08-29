package com.endiq.beryllium.cache;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class VersionedFileCache {
	private final Path cacheDir;
	private final int cacheVersion;

	public VersionedFileCache(Path cacheDir, int cacheVersion) {
		this.cacheDir = cacheDir;
		this.cacheVersion = cacheVersion;
	}

	public Optional<byte[]> get(String key) {
		try {
			Path file = fileFor(key);
			if (!Files.isRegularFile(file)) {
				return Optional.empty();
			}
			return Optional.of(Files.readAllBytes(file));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	public boolean put(String key, byte[] data) {
		try {
			Files.createDirectories(cacheDir);
			Files.write(fileFor(key), data);
			return true;
		} catch (IOException e) {
			return false; // best-effort; a failed cache write must never be fatal
		}
	}

	/** Deletes every cache file NOT belonging to the current version. Call once at startup.
	 *  @return how many stale entries were removed. */
	public int invalidateStaleEntries() {
		int removed = 0;
		String currentPrefix = "v" + cacheVersion + "_";
		try {
			if (!Files.isDirectory(cacheDir)) {
				return 0;
			}
			try (DirectoryStream<Path> files = Files.newDirectoryStream(cacheDir, "*.cache")) {
				for (Path file : files) {
					String name = file.getFileName().toString();
					if (!name.startsWith(currentPrefix)) {
						try {
							Files.deleteIfExists(file);
							removed++;
						} catch (IOException ignored) {
							// Best-effort cleanup - leaving a stale file behind is harmless,
							// it will never be read again (different version prefix).
						}
					}
				}
			}
		} catch (IOException ignored) {
			return removed;
		}
		return removed;
	}

	private Path fileFor(String key) {
		return cacheDir.resolve("v" + cacheVersion + "_" + sanitize(key) + ".cache");
	}

	private static String sanitize(String key) {
		return key.replaceAll("[^a-zA-Z0-9_.-]", "_");
	}
}
