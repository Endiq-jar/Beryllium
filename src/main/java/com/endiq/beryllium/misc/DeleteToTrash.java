package com.endiq.beryllium.misc;

import com.endiq.beryllium.util.BerylliumLog;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Moves a deleted world somewhere it can be recovered from, instead of erasing it.
 *
 * <p>Deleting a world is the one destructive thing the game offers with no undo, so the rule
 * here is that nothing is ever erased while any recoverable option is left:
 *
 * <ol>
 *   <li>the platform's own trash, written directly in the layout that platform uses — the
 *       freedesktop trash on Linux ({@code ~/.local/share/Trash}, with the {@code .trashinfo}
 *       entry that makes file managers offer "restore" on it) and {@code ~/.Trash} on
 *       macOS;</li>
 *   <li>a {@code .trash} folder beside the worlds — what most Android launchers get, and still
 *       a complete, restorable copy of the world, just renamed so the game stops listing it;</li>
 *   <li>nothing, and the caller is told, so it can leave the world alone rather than fall back
 *       to erasing it.</li>
 * </ol>
 *
 * <p>Deliberately not {@code java.awt.Desktop#moveToTrash}: that would initialise AWT inside a
 * GLFW game, which on macOS can take over the event loop and on some launchers hangs the
 * process at the point a player is deleting a world.
 */
public final class DeleteToTrash {
	private DeleteToTrash() {
	}

	/**
	 * @param levelDirectory the world folder itself
	 * @return true when the folder is out of the way and can be considered deleted
	 */
	public static boolean moveToTrash(Path levelDirectory) {
		if (levelDirectory == null || !Files.exists(levelDirectory)) {
			return false;
		}
		String name = levelDirectory.getFileName() == null ? "world" : levelDirectory.getFileName().toString();

		try {
			if (moveToPlatformTrash(levelDirectory, name)) {
				return true;
			}
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] Platform trash unavailable: " + t);
		}

		try {
			return moveToSiblingTrash(levelDirectory, name);
		} catch (Throwable t) {
			BerylliumLog.warn("[BERYLLIUM] Could not move " + name + " to trash (" + t
					+ "); leaving it alone rather than deleting it.");
			return false;
		}
	}

	private static boolean moveToPlatformTrash(Path levelDirectory, String name) throws Exception {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (os.contains("win")) {
			// The Windows recycle bin needs a shell API call that pure Java cannot make.
			return false;
		}

		Path trashRoot;
		boolean freedesktop = false;
		if (os.contains("mac")) {
			trashRoot = Path.of(System.getProperty("user.home", "."), ".Trash");
		} else {
			String dataHome = System.getenv("XDG_DATA_HOME");
			Path base = dataHome != null && !dataHome.isBlank()
					? Path.of(dataHome)
					: Path.of(System.getProperty("user.home", "."), ".local", "share");
			trashRoot = base.resolve("Trash");
			freedesktop = true;
		}

		Path files = trashRoot.resolve("files");
		Files.createDirectories(files);

		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		Path target = files.resolve(name + "-" + stamp);
		for (int suffix = 2; Files.exists(target) && suffix < 1000; suffix++) {
			target = files.resolve(name + "-" + stamp + "-" + suffix);
		}

		move(levelDirectory, target);

		if (freedesktop) {
			try {
				Path info = trashRoot.resolve("info");
				Files.createDirectories(info);
				String entry = "[Trash Info]\nPath=" + levelDirectory.toAbsolutePath() + "\n"
						+ "DeletionDate=" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format(new Date()) + "\n";
				Files.writeString(info.resolve(target.getFileName() + ".trashinfo"), entry);
			} catch (Throwable t) {
				// The folder is already in the trash; the restore hint is a nicety.
				BerylliumLog.debug("[BERYLLIUM] Could not write the trash info entry: " + t);
			}
		}

		BerylliumLog.info("[BERYLLIUM] Moved " + name + " to " + trashRoot
				+ " instead of deleting it.");
		return true;
	}

	private static boolean moveToSiblingTrash(Path levelDirectory, String name) throws Exception {
		Path parent = levelDirectory.getParent();
		if (parent == null) {
			return false;
		}
		Path trash = parent.resolve(".trash");
		Files.createDirectories(trash);

		String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss").format(new Date());
		Path target = trash.resolve(name + "-" + stamp);
		for (int suffix = 2; Files.exists(target) && suffix < 1000; suffix++) {
			target = trash.resolve(name + "-" + stamp + "-" + suffix);
		}

		move(levelDirectory, target);
		BerylliumLog.info("[BERYLLIUM] Moved " + name + " to " + trash
				+ " instead of deleting it; it can be restored by moving it back.");
		return true;
	}

	private static void move(Path from, Path to) throws Exception {
		try {
			Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception atomicUnsupported) {
			Files.move(from, to);
		}
	}
}
