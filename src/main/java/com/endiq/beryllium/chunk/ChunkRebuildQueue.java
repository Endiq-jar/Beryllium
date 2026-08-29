package com.endiq.beryllium.chunk;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ChunkRebuildQueue {
	private final Map<Long, ChunkRebuildRequest> pending = new LinkedHashMap<>();

	public void submitOrUpdate(ChunkRebuildRequest request) {
		pending.put(request.key(), request);
	}

	public boolean contains(long key) {
		return pending.containsKey(key);
	}

	public void remove(long key) {
		pending.remove(key);
	}

	public int size() {
		return pending.size();
	}

	public List<ChunkRebuildRequest> orderedSnapshot(
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		List<ChunkRebuildRequest> list = new ArrayList<>(pending.values());
		list.sort(Comparator.comparingDouble(r -> ChunkRebuildPriority.score(
			camX, camY, camZ, forwardX, forwardY, forwardZ,
			r.centerX(), r.centerY(), r.centerZ(), r.urgent()
		)));
		return list;
	}

	public List<ChunkRebuildRequest> drain(
		int maxCount,
		double camX, double camY, double camZ,
		double forwardX, double forwardY, double forwardZ
	) {
		if (maxCount <= 0) {
			return List.of();
		}
		List<ChunkRebuildRequest> ordered = orderedSnapshot(camX, camY, camZ, forwardX, forwardY, forwardZ);
		int count = Math.min(maxCount, ordered.size());
		List<ChunkRebuildRequest> result = new ArrayList<>(ordered.subList(0, count));
		for (ChunkRebuildRequest r : result) {
			pending.remove(r.key());
		}
		return result;
	}
}
