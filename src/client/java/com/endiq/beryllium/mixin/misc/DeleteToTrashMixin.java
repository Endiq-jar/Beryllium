package com.endiq.beryllium.mixin.misc;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.misc.DeleteToTrash;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/**
 * Sends a deleted world to the trash instead of erasing it.
 *
 * <p>The game asks the world storage to delete a level after the player has confirmed it, which
 * is the last moment the world still exists. Following the same rule as a file manager means the
 * confirmation stays — the player still has to say yes — and the world is still there afterwards,
 * in the trash, recoverable by moving it back ({@link DeleteToTrash} spells out where it goes on
 * each platform).
 *
 * <p>The world's own directory is found by looking for the field that holds it rather than by
 * naming its type, because that field is private and has been renamed at least once inside the
 * supported range. When it cannot be found, the deletion goes ahead the way vanilla does it —
 * better to delete than to leave a world the player asked to remove.
 */
@Mixin(targets = "net.minecraft.world.level.storage.LevelStorageSource$LevelStorageAccess")
public abstract class DeleteToTrashMixin {
	@Inject(method = "deleteLevel", at = @At("HEAD"), cancellable = true, require = 0)
	private void beryllium$trashInsteadOfDelete(CallbackInfo ci) {
		try {
			BerylliumConfig config = Beryllium.config();
			if (config == null || !config.enabled || !config.deleteToTrash) {
				return;
			}
			Path directory = beryllium$levelDirectory();
			if (directory != null && DeleteToTrash.moveToTrash(directory)) {
				ci.cancel();
			}
		} catch (Throwable t) {
			// Vanilla's deletion runs.
		}
	}

	@Unique
	private Path beryllium$levelDirectory() {
		for (String field : new String[] { "levelDirectory", "levelPath", "levelDir", "directory", "path" }) {
			Object value = Reflect.get(this, field);
			if (value instanceof Path path) {
				return path;
			}
		}
		return null;
	}
}
