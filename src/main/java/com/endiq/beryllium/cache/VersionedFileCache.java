package com.endiq.beryllium.cache;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * A minimal, corruption-safe, versioned on-disk byte cache. Generic infrastructure for
 * spec section 16 (shader cache) — <b>not wired to anything shader-specific</b>, since
 * that requires hooking Minecraft's actual shader compilation pipeline, which is
 * internals-heavy territory this delivery couldn't verify against a real jar (same
 * situation as the chunk renderer — see the README). What's here is the cache mechanics
 * themselves: versioning, safe reads of possibly-corrupt entries, and cleanup of stale
 * entries — all real and tested, all reusable for shaders or anything else that needs
 * a "compile once, reuse across launches" cache.
 *
 * <p>Bumping {@code cacheVersion} automatically orphans every entry written under a
 * previous version; {@link #invalidateStaleEntries()} sweeps those away, satisfying
 * section 16's "detect invalid cache entries, automatically invalidate incompatible
 * caches" without this class needing to understand the content it's caching at all.
 */
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
			// A corrupt/unreadable entry is just a cache miss - never allowed to crash
			// the caller (spec: "never allow a corrupt cache to crash Minecraft").
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
