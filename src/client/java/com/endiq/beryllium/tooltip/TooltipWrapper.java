package com.endiq.beryllium.tooltip;

import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps tooltip lines that are wider than the tooltip is allowed to be.
 *
 * <p>A long line is a line that runs off the edge of the screen, and the tooltip positioner
 * cannot save it: it has nowhere to put a box that is wider than the window. Splitting the line
 * is the only fix, and it also gives the positioner a box it can keep on screen.
 *
 * <p>Lines are only split when their text has no styling beyond the line itself — a line built
 * from several differently coloured pieces is left exactly as the game made it, because
 * rebuilding it word by word would drop that styling. In practice the lines that are long
 * enough to need splitting (lore, descriptions, translated text) are the plain ones.
 *
 * <p>Results are cached against the input text and the wrapping width: a tooltip is rebuilt
 * every frame it is on screen, and the answer does not change between frames.
 */
public final class TooltipWrapper {
	private TooltipWrapper() {
	}

	private static String cachedText;
	private static int cachedWidth;
	private static List<Component> cachedResult;

	public static synchronized List<Component> wrap(List<Component> lines, int maxWidth, Font font) {
		if (lines == null || lines.isEmpty() || font == null || maxWidth <= 0) {
			return lines;
		}
		String cacheKey = key(lines);
		if (cacheKey != null && cacheKey.equals(cachedText) && cachedWidth == maxWidth) {
			return cachedResult;
		}

		List<Component> wrapped = new ArrayList<>(lines.size());
		boolean changed = false;
		for (Component line : lines) {
			List<Component> replacement = wrapLine(line, maxWidth, font);
			if (replacement == null) {
				wrapped.add(line);
			} else {
				wrapped.addAll(replacement);
				changed = true;
			}
		}

		List<Component> result = changed ? wrapped : lines;
		if (cacheKey != null) {
			cachedText = cacheKey;
			cachedWidth = maxWidth;
			cachedResult = result;
		}
		return result;
	}

	/** @return the replacement lines, or null when this line should be left alone. */
	private static List<Component> wrapLine(Component line, int maxWidth, Font font) {
		if (line == null) {
			return null;
		}
		try {
			if (!line.getSiblings().isEmpty()) {
				return null;
			}
			String text = line.getString();
			if (text.isEmpty() || text.indexOf('\n') >= 0) {
				return null;
			}
			if (font.width(text) <= maxWidth) {
				return null;
			}

			List<Component> result = new ArrayList<>();
			StringBuilder current = new StringBuilder();
			for (String word : text.split(" ")) {
				String candidate = current.length() == 0 ? word : current + " " + word;
				if (current.length() > 0 && font.width(candidate) > maxWidth) {
					result.add(rebuild(current.toString(), line));
					current.setLength(0);
					current.append(word);
				} else {
					current.setLength(0);
					current.append(candidate);
				}
			}
			if (current.length() > 0) {
				result.add(rebuild(current.toString(), line));
			}
			return result.size() > 1 ? result : null;
		} catch (Throwable t) {
			return null;
		}
	}

	private static Component rebuild(String text, Component template) {
		MutableComponent component = Component.literal(text);
		component.setStyle(template.getStyle());
		return component;
	}

	private static String key(List<Component> lines) {
		try {
			StringBuilder builder = new StringBuilder();
			for (Component line : lines) {
				builder.append(line.getString()).append('\u0000');
			}
			return builder.toString();
		} catch (Throwable t) {
			return null;
		}
	}
}
