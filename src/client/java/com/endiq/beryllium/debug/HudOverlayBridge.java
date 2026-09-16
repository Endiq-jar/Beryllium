package com.endiq.beryllium.debug;

import com.endiq.beryllium.profiler.DebugOverlay;
import com.endiq.beryllium.util.BerylliumLog;
import net.minecraft.client.Minecraft;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Draws Beryllium's debug overlay through Fabric API's HUD callback <em>when Fabric API
 * happens to be installed</em> — without Beryllium depending on it.
 *
 * <p>Beryllium builds one source tree for every supported Minecraft release, and no single
 * HUD drawing API exists across that range (the callback's context type was renamed and
 * reshaped more than once). Rather than pinning one version's API — which is what forces
 * most mods to ship a hard Fabric API dependency — this class looks the callback up
 * reflectively at runtime, registers a dynamic proxy for it, and draws through the same
 * reflection. If Fabric API is absent, or if its callback has a shape Beryllium does not
 * recognise, the overlay simply stays off and the frame statistics are still available in
 * the log.
 *
 * <p>Nothing here runs unless {@code debugMode} is enabled, and every failure is contained:
 * a missing or reshaped API costs a debug nicety, never a frame.
 */
public final class HudOverlayBridge {
	private static boolean attempted = false;
	private static Method drawString;

	private HudOverlayBridge() {
	}

	public static void register(DebugOverlay overlay) {
		if (attempted || overlay == null) {
			return;
		}
		attempted = true;

		try {
			Class<?> callback = Class.forName("net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback");
			Method register = callback.getMethod("register", callback);
			Object proxy = Proxy.newProxyInstance(
				HudOverlayBridge.class.getClassLoader(),
				new Class<?>[]{callback},
				(InvocationHandler) (target, method, args) -> {
					if ("onHudRender".equals(method.getName()) && args != null && args.length >= 2) {
						render(overlay, args[0]);
					}
					return null;
				}
			);
			register.invoke(null, proxy);
			BerylliumLog.debug("[BERYLLIUM] Debug overlay registered through Fabric API's HUD callback.");
		} catch (ClassNotFoundException e) {
			BerylliumLog.debug("[BERYLLIUM] Fabric API is not installed; the debug overlay is "
				+ "console-only for this session (frame stats are still logged).");
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] Could not register the debug overlay (" + t + "); "
				+ "frame stats remain available in the log.");
		}
	}

	private static void render(DebugOverlay overlay, Object context) {
		try {
			java.util.List<String> lines = overlay.lines();
			if (lines == null || lines.isEmpty() || context == null) {
				return;
			}
			Minecraft client = Minecraft.getInstance();
			if (client == null || client.font == null) {
				return;
			}

			Method draw = drawStringMethod(context.getClass());
			if (draw == null) {
				return;
			}

			int y = 4;
			int lineHeight = overlay.lineHeight();
			int color = 0xFFFFFF;
			for (String line : lines) {
				if (line == null) {
					y += lineHeight;
					continue;
				}
				try {
					draw.invoke(context, client.font, line, 4, y, color);
				} catch (Throwable ignored) {
					return;
				}
				y += lineHeight;
			}
		} catch (Throwable t) {
			BerylliumLog.debug("[BERYLLIUM] Debug overlay draw failed: " + t);
		}
	}

	/**
	 * Locates {@code drawString(Font, String, int, int, int)} on the HUD context object
	 * (named {@code DrawContext} on older releases, {@code GuiGraphics} on newer ones).
	 */
	private static Method drawStringMethod(Class<?> contextClass) {
		if (drawString != null) {
			return drawString;
		}
		Class<?> fontClass;
		try {
			fontClass = Class.forName("net.minecraft.client.gui.Font");
		} catch (ClassNotFoundException e) {
			return null;
		}
		for (Class<?> current = contextClass; current != null && current != Object.class; current = current.getSuperclass()) {
			try {
				Method method = current.getMethod("drawString", fontClass, String.class, int.class, int.class, int.class);
				method.setAccessible(true);
				drawString = method;
				return method;
			} catch (NoSuchMethodException ignored) {
				// try the next shape up the hierarchy
			}
		}
		return null;
	}
}
