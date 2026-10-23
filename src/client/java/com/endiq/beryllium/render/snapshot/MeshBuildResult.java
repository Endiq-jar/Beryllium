package com.endiq.beryllium.render.snapshot;

/**
 * A finished mesh, together with everything needed to decide whether it may still be used.
 *
 * <p>The result of a build is not accepted on arrival. It is checked against the inputs as
 * they are <em>now</em>, because a build takes time and three things can move during it: the
 * level, the section's contents, and the resource pack. A mesh describing a state that is no
 * longer current would be drawn over geometry that is, which is a visible artefact with no
 * error behind it — the worst kind of bug to chase in a renderer.
 *
 * <p>So the render thread calls {@link #isAcceptable(MeshRevision)} and uploads only what
 * comes back true. Rejecting is always safe: the section is still dirty and will be rebuilt
 * from current inputs. The cost of a rejection is one wasted build, and the alternative is a
 * frame that does not match the world.
 *
 * <p>The payload is deliberately opaque — a byte array with no interpretation at this layer.
 * What is inside it is the engine's business, and keeping this class ignorant of it is what
 * lets the same result type carry a software mesh, a GPU buffer handle, or nothing at all
 * when a section turned out to be empty.
 */
public final class MeshBuildResult {
	/** Outcome of {@link #isAcceptable(MeshRevision)}, for logging and telemetry. */
	public enum Status {
		/** The inputs have not moved; upload and draw it. */
		ACCEPTED,
		/** The build produced nothing, because the section was empty. Not an error. */
		EMPTY,
		/** The build itself failed. The section stays dirty and is retried. */
		FAILED,
		/** The level the mesh was built from is not the one being rendered. */
		STALE_WORLD,
		/** The section's contents changed while the mesh was being built. */
		STALE_SECTION,
		/** A resource pack was reloaded while the mesh was being built. */
		STALE_PACK
	}

	private final MeshRevision builtFrom;
	private final byte[] payload;
	private final int vertexCount;
	private final int indexCount;
	private final long buildNanos;
	private final String failure;

	private MeshBuildResult(MeshRevision builtFrom, byte[] payload, int vertexCount,
	                        int indexCount, long buildNanos, String failure) {
		this.builtFrom = builtFrom;
		this.payload = payload;
		this.vertexCount = vertexCount;
		this.indexCount = indexCount;
		this.buildNanos = buildNanos;
		this.failure = failure;
	}

	/** A mesh that may be uploaded. */
	public static MeshBuildResult of(MeshRevision builtFrom, byte[] payload,
	                                 int vertexCount, int indexCount, long buildNanos) {
		if (builtFrom == null) {
			throw new IllegalArgumentException("a mesh result must say what it was built from");
		}
		return new MeshBuildResult(builtFrom, payload == null ? new byte[0] : payload,
			vertexCount, indexCount, buildNanos, null);
	}

	/** A section that turned out to hold nothing. Valid, and cheap: no buffer is allocated. */
	public static MeshBuildResult empty(MeshRevision builtFrom, long buildNanos) {
		if (builtFrom == null) {
			throw new IllegalArgumentException("a mesh result must say what it was built from");
		}
		return new MeshBuildResult(builtFrom, new byte[0], 0, 0, buildNanos, null);
	}

	/** A build that did not produce a mesh. The section stays dirty and is retried. */
	public static MeshBuildResult failed(MeshRevision builtFrom, String reason) {
		if (builtFrom == null) {
			throw new IllegalArgumentException("a mesh result must say what it was built from");
		}
		return new MeshBuildResult(builtFrom, new byte[0], 0, 0, 0L,
			reason == null ? "unknown" : reason);
	}

	public MeshRevision builtFrom() {
		return builtFrom;
	}

	/** The mesh bytes, opaque at this layer. Empty for a failed or empty result. */
	public byte[] payload() {
		return payload.clone();
	}

	public int payloadLength() {
		return payload.length;
	}

	public int vertexCount() {
		return vertexCount;
	}

	public int indexCount() {
		return indexCount;
	}

	public long buildNanos() {
		return buildNanos;
	}

	public boolean isFailed() {
		return failure != null;
	}

	public String failureReason() {
		return failure;
	}

	public boolean isEmpty() {
		return failure == null && vertexCount == 0 && payload.length == 0;
	}

	/**
	 * Whether this result may be uploaded, given the inputs as they are now.
	 *
	 * @param now the current revision of the same section
	 */
	public boolean isAcceptable(MeshRevision now) {
		return status(now) == Status.ACCEPTED || status(now) == Status.EMPTY;
	}

	/**
	 * Why this result is or is not acceptable.
	 *
	 * <p>The order of the checks is deliberate: a failed build is reported as failed rather
	 * than as stale, because the two call for different responses — a failure is retried, and
	 * a stale result is simply discarded in favour of one already in flight.
	 */
	public Status status(MeshRevision now) {
		if (failure != null) {
			return Status.FAILED;
		}
		if (now == null) {
			return Status.STALE_WORLD;
		}
		if (builtFrom.worldId() != now.worldId()) {
			return Status.STALE_WORLD;
		}
		if (builtFrom.packGeneration() != now.packGeneration()) {
			return Status.STALE_PACK;
		}
		if (builtFrom.sectionRevision() != now.sectionRevision()
			|| builtFrom.sectionKey() != now.sectionKey()) {
			return Status.STALE_SECTION;
		}
		return isEmpty() ? Status.EMPTY : Status.ACCEPTED;
	}

	@Override
	public String toString() {
		return "MeshBuildResult[" + builtFrom + " vertices=" + vertexCount
			+ " indices=" + indexCount + " bytes=" + payload.length
			+ (failure == null ? "" : " failed=" + failure) + "]";
	}
}
