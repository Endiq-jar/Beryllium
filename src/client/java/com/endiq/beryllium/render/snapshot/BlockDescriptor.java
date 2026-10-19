package com.endiq.beryllium.render.snapshot;

import java.util.Arrays;

/**
 * One distinct block state in a section, described by how it behaves rather than by what
 * it is called.
 *
 * <p>This is the part of the contract that stops the engine from having to know about
 * Minecraft's blocks. The engine's own registry is a frozen {@code uint8} enum of about
 * thirty entries; Minecraft has tens of thousands of states, and modpacks add more. A
 * descriptor that carried only a name or an id would force the engine to map everything it
 * had not heard of onto something it had, which is how a modded world turns into a
 * landscape of stone — or, with the more obvious fallback, into nothing at all.
 *
 * <p>So the descriptor carries the properties that actually decide how geometry is produced
 * and drawn: whether it occupies space, whether it hides the faces behind it, whether it is
 * alpha-tested or blended, how much light it gives off and lets through, which render layer
 * it belongs to, and what kind of shape it has. A block the engine has never seen still
 * meshes correctly from those, and the name travels alongside so the engine can resolve it
 * exactly when it happens to know it.
 *
 * <p>Immutable. The {@code customModel} array is copied on the way in and defensively on the
 * way out, so a descriptor cannot be changed by whoever built it or by whoever reads it —
 * which is what allows it to be handed to a worker thread.
 */
public final class BlockDescriptor {
	private final String name;
	private final int flags;
	private final int lightEmission;
	private final int lightAttenuation;
	private final int skylightFilter;
	private final int renderLayer;
	private final int modelKind;
	private final byte[] customModel;

	public BlockDescriptor(String name, int flags, int lightEmission, int lightAttenuation,
	                       int skylightFilter, int renderLayer, int modelKind, byte[] customModel) {
		this.name = name == null ? "" : name;
		this.flags = flags;
		this.lightEmission = clamp(lightEmission);
		this.lightAttenuation = clamp(lightAttenuation);
		this.skylightFilter = clamp(skylightFilter);
		this.renderLayer = renderLayer;
		this.modelKind = modelKind;
		this.customModel = customModel == null ? new byte[0] : customModel.clone();
	}

	/** Air: nothing to draw, no material properties. */
	public static BlockDescriptor air() {
		return new BlockDescriptor("minecraft:air", 0, 0, 0, 0,
			SnapshotFormat.LAYER_OPAQUE, SnapshotFormat.MODEL_AIR, null);
	}

	/**
	 * A descriptor for a block state whose properties could not be read.
	 *
	 * <p>Solid, opaque and cube-shaped, because that is the assumption that fails visibly:
	 * a block that should have been transparent shows up as stone and somebody reports it,
	 * whereas a block that should have been solid showing up as air is a hole in the world
	 * that looks like a rendering bug in something else entirely.
	 */
	public static BlockDescriptor unknownSolid(String name) {
		return new BlockDescriptor(name,
			SnapshotFormat.FLAG_SOLID | SnapshotFormat.FLAG_OPAQUE,
			0, 15, 15, SnapshotFormat.LAYER_OPAQUE, SnapshotFormat.MODEL_FULL_CUBE, null);
	}

	private static int clamp(int value) {
		return Math.max(0, Math.min(15, value));
	}

	public String name() {
		return name;
	}

	public int flags() {
		return flags;
	}

	public int lightEmission() {
		return lightEmission;
	}

	public int lightAttenuation() {
		return lightAttenuation;
	}

	public int skylightFilter() {
		return skylightFilter;
	}

	public int renderLayer() {
		return renderLayer;
	}

	public int modelKind() {
		return modelKind;
	}

	/** A copy of the opaque model payload; empty when the descriptor carries none. */
	public byte[] customModel() {
		return customModel.clone();
	}

	public int customModelLength() {
		return customModel.length;
	}

	public boolean isAir() {
		return modelKind == SnapshotFormat.MODEL_AIR
			|| (flags & (SnapshotFormat.FLAG_SOLID | SnapshotFormat.FLAG_OPAQUE
				| SnapshotFormat.FLAG_CUTOUT | SnapshotFormat.FLAG_TRANSLUCENT)) == 0;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BlockDescriptor)) {
			return false;
		}
		BlockDescriptor that = (BlockDescriptor) other;
		return flags == that.flags
			&& lightEmission == that.lightEmission
			&& lightAttenuation == that.lightAttenuation
			&& skylightFilter == that.skylightFilter
			&& renderLayer == that.renderLayer
			&& modelKind == that.modelKind
			&& name.equals(that.name)
			&& Arrays.equals(customModel, that.customModel);
	}

	@Override
	public int hashCode() {
		int result = name.hashCode();
		result = 31 * result + flags;
		result = 31 * result + lightEmission;
		result = 31 * result + lightAttenuation;
		result = 31 * result + skylightFilter;
		result = 31 * result + renderLayer;
		result = 31 * result + modelKind;
		result = 31 * result + Arrays.hashCode(customModel);
		return result;
	}

	@Override
	public String toString() {
		return "BlockDescriptor[" + name + " flags=0x" + Integer.toHexString(flags)
			+ " layer=" + renderLayer + " model=" + modelKind
			+ " emission=" + lightEmission + "]";
	}
}
