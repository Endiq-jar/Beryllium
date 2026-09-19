package com.endiq.beryllium.chat;

/**
 * The two chat categories Beryllium can hide, decided from the translation key of the message.
 *
 * <p>Deciding on the key rather than on the rendered text is what makes this safe: a player
 * whose name happens to look like an advancement line is never hidden, because their message is
 * not a {@code chat.type.advancement} line.
 */
public final class ChatFilterRules {
	private ChatFilterRules() {
	}

	/** Vanilla's keys for "X has made the advancement Y" / "X has completed the challenge Y" /
	 *  "X has reached the goal Y", plus the modern announcement variants. */
	public static boolean isAdvancementAnnouncement(String translationKey) {
		return translationKey != null && translationKey.startsWith("chat.type.advancement");
	}

	/** Command feedback ("X has changed the game mode", "Set the time to day"). */
	public static boolean isAdminMessage(String translationKey) {
		return "chat.type.admin".equals(translationKey);
	}

	/**
	 * True when the admin line came from the server rather than a player. Vanilla puts the
	 * command source as the first argument, and uses a bare {@code @} for the console.
	 */
	public static boolean isServerGeneratedAdminMessage(Object firstArgument) {
		if (firstArgument == null) {
			return false;
		}
		if (firstArgument instanceof CharSequence text) {
			return "@".contentEquals(text);
		}
		try {
			// Component#getString, reached reflectively so this stays a plain helper.
			Object string = firstArgument.getClass().getMethod("getString").invoke(firstArgument);
			return "@".equals(string);
		} catch (Throwable t) {
			return false;
		}
	}

	/** Separator-only lines ("=====", "-----") that compact chat should never merge. */
	public static boolean isSeparator(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		String trimmed = text.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c != ' ' && c != '=' && c != '-' && c != '_' && c != '~' && c != '*' && c != '\u2500') {
				return false;
			}
		}
		return true;
	}
}
