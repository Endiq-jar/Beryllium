package com.endiq.beryllium.particle;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.config.BerylliumConfig;
import com.endiq.beryllium.culling.VisibilityCulling;
import net.minecraft.client.particle.Particle;
import net.minecraft.world.phys.Vec3;

/**
 * Particle culling: changes the distance at which particles exist and are drawn.
 *
 * <p>Particles are the one thing in Minecraft that can cost a frame without the player
 * ever having built anything: distant mob spawners, other players' farms, a burning
 * forest over the hill, a lava lake two chunks away. Every one of them spawns particles
 * that are sub-pixel at that range and still tick and render.
 *
 * <p>Beryllium drops particles that are created beyond {@code particleCullDistance} of the
 * camera. Doing it at spawn time (rather than at draw time) is what makes it worth doing:
 * the particle never enters the list, so it never ticks, never sorts into a buffer, and
 * never gets uploaded to the GPU. Particles you can actually see are untouched.
 *
 * <p>Draw-time culling is also applied where Beryllium can hook the render call, which
 * covers particles that were already alive when the player moved away.
 */
public final class ParticleCulling {
	private ParticleCulling() {
	}

	/**
	 * @return true if this particle should never be added to the world
	 */
	public static boolean shouldCullSpawn(Particle particle) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.particleCulling) {
			return false;
		}
		if (particle == null || config.particleCullDistance <= 0.0) {
			return false;
		}

		Vec3 camera = VisibilityCulling.cameraPosition();
		if (camera == null) {
			return false;
		}

		double x;
		double y;
		double z;
		try {
			Vec3 center = particle.getBoundingBox().getCenter();
			x = center.x;
			y = center.y;
			z = center.z;
		} catch (Throwable t) {
			return false;
		}

		double dx = x - camera.x;
		double dy = y - camera.y;
		double dz = z - camera.z;
		double range = config.particleCullDistance;
		return dx * dx + dy * dy + dz * dz > range * range;
	}

	/**
	 * Draw-time variant of the same rule, for particles that were already alive when the
	 * player moved away. Beryllium's own spawn hook means this is rarely needed, but the
	 * decision is shared so a future draw-time hook has exactly one rule to call.
	 */
	public static boolean shouldCullRender(Particle particle) {
		BerylliumConfig config = Beryllium.config();
		if (config == null || !config.enabled || !config.particleCulling) {
			return false;
		}
		return shouldCullSpawn(particle);
	}
}
