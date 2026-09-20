package com.endiq.beryllium.keybinds;

import com.endiq.beryllium.Beryllium;
import com.endiq.beryllium.compat.Reflect;
import com.endiq.beryllium.config.BerylliumConfig;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which binds share which key, so one key press can drive all of them.
 *
 * <p>Built from the game's own list of binds rather than from what this mod saw being
 * constructed, because binds are also created while options are loaded and while a resource
 * pack is applied. Rebuilt when the game rebuilds its bind list (which is what happens when the
 * player rebinds something), and also if it has been a second since the last rebuild, so a
 * rebind that the game applies quietly is picked up before the next key press.
 *
 * <p>Keyed by the key object the game itself compares, so nothing here has to agree with a
 * release about what a key is.
 */
public final class KeyBindingRegistry {
	private KeyBindingRegistry() {
	}

	private static final Map<Object, List<Object>> BY_KEY = new IdentityHashMap<>();
	private static volatile boolean built;
	private static volatile long builtAt;

	/** Called when a bind is constructed. */
	public static synchronized void add(Object binding) {
		try {
			Object key = Reflect.get(binding, "key");
			if (key != null) {
				BY_KEY.computeIfAbsent(key, unused -> new ArrayList<>()).add(binding);
				built = true;
				builtAt = System.currentTimeMillis();
			}
		} catch (Throwable ignored) {
			// A bind that is not indexed is a bind that behaves like vanilla's.
		}
	}

	/** The game is about to rebuild its bind list; the index is no longer meaningful. */
	public static void invalidate() {
		built = false;
	}

	/** @return true when every bind on this key has been clicked and vanilla must not run. */
	public static boolean click(Object key) {
		List<Object> bindings = bindingsFor(key);
		if (bindings == null || bindings.size() < 2) {
			return false;
		}
		boolean handled = true;
		for (Object binding : bindings) {
			Object count = Reflect.get(binding, "clickCount");
			if (!(count instanceof Integer clicks) || !Reflect.set(binding, "clickCount", clicks + 1)) {
				// A release that no longer exposes the click counter: none of the binds on this key
				// have been clicked, so the game must handle the key itself. Returning false after
				// a partial update would drop the key press entirely.
				handled = false;
				break;
			}
		}
		return handled;
	}

	/** @return true when every bind on this key has been pressed, so vanilla must not run. */
	public static boolean set(Object key, boolean down) {
		List<Object> bindings = bindingsFor(key);
		if (bindings == null || bindings.size() < 2) {
			return false;
		}
		for (Object binding : bindings) {
			if (Reflect.findMethod(binding.getClass(), "setDown", 1, new Object[] { down }) == null) {
				// This release does not let the binding be pressed directly; the game handles the
				// key itself rather than the press being lost.
				return false;
			}
		}
		for (Object binding : bindings) {
			Reflect.call(binding, "setDown", down);
		}
		return true;
	}

	private static synchronized List<Object> bindingsFor(Object key) {
		if (!enabled() || key == null) {
			return null;
		}
		if (!built || System.currentTimeMillis() - builtAt > 1000L) {
			rebuild();
		}
		List<Object> bindings = BY_KEY.get(key);
		return bindings == null ? null : List.copyOf(bindings);
	}

	private static void rebuild() {
		BY_KEY.clear();
		built = true;
		builtAt = System.currentTimeMillis();
		try {
			Object all = Reflect.getStatic(Reflect.type("net.minecraft.client.KeyMapping"), "ALL");
			if (all == null) {
				all = Reflect.getStatic(Reflect.type("net.minecraft.client.KeyMapping"), "MAP");
			}
			if (all == null) {
				all = Reflect.getStatic(Reflect.type("net.minecraft.client.KeyMapping"), "keyMappings");
			}
			if (!(all instanceof Map<?, ?> mappings)) {
				return;
			}
			for (Object binding : mappings.values()) {
				add(binding);
			}
		} catch (Throwable ignored) {
			// An empty index means the game behaves exactly as vanilla.
		}
	}

	private static boolean enabled() {
		BerylliumConfig config = Beryllium.config();
		return config != null && config.enabled && config.multipleBindingsPerKey;
	}

	/** @return how many binds share a key, for the log line at startup. */
	public static int sharedKeyCount() {
		synchronized (KeyBindingRegistry.class) {
			if (!built) {
				rebuild();
			}
			int count = 0;
			for (List<Object> bindings : BY_KEY.values()) {
				if (bindings.size() > 1) {
					count++;
				}
			}
			return count;
		}
	}

	/** Unused today, kept so the index can be inspected from a debug overlay. */
	public static Set<Object> indexedKeys() {
		synchronized (KeyBindingRegistry.class) {
			return Set.copyOf(BY_KEY.keySet());
		}
	}
}
