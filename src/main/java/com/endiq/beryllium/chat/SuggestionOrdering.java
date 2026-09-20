package com.endiq.beryllium.chat;

import com.mojang.brigadier.suggestion.Suggestion;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ordering for the command suggestion popup.
 *
 * <p>Vanilla only suggests what <em>starts with</em> what you have typed, and it refuses a
 * candidate whose namespaced id does not start with the namespace you typed. Both rules throw
 * away candidates the player obviously meant: <code>give @s diamond</code> should offer
 * <code>minecraft:diamond_sword</code> even though the id starts with its namespace, and a
 * half-remembered command should still be found by the middle of its name.
 *
 * <p>This ranks rather than filters: exact-prefix matches first, then matches that contain the
 * typed text at all, then everything else, which is what makes it safe to be more generous than
 * vanilla — the player's likely answer is at the top of the list, and nothing has been removed.
 */
public final class SuggestionOrdering {
	private SuggestionOrdering() {
	}

	/**
	 * @param typedText what the player has typed for the argument being completed
	 * @param candidates the suggestions vanilla produced
	 * @return the same suggestions, ordered by how well they match
	 */
	public static List<Suggestion> rank(String typedText, List<Suggestion> candidates) {
		if (candidates == null || candidates.isEmpty()) {
			return candidates;
		}
		String needle = normalise(typedText);
		if (needle.isEmpty()) {
			return candidates;
		}

		List<Suggestion> prefixMatches = new ArrayList<>(candidates.size());
		List<Suggestion> containsMatches = new ArrayList<>(candidates.size());
		List<Suggestion> rest = new ArrayList<>(candidates.size());

		for (Suggestion suggestion : candidates) {
			String text = suggestion.getText() == null ? "" : suggestion.getText().toLowerCase(Locale.ROOT);
			if (startsWith(text, needle)) {
				prefixMatches.add(suggestion);
			} else if (text.contains(needle)) {
				containsMatches.add(suggestion);
			} else {
				rest.add(suggestion);
			}
		}

		prefixMatches.addAll(containsMatches);
		prefixMatches.addAll(rest);
		return prefixMatches;
	}

	/**
	 * True when {@code candidate} should be offered for {@code remaining}. Mirrors
	 * {@link #rank}'s rules so the two agree about what "matches" means.
	 */
	public static boolean matchesSubstring(String remaining, String candidate) {
		if (candidate == null) {
			return false;
		}
		String needle = normalise(remaining);
		if (needle.isEmpty()) {
			return true;
		}
		String haystack = candidate.toLowerCase(Locale.ROOT);
		if (haystack.contains(needle)) {
			return true;
		}
		// "diam" should still reach "minecraft:diamond" and ":diamond" alike.
		if (haystack.startsWith(needle)) {
			return true;
		}
		if (needle.length() > 1 && needle.charAt(0) == '#' && haystack.contains(needle.substring(1))) {
			return true;
		}
		return haystack.indexOf(needle) > 0;
	}

	private static boolean startsWith(String candidate, String needle) {
		return candidate.startsWith(needle)
				|| (needle.indexOf(':') > 0 && candidate.indexOf(':') > 0
						&& candidate.substring(candidate.indexOf(':') + 1).startsWith(needle.substring(needle.indexOf(':') + 1)));
	}

	/** Lowercases and drops the leading {@code /} and any typed namespace, the way a player
	 *  means them. */
	private static String normalise(String text) {
		if (text == null) {
			return "";
		}
		String value = text.toLowerCase(Locale.ROOT);
		if (value.startsWith("/")) {
			value = value.substring(1);
		}
		if (value.startsWith("#")) {
			value = value.substring(1);
		}
		return value;
	}
}
