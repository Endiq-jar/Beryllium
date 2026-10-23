package com.endiq.beryllium.render.snapshot;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable, versioned picture of one 16x16x16 section, taken on the render thread and
 * safe to hand to a native worker.
 *
 * <p>This is the object the whole engine boundary is built around. A worker thread must
 * never read a live Minecraft world object, because those are mutated by the game thread
 * while a build runs and a build reading a half-updated section produces geometry that
 * corresponds to no state the world was ever in. So the render thread copies a section into
 * one of these — final fields, copied arrays, nothing reachable that can change — and the
 * worker meshes the copy.
 *
 * <p>It also carries a {@link MeshRevision}, which is what lets the render thread refuse the
 * result if the world, the section or the resource pack moved during the build. See
 * {@link MeshBuildResult}.
 *
 * <p>{@link #encode()} produces the byte layout defined in {@code engine/src/snapshot.h} and
 * transcribed in {@link SnapshotFormat}. The two are the same contract seen from either side
 * and must change together; the format version is what makes a mismatch survivable rather
 * than silently corrupt.
 */
public final class SectionSnapshot {
	private final int sectionX;
	private final int sectionY;
	private final int sectionZ;
	private final MeshRevision revision;
	private final List<BlockDescriptor> palette;
	private final short[] indices;
	private final byte[] light;

	/**
	 * @param palette  distinct block states in this section, indexed by the values in
	 *                 {@code indices}. Must not be empty.
	 * @param indices  one entry per voxel, in x + z*16 + y*256 order, each a palette index
	 * @param light    one byte per voxel (low nibble sky, high nibble block), or {@code null}
	 *                 when the engine should compute its own lighting
	 */
	public SectionSnapshot(int sectionX, int sectionY, int sectionZ, MeshRevision revision,
	                       List<BlockDescriptor> palette, short[] indices, byte[] light) {
		if (revision == null) {
			throw new IllegalArgumentException("a snapshot must carry the revision it was taken at");
		}
		if (palette == null || palette.isEmpty()) {
			throw new IllegalArgumentException("a snapshot needs at least one palette entry");
		}
		if (palette.size() > SnapshotFormat.SECTION_VOLUME) {
			throw new IllegalArgumentException(
				"a palette larger than the section it describes: " + palette.size());
		}
		if (indices == null || indices.length != SnapshotFormat.SECTION_VOLUME) {
			throw new IllegalArgumentException(
				"a snapshot needs exactly " + SnapshotFormat.SECTION_VOLUME + " voxel indices");
		}
		for (int i = 0; i < indices.length; i++) {
			int index = indices[i] & 0xFFFF;
			if (index >= palette.size()) {
				throw new IllegalArgumentException(
					"voxel " + i + " refers to palette entry " + index
						+ " but the palette holds " + palette.size());
			}
		}
		if (light != null && light.length != SnapshotFormat.SECTION_VOLUME) {
			throw new IllegalArgumentException(
				"light must cover every voxel, or be absent entirely");
		}

		this.sectionX = sectionX;
		this.sectionY = sectionY;
		this.sectionZ = sectionZ;
		this.revision = revision;
		this.palette = Collections.unmodifiableList(new ArrayList<>(palette));
		this.indices = indices.clone();
		this.light = light == null ? null : light.clone();
	}

	public int sectionX() {
		return sectionX;
	}

	public int sectionY() {
		return sectionY;
	}

	public int sectionZ() {
		return sectionZ;
	}

	public MeshRevision revision() {
		return revision;
	}

	public List<BlockDescriptor> palette() {
		return palette;
	}

	/** Palette index of one voxel, in x + z*16 + y*256 order. */
	public int paletteIndexAt(int localX, int localY, int localZ) {
		return indices[localX + localZ * SnapshotFormat.SECTION_SIDE
			+ localY * SnapshotFormat.SECTION_SIDE * SnapshotFormat.SECTION_SIDE] & 0xFFFF;
	}

	public BlockDescriptor blockAt(int localX, int localY, int localZ) {
		return palette.get(paletteIndexAt(localX, localY, localZ));
	}

	/** Whether this snapshot carries baked light, or the engine must compute its own. */
	public boolean hasLight() {
		return light != null;
	}

	/** Sky light 0..15 at a voxel, or 0 when the snapshot carries no light. */
	public int skyLightAt(int localX, int localY, int localZ) {
		if (light == null) {
			return 0;
		}
		return light[voxel(localX, localY, localZ)] & 0xF;
	}

	/** Block light 0..15 at a voxel, or 0 when the snapshot carries no light. */
	public int blockLightAt(int localX, int localY, int localZ) {
		if (light == null) {
			return 0;
		}
		return (light[voxel(localX, localY, localZ)] >> 4) & 0xF;
	}

	private static int voxel(int localX, int localY, int localZ) {
		return localX + localZ * SnapshotFormat.SECTION_SIDE
			+ localY * SnapshotFormat.SECTION_SIDE * SnapshotFormat.SECTION_SIDE;
	}

	/** Whether every voxel is air, in which case there is nothing to mesh or draw. */
	public boolean isEmpty() {
		for (int i = 0; i < indices.length; i++) {
			if (!palette.get(indices[i] & 0xFFFF).isAir()) {
				return false;
			}
		}
		return true;
	}

	/**
	 * Serialises to the wire format the engine parses.
	 *
	 * <p>Little-endian throughout. The engine validates every length against the buffer
	 * before reading it, so a malformed encoding is rejected rather than misread — but the
	 * encoder here still has to be exactly right, because a buffer that parses and describes
	 * the wrong section is worse than one that fails.
	 */
	public byte[] encode() {
		int bitsPerEntry = SnapshotFormat.bitsPerEntryFor(palette.size());
		int flags = light == null ? 0 : SnapshotFormat.FLAG_HAS_LIGHT;

		int size = SnapshotFormat.HEADER_BYTES;
		for (BlockDescriptor descriptor : palette) {
			byte[] name = descriptor.name().getBytes(StandardCharsets.UTF_8);
			size += 4 + 4 + 4 + 8 + 2 + name.length + 4 + descriptor.customModelLength();
		}
		size += SnapshotFormat.indexBytes(bitsPerEntry);
		if (light != null) {
			size += SnapshotFormat.SECTION_VOLUME;
		}

		byte[] out = new byte[size];
		int[] cursor = {0};

		putU32(out, cursor, SnapshotFormat.MAGIC);
		putU32(out, cursor, SnapshotFormat.VERSION);
		putU32(out, cursor, flags);
		putU32(out, cursor, sectionX);
		putU32(out, cursor, sectionY);
		putU32(out, cursor, sectionZ);
		putU64(out, cursor, revision.worldId());
		putU64(out, cursor, revision.sectionRevision());
		putU64(out, cursor, revision.packGeneration());
		putU32(out, cursor, palette.size());
		out[cursor[0]++] = (byte) bitsPerEntry;
		out[cursor[0]++] = 0;
		out[cursor[0]++] = 0;
		out[cursor[0]++] = 0;

		for (int i = 0; i < palette.size(); i++) {
			BlockDescriptor descriptor = palette.get(i);
			byte[] name = descriptor.name().getBytes(StandardCharsets.UTF_8);
			byte[] custom = descriptor.customModel();

			putU32(out, cursor, i);
			/* The engine resolves from the name and treats this as advice only, because a
			 * Java-side id table and a C-side enum drift apart the moment either is edited.
			 * Sending zero says "you decide", which is the honest answer. */
			putU32(out, cursor, 0);
			putU32(out, cursor, descriptor.flags());
			out[cursor[0]++] = (byte) descriptor.lightEmission();
			out[cursor[0]++] = (byte) descriptor.lightAttenuation();
			out[cursor[0]++] = (byte) descriptor.skylightFilter();
			out[cursor[0]++] = (byte) descriptor.renderLayer();
			out[cursor[0]++] = (byte) descriptor.modelKind();
			out[cursor[0]++] = 0;
			out[cursor[0]++] = 0;
			out[cursor[0]++] = 0;
			putU16(out, cursor, name.length);
			System.arraycopy(name, 0, out, cursor[0], name.length);
			cursor[0] += name.length;
			putU32(out, cursor, custom.length);
			if (custom.length > 0) {
				System.arraycopy(custom, 0, out, cursor[0], custom.length);
				cursor[0] += custom.length;
			}
		}

		long bitPosition = 0;
		for (int v = 0; v < SnapshotFormat.SECTION_VOLUME; v++) {
			int value = indices[v] & 0xFFFF;
			for (int bit = 0; bit < bitsPerEntry; bit++) {
				if (((value >> bit) & 1) != 0) {
					int byteIndex = (int) (bitPosition / 8) + cursor[0];
					out[byteIndex] |= (byte) (1 << (int) (bitPosition % 8));
				}
				bitPosition++;
			}
		}
		cursor[0] += SnapshotFormat.indexBytes(bitsPerEntry);

		if (light != null) {
			System.arraycopy(light, 0, out, cursor[0], SnapshotFormat.SECTION_VOLUME);
			cursor[0] += SnapshotFormat.SECTION_VOLUME;
		}

		if (cursor[0] != size) {
			// Cannot happen without a bug above, and a short or overlong buffer would be
			// rejected by the engine anyway. Failing loudly here is easier to diagnose than
			// a mysterious parse error on the other side of a JNI boundary.
			throw new IllegalStateException(
				"encoded " + cursor[0] + " bytes but reserved " + size);
		}
		return out;
	}

	private static void putU16(byte[] out, int[] cursor, int value) {
		out[cursor[0]++] = (byte) (value & 0xFF);
		out[cursor[0]++] = (byte) ((value >> 8) & 0xFF);
	}

	private static void putU32(byte[] out, int[] cursor, int value) {
		out[cursor[0]++] = (byte) (value & 0xFF);
		out[cursor[0]++] = (byte) ((value >> 8) & 0xFF);
		out[cursor[0]++] = (byte) ((value >> 16) & 0xFF);
		out[cursor[0]++] = (byte) ((value >> 24) & 0xFF);
	}

	private static void putU64(byte[] out, int[] cursor, long value) {
		putU32(out, cursor, (int) (value & 0xFFFFFFFFL));
		putU32(out, cursor, (int) ((value >>> 32) & 0xFFFFFFFFL));
	}

	@Override
	public String toString() {
		return "SectionSnapshot[" + sectionX + "," + sectionY + "," + sectionZ
			+ " palette=" + palette.size() + " light=" + (light != null) + "]";
	}
}
