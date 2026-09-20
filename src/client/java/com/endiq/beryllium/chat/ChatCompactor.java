package com.endiq.beryllium.chat;

import com.endiq.beryllium.compat.Reflect;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.List;
import java.util.Locale;

/**
 * Compact chat: turns the second, third and hundredth copy of a message into a count on the
 * first one.
 *
 * <p>Reached through reflection for everything that is not a {@link Component}, because the chat
 * history list holds the client's message type, which lives in a different package before and
 * after 1.21.11, and the method that rebuilds the visible list from it was renamed in the same
 * era. Both are looked up by name and cached; if either is missing, this returns the message
 * untouched and the chat behaves exactly as vanilla's.
 */
public final class ChatCompactor {
	private ChatCompactor() {
	}

	/** The colour of the count, used to recognise our own count when a later message arrives. */
	private static final Style COUNT_STYLE = Style.EMPTY.withColor(ChatFormatting.GRAY);

	private static final int MAX_COUNT = 9999;

	/**
	 * @param chatComponent the client's chat component (the mixin's {@code this})
	 * @param message the message about to be added
	 * @param mode {@code "CONSECUTIVE"} to only merge into the newest message, {@code "ALWAYS"}
	 *             to merge into any copy still in history
	 * @return the message to add, with a count appended when a copy was found
	 */
	public static Component compact(Object chatComponent, Component message, String mode) {
		try {
			if (chatComponent == null || message == null) {
				return message;
			}
			String text = message.getString();
			if (ChatFilterRules.isSeparator(text)) {
				return message;
			}

			Object rawHistory = Reflect.get(chatComponent, "allMessages");
			if (!(rawHistory instanceof List<?> history) || history.isEmpty()) {
				return message;
			}

			boolean consecutiveOnly = mode == null || !mode.equalsIgnoreCase("ALWAYS");
			int start = consecutiveOnly ? history.size() - 1 : 0;

			for (int index = start; index >= 0 && index < history.size(); index++) {
				Object candidate = history.get(index);
				Component previous = content(candidate);
				if (previous == null) {
					continue;
				}

				Integer count = countOf(previous);
				if (!sameMessage(previous, message, count != null)) {
					continue;
				}

				int newCount = count == null ? 2 : count + 1;
				if (newCount > MAX_COUNT) {
					return message;
				}

				Component replacement = withCount(message, newCount);

				// Remove the copy that is being folded in, then rebuild the visible list, so the
				// chat shows one line with a count rather than two lines.
				List<Object> mutable = asMutableList(history);
				if (mutable != null) {
					mutable.remove(index);
				}
				refresh(chatComponent);
				return replacement;
			}
			return message;
		} catch (Throwable t) {
			// Any doubt at all: let the message through as vanilla would.
			return message;
		}
	}

	/** The message a chat history entry holds, or null when this is not an entry we understand. */
	private static Component content(Object entry) {
		if (entry == null) {
			return null;
		}
		Object content = Reflect.call(entry, "content");
		return content instanceof Component component ? component : null;
	}

	/**
	 * Are these the same message? A count on the previous one is not part of the message, so it
	 * is compared without it — otherwise the third copy would not match the counted second.
	 */
	private static boolean sameMessage(Component previous, Component current, boolean previousHasCount) {
		Object previousContents = Reflect.call(previous, "getContents");
		Object currentContents = Reflect.call(current, "getContents");
		if (previousContents == null || currentContents == null || !previousContents.equals(currentContents)) {
			return false;
		}
		if (!previous.getStyle().equals(current.getStyle())) {
			return false;
		}

		List<Component> previousSiblings = previous.getSiblings();
		List<Component> currentSiblings = current.getSiblings();
		if (!previousHasCount) {
			return previousSiblings.equals(currentSiblings);
		}
		if (previousSiblings.size() != currentSiblings.size() + 1) {
			return false;
		}
		for (int i = 0; i < currentSiblings.size(); i++) {
			if (!previousSiblings.get(i).equals(currentSiblings.get(i))) {
				return false;
			}
		}
		return true;
	}

	/** The count already on a message, or null when it has none. */
	private static Integer countOf(Component message) {
		List<Component> siblings = message.getSiblings();
		if (siblings.isEmpty()) {
			return null;
		}
		Component last = siblings.get(siblings.size() - 1);
		if (!isCountSibling(last)) {
			return null;
		}
		String text = last.getString();
		int open = text.indexOf("(x");
		int close = text.lastIndexOf(')');
		if (open < 0 || close < open) {
			return null;
		}
		try {
			return Integer.parseInt(text.substring(open + 2, close).trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static boolean isCountSibling(Component sibling) {
		String text = sibling.getString().toUpperCase(Locale.ROOT);
		return text.startsWith(" (X") && text.endsWith(")") && sibling.getStyle().equals(COUNT_STYLE);
	}

	/** The message with its siblings intact and a fresh count appended. */
	private static Component withCount(Component message, int count) {
		MutableComponent base = message instanceof MutableComponent mutable ? mutable.copy() : message.copy();
		// Drop a previous count if the message itself carries one.
		List<Component> siblings = base.getSiblings();
		if (!siblings.isEmpty() && isCountSibling(siblings.get(siblings.size() - 1))) {
			siblings.remove(siblings.size() - 1);
		}
		return base.append(Component.literal(" (x" + count + ")").setStyle(COUNT_STYLE));
	}

	@SuppressWarnings("unchecked")
	private static List<Object> asMutableList(List<?> history) {
		try {
			return (List<Object>) history;
		} catch (Throwable t) {
			return null;
		}
	}

	/** Rebuilds the visible list after history changed. */
	private static void refresh(Object chatComponent) {
		if (Reflect.call(chatComponent, "refreshTrimmedMessages") != null) {
			return;
		}
		Reflect.call(chatComponent, "refreshTrimmedMessage");
	}
}
