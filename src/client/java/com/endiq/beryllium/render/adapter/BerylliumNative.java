package com.endiq.beryllium.render.adapter;

import com.endiq.beryllium.render.snapshot.MeshRevision;
import com.endiq.beryllium.render.snapshot.SectionSnapshot;
import com.endiq.beryllium.util.BerylliumLog;

/**
 * The only place in Beryllium that talks to the native engine.
 *
 * <p>Everything about the boundary is concentrated here on purpose. Loading a native library
 * is the single most platform-specific thing a Java mod does — it fails differently on a
 * desktop JVM, on an Android launcher's JVM, and on one where the library was simply not
 * packaged — so it gets one class, one attempt, and one recorded outcome. Nothing else in the
 * mod calls {@code System.loadLibrary}, and nothing else declares a {@code native} method.
 *
 * <h2>Current status</h2>
 *
 * <p>The library is not built or packaged yet, so {@link #isReady()} is false on every
 * install and every method below is unreachable in practice. That is the intended state:
 * the snapshot contract on the engine side exists and is tested in C, but there is no JNI
 * layer, no mesher that understands real Minecraft models, and no Android GLES backend. The
 * seam is here so those can be added without the rest of the mod ever learning about them.
 *
 * <p>Every entry point is guarded by {@link #isReady()} and returns a value that means
 * "nothing happened" rather than throwing. A missing native library must cost a feature, not
 * a crash — and on a launcher where {@code loadLibrary} itself can abort the JVM, the attempt
 * is made once, as early as possible, and never retried.
 */
public final class BerylliumNative {
	/** Name passed to {@code System.loadLibrary}, i.e. {@code libberyllium_engine.so}. */
	private static final String LIBRARY_NAME = "beryllium_engine";

	private static volatile boolean attempted = false;
	private static volatile boolean loaded = false;
	private static volatile String loadFailure = null;

	private BerylliumNative() {
	}

	/**
	 * Loads the native library, once. Safe to call from anywhere and as often as anything
	 * likes; only the first call does work.
	 */
	public static synchronized void initialize() {
		if (attempted) {
			return;
		}
		attempted = true;
		try {
			System.loadLibrary(LIBRARY_NAME);
			loaded = true;
			BerylliumLog.info("[BERYLLIUM] native engine library loaded");
		} catch (Throwable t) {
			/* UnsatisfiedLinkError is the expected case on every install today. Anything
			 * else — a SecurityException from a launcher that forbids native code, a
			 * missing dependency inside the library — is recorded the same way, because the
			 * response is the same: stay on vanilla. */
			loaded = false;
			loadFailure = t.getClass().getSimpleName() + ": " + t.getMessage();
			BerylliumLog.debug("[BERYLLIUM] native engine library unavailable (" + loadFailure
				+ "); the custom terrain renderer stays off");
		}
	}

	/**
	 * Whether the native engine can be used.
	 *
	 * <p>Loading the library is necessary but not sufficient: the engine also has to report
	 * that it can actually render on this device, which is what the capability probe below is
	 * for. A library that loads onto a device with no usable GLES 3.0 context is not a
	 * renderer.
	 */
	public static boolean isReady() {
		if (!attempted) {
			initialize();
		}
		if (!loaded) {
			return false;
		}
		try {
			return nativeProbe() == 0;
		} catch (Throwable t) {
			// The library is present but its entry points are not what this build expects:
			// a version mismatch between the jar and the .so. Stay on vanilla.
			loadFailure = "native probe failed: " + t;
			loaded = false;
			return false;
		}
	}

	public static boolean isLoaded() {
		return loaded;
	}

	/** Why the library is unavailable, for the debug overlay. Null when it loaded. */
	public static String loadFailure() {
		return loadFailure;
	}

	/**
	 * Hands one immutable section snapshot to the engine.
	 *
	 * <p>The bytes are the encoding defined in {@code engine/src/snapshot.h}. Crossing the
	 * boundary as bytes rather than as live objects is what makes the rule enforceable: a
	 * worker on the other side physically cannot reach a Minecraft object, because none was
	 * given to it.
	 *
	 * @return true if the engine accepted the snapshot for building
	 */
	public static boolean submitSnapshot(SectionSnapshot snapshot) {
		if (snapshot == null || !isReady()) {
			return false;
		}
		try {
			byte[] encoded = snapshot.encode();
			return nativeSubmitSnapshot(encoded, encoded.length) == 0;
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] snapshot submission failed: " + t);
			return false;
		}
	}

	/**
	 * Collects a finished mesh, if one is ready.
	 *
	 * <p>The engine returns the mesh bytes plus the revision they were built from; the caller
	 * decides whether to accept them. Nothing here judges staleness, because the render thread
	 * is the only place that knows what the current revision is.
	 *
	 * @return the payload, or null when no build has finished
	 */
	public static byte[] pollMesh() {
		if (!isReady()) {
			return null;
		}
		try {
			return nativePollMesh();
		} catch (Throwable t) {
			return null;
		}
	}

	/**
	 * Asks the engine which revision the most recent mesh was built from.
	 *
	 * @return the revision, or null when there is no result pending
	 */
	public static MeshRevision pollMeshRevision() {
		if (!isReady()) {
			return null;
		}
		try {
			long[] parts = nativePollRevision();
			if (parts == null || parts.length < 4) {
				return null;
			}
			return new MeshRevision(parts[0], parts[1], parts[2], parts[3]);
		} catch (Throwable t) {
			return null;
		}
	}

	/** Releases everything the engine holds. Called on client shutdown and world change. */
	public static void shutdown() {
		if (!loaded) {
			return;
		}
		try {
			nativeShutdown();
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] native shutdown failed: " + t);
		}
	}

	/* ------------------------------------------------------------- native --- */

	/** @return 0 when the engine can render on this device, non-zero otherwise */
	private static native int nativeProbe();

	/** @return 0 when the snapshot was accepted */
	private static native int nativeSubmitSnapshot(byte[] encoded, int length);

	private static native byte[] nativePollMesh();

	/** worldId, sectionKey, sectionRevision, packGeneration — or null */
	private static native long[] nativePollRevision();

	private static native void nativeShutdown();
}
