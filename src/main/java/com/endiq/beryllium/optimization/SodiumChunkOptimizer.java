package com.endiq.beryllium.optimization;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;

/**
 * Sodium-competitor chunk pipeline — face culling, greedy meshing, occlusion, vertex dedup.
 *
 * <p>Vanilla builds a chunk mesh per section by emitting 6 faces per block and letting the
 * GPU discard the hidden ones. Sodium replaces that with: (1) face culling against opaque
 * neighbours, (2) greedy meshing that merges coplanar quads, (3) occlusion graph that
 * skips sections hidden behind others, (4) vertex deduplication. Beryllium's version is
 * not a full Sodium fork — it is a set of hooks into the vanilla mesher that skip the
 * same work, gated by {@code potatoSodiumChunkBuild} and the broader no-mercy flag.
 *
 * <p>All methods are Allocation-free on the hot path (no boxing, no map lookups). The
 * heavy lifting is in the mixins that call here: {@code ChunkRebuildMixin},
 * {@code PotatoSodiumMixin}, and {@code DeduplicationCache}.
 */
public final class SodiumChunkOptimizer {
    private SodiumChunkOptimizer() {}

    public static boolean enabled() {
        BerylliumConfig c = Beryllium.config();
        return c != null && c.enabled && (c.sodiumCompetitor || c.sodiumCompetitorMode || c.potatoSodiumChunkBuild || c.chunkBuildOptimization || c.optimizeChunkBuilding || PotatoOptimizer.potatoEnabled());
    }

    public static boolean greedyMeshing() {
        return enabled() && (Beryllium.config().potatoGreedyMeshing || PotatoOptimizer.greedyMeshing());
    }

    public static boolean faceCulling() {
        BerylliumConfig c = Beryllium.config();
        return enabled() && (c.potatoFaceCulling || c.potatoChunkOcclusion || PotatoOptimizer.potatoEnabled());
    }

    public static boolean occlusionCulling() {
        return enabled() && PotatoOptimizer.occlusionCulling();
    }

    public static boolean vertexDeduplication() {
        BerylliumConfig c = Beryllium.config();
        return enabled() && (c.potatoVertexDeduplication || c.dedupVertices || PotatoOptimizer.potatoEnabled());
    }

    /** Sodium skips sections that are fully occluded by the visibility graph. */
    public static boolean shouldCullSection(boolean isOccluded, double dist) {
        if (!enabled()) return false;
        if (isOccluded) return true;
        if (PotatoOptimizer.potatoEnabled() && dist > 32) return true;
        return false;
    }

    /** Greedy meshing merges quads; this is the per-face fast-path check. */
    public static boolean shouldMergeFaces() {
        return greedyMeshing();
    }
}
