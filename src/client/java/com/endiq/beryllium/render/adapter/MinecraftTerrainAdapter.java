package com.endiq.beryllium.render.adapter;

import com.endiq.beryllium.render.snapshot.MeshRevision;
import com.endiq.beryllium.render.snapshot.SectionSnapshot;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.util.Optional;

/**
 * The seam between Minecraft and the renderer.
 *
 * <p>Beryllium ships one jar across a wide range of Minecraft releases and has to work on
 * Android launchers whose graphics environment is nothing like a desktop's. If the code that
 * reads a section out of the world were tangled up with the code that meshes and draws it,
 * every release and every platform would mean editing the renderer. This interface is the
 * cut: everything version-specific and platform-specific lives behind it, and the renderer
 * only ever sees {@link SectionSnapshot} and {@link MeshRevision}, which do not change.
 *
 * <p>An adapter's whole job is to answer three questions about a world it understands and
 * the renderer does not:
 * <ul>
 *   <li>what does this section look like right now, as an immutable snapshot;</li>
 *   <li>what revision is it at, so a mesh built earlier can be recognised as stale;</li>
 *   <li>what generation is the resource pack at, since a reload invalidates every mesh in
 *       the world without moving a single voxel.</li>
 * </ul>
 *
 * <p>Implementations are expected to fail by returning {@code null} rather than by throwing.
 * A release whose internals have moved is an install where the custom renderer does not
 * engage, not an install that crashes.
 */
public interface MinecraftTerrainAdapter {
	/**
	 * A short description of the releases this adapter covers, for logging.
	 *
	 * <p>Worth having because the alternative is a silent wrong answer: when a future release
	 * moves the internals this adapter reflects over, the log line is what says which adapter
	 * gave up.
	 */
	String describes();

	/**
	 * Whether this adapter can operate on the running release.
	 *
	 * <p>Checked once at startup rather than per call, so an adapter that cannot work costs a
	 * single probe instead of a failed reflection lookup on every section of every frame.
	 */
	boolean isAvailable();

	/**
	 * Captures one section as an immutable snapshot.
	 *
	 * <p>Runs on whichever thread asks for it, and must not retain any reference to a live
	 * world object afterwards — the returned snapshot is what gets handed to a worker, and a
	 * snapshot that still pointed at the world would defeat the entire reason snapshots exist.
	 *
	 * @return the snapshot, or null if the section could not be read
	 */
	SectionSnapshot captureSection(int sectionX, int sectionY, int sectionZ);

	/**
	 * The current revision of one section, without capturing it.
	 *
	 * <p>Used to validate a finished mesh. Cheaper than a capture, and called far more often:
	 * every result that comes back from a worker is checked against this before it is
	 * uploaded.
	 */
	MeshRevision currentRevision(int sectionX, int sectionY, int sectionZ);

	/**
	 * The resource-pack generation.
	 *
	 * <p>Increments whenever packs are reloaded. Every mesh in the world is stale the moment
	 * it does, because block models and textures changed while no voxel moved — which is
	 * exactly the kind of invalidation nothing else in the pipeline would notice.
	 */
	long packGeneration();

	/** Identity of the level currently being rendered, stable for the life of that level. */
	long worldId();

	/**
	 * Picks the adapter for the running release.
	 *
	 * <p>There is one implementation today, and it is deliberately reflection-based rather
	 * than written against a specific release's classes: it degrades to "unavailable" on a
	 * release whose internals it cannot find, instead of failing to compile against one and
	 * shipping a jar that only works there. Version-specific adapters get added here as they
	 * are needed, most specific first.
	 */
	static MinecraftTerrainAdapter select() {
		MinecraftTerrainAdapter reflective = new ReflectiveTerrainAdapter();
		if (reflective.isAvailable()) {
			return reflective;
		}
		return Unavailable.INSTANCE;
	}

	/** The Minecraft version this jar is running against, or "unknown". */
	static String minecraftVersion() {
		try {
			Optional<ModContainer> container = FabricLoader.getInstance().getModContainer("minecraft");
			if (container.isPresent()) {
				return container.get().getMetadata().getVersion().getFriendlyString();
			}
		} catch (Throwable ignored) {
			// fall through
		}
		return "unknown";
	}

	/**
	 * The adapter used when none can operate on this release.
	 *
	 * <p>Every method returns the value that means "nothing to render", so a caller does not
	 * need a null check on the adapter itself. Returning a no-op rather than null is what lets
	 * {@link #select()} be called unconditionally at startup.
	 */
	final class Unavailable implements MinecraftTerrainAdapter {
		static final Unavailable INSTANCE = new Unavailable();

		private Unavailable() {
		}

		@Override
		public String describes() {
			return "no adapter available for Minecraft " + minecraftVersion();
		}

		@Override
		public boolean isAvailable() {
			return false;
		}

		@Override
		public SectionSnapshot captureSection(int sectionX, int sectionY, int sectionZ) {
			return null;
		}

		@Override
		public MeshRevision currentRevision(int sectionX, int sectionY, int sectionZ) {
			return null;
		}

		@Override
		public long packGeneration() {
			return 0L;
		}

		@Override
		public long worldId() {
			return 0L;
		}
	}
}
