package net.vulkanmod.universal;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.Remapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;

/**
 * Merges the per-version VulkanMod jars into a single universal jar.
 *
 * <p>Each input jar is the Loom-remapped artifact of exactly one per-version
 * source tree.  The packer
 * <ol>
 *   <li>relocates every {@code net/vulkanmod/**} class of version {@code V}
 *       into {@code net/vulkanmod/mc<V>'/**} (ASM remapping of the whole
 *       constant pool, so cross-references between the relocated classes keep
 *       working while references to the game itself are left untouched);</li>
 *   <li>rewrites the per-version mixin configurations (package, plugin,
 *       refmap) to the relocated names;</li>
 *   <li>unions the per-version access wideners (entries for a foreign version
 *       never match any class of the running game, which makes them inert);</li>
 *   <li>copies the bundled LWJGL natives and resources (newest version wins);</li>
 *   <li>drops the bundled Fabric API module classes - the universal build
 *       declares Fabric API as a runtime dependency instead, because the
 *       per-version API bundles would collide and could not be safe on a
 *       foreign game version;</li>
 *   <li>installs the universal {@code fabric.mod.json} and the universal
 *       entry point; and finally</li>
 *   <li>re-opens the result and structurally verifies every reference: each
 *       mixin configuration resolves to classes that exist in the jar, each
 *       refmap key resolves to a class, and every class parses with its
 *       internal name matching its entry path.</li>
 * </ol>
 *
 * <p>Usage: {@code --in <version>=<jar>} (repeatable, primary version first)
 * {@code --aw <version>=<accesswidener>} (repeatable) {@code --extra
 * <name>=<file>} (repeatable, e.g. the Beryllium license) {@code --out
 * <jar>} {@code --version <modVersion>}.
 */
public final class JarPacker {

	static final String MOD_PACKAGE = "net/vulkanmod/";
	static final String MOD_PACKAGE_DOTTED = "net.vulkanmod.";

	private JarPacker() {
	}

