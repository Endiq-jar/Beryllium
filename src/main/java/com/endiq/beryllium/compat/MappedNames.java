package com.endiq.beryllium.compat;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates a Mojang-mapped name into whatever the running game actually calls it.
 *
 * <p>Beryllium is compiled against Mojang mappings and Loom remaps the jar before it ships,
 * so every <em>direct</em> reference in the bytecode lands on the right member at runtime.
 * A name held in a {@code String} is a different matter: Loom remaps references, not
 * literals, and a Fabric production runtime does not use Mojang names for Minecraft at all
 * — it uses intermediary. {@code getDeclaredMethod("setDirty", ...)} therefore misses on
 * every obfuscated release in the supported range, however correct the name looks in the
 * source. From 26.1 the game jar ships unobfuscated, and there the Mojang name is already
 * the runtime name.
 *
 * <p>Anything that reaches a Minecraft member by string has to go through here. Fabric's
 * own {@link MappingResolver} does the translation; it is asked once per member and the
 * answer is cached.
 *
 * <p>Every method falls back to the name it was given. In a development environment the
 * runtime namespace already <em>is</em> {@code named}; on 26.1+ no mappings are loaded to
 * resolve through; and a resolver that is missing or throws has to degrade the same way a
 * missing member does, so the caller can treat it as "not there" and fall back to its own
 * last resort.
 */
public final class MappedNames {
	/** The namespace Beryllium's sources are written in. */
	private static final String NAMED = "named";

	private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

	private static volatile MappingResolver resolver;
	private static volatile boolean resolverLookedUp;

	private MappedNames() {
	}

	/**
	 * @param namedOwner      the class that declares the method, in Mojang mappings
	 *                        (e.g. {@code net.minecraft.client.renderer.ViewArea})
	 * @param namedMethod     the method name, in Mojang mappings
	 * @param namedDescriptor the method descriptor, in Mojang mappings. Only descriptors made
	 *                        of primitives need no translation of their own, which is all
	 *                        this is used for.
	 * @return the name the running game uses, or {@code namedMethod} when that cannot be
	 *         determined
	 */
	public static String methodName(String namedOwner, String namedMethod, String namedDescriptor) {
		return CACHE.computeIfAbsent("m:" + namedOwner + '#' + namedMethod + namedDescriptor, unused -> {
			try {
				MappingResolver mappingResolver = resolver();
				if (mappingResolver != null) {
					String resolved = mappingResolver.mapMethodName(NAMED, namedOwner, namedMethod, namedDescriptor);
					if (resolved != null && !resolved.isEmpty()) {
						return resolved;
					}
				}
			} catch (Throwable t) {
				// No usable mapping; the caller still has the literal name and the shape.
			}
			return namedMethod;
		});
	}

	/**
	 * @param namedClass a class name in Mojang mappings
	 * @return the name the running game uses, or {@code namedClass} when it cannot be determined
	 */
	public static String className(String namedClass) {
		return CACHE.computeIfAbsent("c:" + namedClass, unused -> {
			try {
				MappingResolver mappingResolver = resolver();
				if (mappingResolver != null) {
					String resolved = mappingResolver.mapClassName(NAMED, namedClass);
					if (resolved != null && !resolved.isEmpty()) {
						return resolved;
					}
				}
			} catch (Throwable t) {
				// as above
			}
			return namedClass;
		});
	}

	/** True when the running game uses names other than Mojang's — i.e. an obfuscated release. */
	public static boolean runtimeIsRemapped() {
		try {
			MappingResolver mappingResolver = resolver();
			return mappingResolver != null && !NAMED.equals(mappingResolver.getCurrentRuntimeNamespace());
		} catch (Throwable t) {
			return false;
		}
	}

	private static MappingResolver resolver() {
		if (!resolverLookedUp) {
			resolverLookedUp = true;
			try {
				resolver = FabricLoader.getInstance().getMappingResolver();
			} catch (Throwable t) {
				resolver = null;
			}
		}
		return resolver;
	}
}