	public static void main(String[] args) throws Exception {
		Map<String, Path> inputs = new LinkedHashMap<>();
		Map<String, Path> awInputs = new LinkedHashMap<>();
		Map<String, Path> extras = new LinkedHashMap<>();
		Path out = null;
		String version = null;
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			switch (arg) {
				case "--in" -> {
					String[] kv = args[++i].split("=", 2);
					inputs.put(kv[0], Path.of(kv[1]));
				}
				case "--aw" -> {
					String[] kv = args[++i].split("=", 2);
					awInputs.put(kv[0], Path.of(kv[1]));
				}
				case "--extra" -> {
					String[] kv = args[++i].split("=", 2);
					extras.put(kv[0], Path.of(kv[1]));
				}
				case "--out" -> out = Path.of(args[++i]);
				case "--version" -> version = args[++i];
				default -> throw new IllegalArgumentException("unknown argument: " + arg);
			}
		}
		if (inputs.isEmpty() || out == null || version == null) {
			throw new IllegalArgumentException(
					"usage: JarPacker --in v=jar ... --aw v=aw ... --extra name=file ... --out jar --version x");
		}
		pack(inputs, awInputs, extras, out, version);
		System.out.println("[JarPacker] universal jar written: " + out);
	}

	/* ------------------------------------------------------------------ */

	private static void pack(Map<String, Path> inputs, Map<String, Path> awInputs, Map<String, Path> extras,
			Path out, String version) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		int relocatedClasses = 0;
		int droppedApiClasses = 0;
		int copiedResources = 0;

		for (Map.Entry<String, Path> input : inputs.entrySet()) {
			String v = input.getKey();
			String compact = v.replace(".", "");
			String prefix = MOD_PACKAGE + "mc" + compact + "/";
			String prefixDotted = MOD_PACKAGE_DOTTED + "mc" + compact + ".";
			int classes = 0;
			int api = 0;

			try (JarFile jar = new JarFile(input.getValue().toFile())) {
				// Pass 1: read everything, so decisions can see the whole jar.
				Map<String, byte[]> raw = new LinkedHashMap<>();
				var en = jar.entries();
				while (en.hasMoreElements()) {
					JarEntry ze = en.nextElement();
					if (ze.isDirectory()) {
						continue;
					}
					raw.put(ze.getName(), read(jar, ze));
				}

				boolean vulkanConfigHasRefmapKey = false;
				String vulkanConfigName = "vulkanmod.mixins.json";
				if (!raw.containsKey(vulkanConfigName)) {
					for (String candidate : raw.keySet()) {
						if (candidate.endsWith("vulkanmod.mixins.json")) {
							vulkanConfigName = candidate;
							break;
						}
					}
				}
				if (raw.containsKey(vulkanConfigName)) {
					JsonObject config = new Gson().fromJson(
							new String(raw.get(vulkanConfigName), StandardCharsets.UTF_8), JsonObject.class);
					vulkanConfigHasRefmapKey = config.has("refmap");
				}

				for (Map.Entry<String, byte[]> r : raw.entrySet()) {
					String name = r.getKey();
					if (name.startsWith(MOD_PACKAGE) && name.endsWith(".class")) {
						entries.put(prefix + name.substring(MOD_PACKAGE.length()),
								renameClassBytes(r.getValue(), prefix));
						classes++;
					} else if (name.startsWith("net/fabricmc/")) {
						// Bundled Fabric API module classes: version-specific and
						// colliding, so the universal build depends on the API at
						// runtime instead.
						api++;
					} else if (name.endsWith(".class")) {
						System.err.println("[JarPacker] WARNING: unexpected class from " + v + ": " + name);
					} else if (name.equals(vulkanConfigName)) {
						byte[] rewritten = rewriteMixinConfig(r.getValue(), prefixDotted, compact,
								!vulkanConfigHasRefmapKey && raw.containsKey("vulkanmod.refmap.json"));
						entries.put(prefix + "vulkanmod.mixins.json", rewritten);
					} else if (name.equals("vulkanmod.refmap.json")) {
						entries.put("vulkanmod-mc" + compact + ".refmap.json",
								rewriteRefmap(r.getValue(), prefixDotted));
					} else if (name.endsWith(".mixins.json") || name.endsWith(".refmap.json")) {
						// Bundled Fabric API mixin configs / refmaps: dropped along
						// with the API classes (the runtime API brings its own).
					} else if (name.equals("fabric.mod.json") || name.startsWith("META-INF/")
							|| name.equals("gradle.properties") || name.endsWith(".accesswidener")
							|| name.endsWith(".orig") || name.endsWith(".jar")) {
						// replaced by the universal manifest or dropped
					} else if (name.startsWith("LICENSE")) {
						// Each per-version jar carries its own LGPL copy (renamed by
						// the upstream jar task); the universal jar keeps one.
						if (!entries.containsKey("LICENSE")) {
							entries.put("LICENSE", r.getValue());
							copiedResources++;
						}
					} else if (!entries.containsKey(name)) {
						// resources, natives, licenses: first (newest) version wins
						entries.put(name, r.getValue());
						copiedResources++;
					}
				}
			}
			relocatedClasses += classes;
			droppedApiClasses += api;
			System.out.println("[JarPacker] " + v + ": " + classes + " classes relocated, " + api
					+ " Fabric API classes dropped");
		}

		/* Universal manifest */
		JsonObject manifest = new JsonObject();
		manifest.addProperty("schemaVersion", 1);
		manifest.addProperty("id", "vulkanmod");
		manifest.addProperty("version", version);
		manifest.addProperty("name", "VulkanMod");
		manifest.addProperty("description",
				"Vulkan based voxel rendering engine for Minecraft. Universal build: one jar containing the "
						+ "compiled code sets for " + String.join(", ", inputs.keySet())
						+ ". The set matching your game version activates; all others stay inert.");
		JsonArray authors = new JsonArray();
		authors.add(new JsonPrimitive("Collateral"));
		authors.add(new JsonPrimitive("Endiq-jar (universal packaging)"));
		manifest.add("authors", authors);
		JsonObject contact = new JsonObject();
		contact.addProperty("homepage", "");
		contact.addProperty("sources", "https://github.com/xCollateral/VulkanMod");
		manifest.add("contact", contact);
		manifest.addProperty("icon", "assets/vulkanmod/vlogo.png");
		manifest.addProperty("environment", "client");
		JsonObject entrypoints = new JsonObject();
		JsonArray client = new JsonArray();
		client.add("net.vulkanmod.universal.Initializer");
		entrypoints.add("client", client);
		manifest.add("entrypoints", entrypoints);
		JsonArray mixins = new JsonArray();
		for (String v : inputs.keySet()) {
			mixins.add(MOD_PACKAGE + "mc" + v.replace(".", "") + "/vulkanmod.mixins.json");
		}
		manifest.add("mixins", mixins);
		manifest.addProperty("accessWidener", "vulkanmod.accesswidener");
		JsonObject depends = new JsonObject();
		depends.addProperty("fabricloader", ">=0.14.14");
		depends.addProperty("minecraft", ">=1.19.4");
		depends.addProperty("fabric-api", "*");
		manifest.add("depends", depends);
		JsonObject custom = new JsonObject();
		custom.addProperty("fabric-renderer-api-v1:contains_renderer", true);
		manifest.add("custom", custom);
		entries.put("fabric.mod.json", new Gson().toJson(manifest).getBytes(StandardCharsets.UTF_8));

		/* Merged access widener (source form: named/mojmap namespace; entries for a
		 * foreign version simply never match any class of the running game). */
		entries.put("vulkanmod.accesswidener", mergeAccessWideners(awInputs).getBytes(StandardCharsets.UTF_8));

		/* Universal entry point classes (compiled by this build). */
		Path classesDir = Path.of("build/classes/java/main/net/vulkanmod/universal");
		if (!Files.isDirectory(classesDir)) {
			throw new IllegalStateException("universal classes not found at " + classesDir);
		}
		try (var stream = Files.list(classesDir)) {
			for (Path classFile : (Iterable<Path>) stream::iterator) {
				String fileName = classFile.getFileName().toString();
				if (fileName.endsWith(".class")) {
					entries.put("net/vulkanmod/universal/" + fileName, Files.readAllBytes(classFile));
				}
			}
		}

		/* Extra files (licenses, attribution). */
		for (Map.Entry<String, Path> extra : extras.entrySet()) {
			entries.put(extra.getKey(), Files.readAllBytes(extra.getValue()));
		}

		if (Files.exists(out)) {
			Files.delete(out);
		}
		Path parent = out.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
		try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(out))) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				jar.putNextEntry(new ZipEntry(entry.getKey()));
				jar.write(entry.getValue());
				jar.closeEntry();
			}
		}

		verify(out, new LinkedHashSet<>(inputs.keySet()));
		System.out.println("[JarPacker] " + relocatedClasses + " classes relocated, " + droppedApiClasses
				+ " Fabric API classes dropped, " + copiedResources + " resources copied");
	}

	/* ------------------------------------------------------------------ */

	/** Relocates one mod class from {@code net/vulkanmod/X} to {@code prefixX}. */
	private static byte[] renameClassBytes(byte[] classBytes, String prefix) {
		Remapper remapper = new Remapper() {
			@Override
			public String map(String internalName) {
				if (internalName.startsWith(MOD_PACKAGE)) {
					return prefix + internalName.substring(MOD_PACKAGE.length());
				}
				return internalName;
			}
		};
		ClassReader reader = new ClassReader(classBytes);
		ClassWriter writer = new ClassWriter(reader, 0);
		new ClassRemapper(writer, remapper).accept(reader, 0);
		return writer.toByteArray();
	}

	private static byte[] read(JarFile jar, JarEntry entry) throws IOException {
		try (InputStream stream = jar.getInputStream(entry)) {
			return stream.readAllBytes();
		}
	}

	/**
	 * Rewrites package / plugin / mixin entries / refmap of a mixin
	 * configuration.  When {@code addRefmapIfMissing} is set, the relocated
	 * refmap is declared explicitly (covers Loom jars that ship the refmap
	 * file without the JSON key).
	 */
	private static byte[] rewriteMixinConfig(byte[] json, String prefixDotted, String compact,
			boolean addRefmapIfMissing) {
		Gson gson = new Gson();
		JsonObject o = gson.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);

		if (o.has("package") && o.get("package").isJsonPrimitive()) {
			String pkg = o.get("package").getAsString();
			if (pkg.startsWith(MOD_PACKAGE_DOTTED)) {
				o.addProperty("package", prefixDotted + pkg.substring(MOD_PACKAGE_DOTTED.length()));
			}
		}
		if (o.has("plugin") && o.get("plugin").isJsonPrimitive()) {
			String plugin = o.get("plugin").getAsString();
			if (plugin.startsWith(MOD_PACKAGE_DOTTED)) {
				o.addProperty("plugin", prefixDotted + plugin.substring(MOD_PACKAGE_DOTTED.length()));
			}
		}
		for (String key : new String[] { "mixins", "client", "server" }) {
			if (o.has(key) && o.get(key).isJsonArray()) {
				JsonArray array = o.getAsJsonArray(key);
				for (int i = 0; i < array.size(); i++) {
					String mixin = array.get(i).getAsString();
					if (mixin.startsWith(MOD_PACKAGE_DOTTED)) {
						array.set(i, new JsonPrimitive(prefixDotted + mixin.substring(MOD_PACKAGE_DOTTED.length())));
					}
				}
			}
		}
		if (o.has("refmap") && o.get("refmap").isJsonPrimitive()) {
			o.addProperty("refmap", "vulkanmod-mc" + compact + ".refmap.json");
		} else if (addRefmapIfMissing) {
			o.addProperty("refmap", "vulkanmod-mc" + compact + ".refmap.json");
		}
		return gson.toJson(o).getBytes(StandardCharsets.UTF_8);
	}

	/** Prefixes every mapping key (a mixin class name) of a refmap. */
	private static byte[] rewriteRefmap(byte[] json, String prefixDotted) {
		Gson gson = new Gson();
		JsonObject o = gson.fromJson(new String(json, StandardCharsets.UTF_8), JsonObject.class);
		JsonObject mappings = o.getAsJsonObject("mappings");
		if (mappings != null) {
			List<String> keys = new ArrayList<>(mappings.keySet());
			for (String key : keys) {
				JsonElement value = mappings.get(key);
				mappings.remove(key);
				if (key.startsWith(MOD_PACKAGE_DOTTED)) {
					mappings.add(prefixDotted + key.substring(MOD_PACKAGE_DOTTED.length()), value);
				} else {
					mappings.add(key, value);
				}
			}
		}
		return gson.toJson(o).getBytes(StandardCharsets.UTF_8);
	}

	/** Union of the per-version source access wideners, deduplicated. */
	private static String mergeAccessWideners(Map<String, Path> awInputs) throws IOException {
		String header = null;
		Set<String> lines = new LinkedHashSet<>();
		for (Path aw : awInputs.values()) {
			for (String line : Files.readAllLines(aw, StandardCharsets.UTF_8)) {
				String trimmed = line.trim();
				if (trimmed.isEmpty()) {
					continue;
				}
				if (trimmed.startsWith("accessWidener")) {
					if (header == null) {
						header = trimmed;
					}
					continue;
				}
				if (trimmed.startsWith("#")) {
					continue;
				}
				lines.add(trimmed);
			}
		}
		if (header == null) {
			throw new IllegalStateException("no access widener header found");
		}
		StringBuilder sb = new StringBuilder(header).append('\n');
		for (String line : lines) {
			sb.append(line).append('\n');
		}
		return sb.toString();
	}


	/** Convenience: read a jar entry as a UTF-8 string. */
	private static String readString(JarFile jar, JarEntry entry) throws IOException {
		return new String(read(jar, entry), StandardCharsets.UTF_8);
	}

	/* ------------------------------------------------------------------ */

	/**
	 * Re-opens the assembled jar and verifies every structural reference.
	 * Fails the build if anything does not resolve.
	 */
	private static void verify(Path jarPath, Set<String> versions) throws Exception {
		Gson gson = new Gson();
		int classes = 0;
		int natives = 0;
		try (JarFile jar = new JarFile(jarPath.toFile())) {
			var en = jar.entries();
			while (en.hasMoreElements()) {
				JarEntry ze = en.nextElement();
				String name = ze.getName();
				if (ze.isDirectory()) {
					continue;
				}
				if (name.endsWith(".class") && name.startsWith("net/vulkanmod/")) {
					classes++;
					try (InputStream stream = jar.getInputStream(ze)) {
						ClassReader reader = new ClassReader(stream);
						String expected = name.substring(0, name.length() - 6).replace('/', '.');
						if (!reader.getClassName().equals(expected)) {
							throw new IllegalStateException("class " + expected + " has internal name "
									+ reader.getClassName());
						}
					}
					if (name.startsWith(MOD_PACKAGE) && !name.startsWith("net/vulkanmod/universal/")
							&& !hasVersionPackage(name, versions)) {
						throw new IllegalStateException("stray class outside version packages: " + name);
					}
				}
				if (name.endsWith(".so") || name.endsWith(".dll") || name.endsWith(".dylib")
						|| name.startsWith("natives-") || name.startsWith("org/lwjgl/")) {
					natives++;
				}
			}

			JarEntry manifest = jar.getJarEntry("fabric.mod.json");
			if (manifest == null) {
				throw new IllegalStateException("fabric.mod.json missing");
			}
			JsonObject o = gson.fromJson(readString(jar, manifest), JsonObject.class);
			JsonArray mixinConfigs = o.getAsJsonArray("mixins");
			for (JsonElement element : mixinConfigs) {
				String configPath = element.getAsString();
				JarEntry configEntry = jar.getJarEntry(configPath);
				if (configEntry == null) {
					throw new IllegalStateException("mixin config missing from jar: " + configPath);
				}
				JsonObject config = gson.fromJson(readString(jar, configEntry), JsonObject.class);
				String base = config.get("package").getAsString();
				if (!base.startsWith(MOD_PACKAGE_DOTTED)) {
					throw new IllegalStateException("mixin config package not relocated: " + configPath + " -> " + base);
				}
				if (config.has("plugin")) {
					String plugin = config.get("plugin").getAsString().replace('.', '/') + ".class";
					if (jar.getJarEntry(plugin) == null) {
						throw new IllegalStateException("plugin class missing: " + plugin);
					}
				}
				int resolved = 0;
				for (String section : new String[] { "mixins", "client", "server" }) {
					if (!config.has(section)) {
						continue;
					}
					for (JsonElement mixin : config.getAsJsonArray(section)) {
						String entryName = mixin.getAsString();
						String fq = entryName.startsWith(base) ? entryName : base + entryName;
						String classPath = fq.replace('.', '/') + ".class";
						if (jar.getJarEntry(classPath) == null) {
							throw new IllegalStateException("mixin class missing from jar: " + classPath
									+ " (from " + configPath + ")");
						}
						resolved++;
					}
				}
				if (config.has("refmap")) {
					String refmap = config.get("refmap").getAsString();
					JarEntry refmapEntry = jar.getJarEntry(refmap);
					if (refmapEntry == null) {
						throw new IllegalStateException("refmap missing: " + refmap);
					}
					JsonObject refmapJson = gson.fromJson(readString(jar, refmapEntry), JsonObject.class);
					JsonObject mappings = refmapJson.getAsJsonObject("mappings");
					if (mappings != null) {
						for (String key : mappings.keySet()) {
							String cls = key.replace('.', '/') + ".class";
							if (jar.getJarEntry(cls) == null) {
								throw new IllegalStateException("refmap key does not resolve to a jar class: " + cls);
							}
						}
					}
					System.out.println("[JarPacker] " + configPath + ": " + resolved + " mixin classes resolve, refmap "
							+ refmap + " ok");
				} else {
					System.out.println("[JarPacker] " + configPath + ": " + resolved + " mixin classes resolve (no refmap)");
				}
			}

			if (jar.getJarEntry("net/vulkanmod/universal/Initializer.class") == null) {
				throw new IllegalStateException("universal entry point missing");
			}
			if (jar.getJarEntry("vulkanmod.accesswidener") == null) {
				throw new IllegalStateException("merged access widener missing");
			}
		}
		System.out.println("[JarPacker] verification passed: " + classes
				+ " version-prefixed/universal classes, " + natives + " native resource entries");
	}

	private static boolean hasVersionPackage(String name, Set<String> versions) {
		for (String v : versions) {
			if (name.startsWith(MOD_PACKAGE + "mc" + v.replace(".", "") + "/")) {
				return true;
			}
		}
		return false;
	}
}
