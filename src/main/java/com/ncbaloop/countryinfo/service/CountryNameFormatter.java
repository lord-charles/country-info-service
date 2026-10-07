package com.ncbaloop.countryinfo.service;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Normalises user-supplied country names into the casing the upstream provider expects.
 *
 * <p>The provider's name lookup is case-sensitive: {@code "Kenya"} resolves, {@code "kenya"}
 * does not. The brief asks for sentence case ({@code "kenya" -> "Kenya"}), which works for
 * single-word names but not for multi-word ones ({@code "United states"} is unknown upstream,
 * {@code "United States"} resolves). {@link #lookupCandidates(String)} therefore tries
 * sentence case first, then title case.
 */
public final class CountryNameFormatter {

	private static final Set<String> LOWERCASE_WORDS = Set.of("and", "of", "the", "da", "de", "du", "la", "le");

	private CountryNameFormatter() {
	}

	/** {@code "  kENYA "} -> {@code "Kenya"}; {@code "united states"} -> {@code "United states"}. */
	public static String toSentenceCase(String raw) {
		String collapsed = collapseWhitespace(raw).toLowerCase(Locale.ROOT);
		if (collapsed.isEmpty()) {
			return collapsed;
		}
		return Character.toUpperCase(collapsed.charAt(0)) + collapsed.substring(1);
	}

	/** {@code "bosnia and herzegovina"} -> {@code "Bosnia and Herzegovina"}. */
	public static String toTitleCase(String raw) {
		String[] words = collapseWhitespace(raw).toLowerCase(Locale.ROOT).split(" ");
		StringBuilder result = new StringBuilder();
		for (int i = 0; i < words.length; i++) {
			String word = words[i];
			if (i > 0) {
				result.append(' ');
			}
			result.append((i > 0 && LOWERCASE_WORDS.contains(word)) ? word : capitalizeParts(word));
		}
		return result.toString();
	}

	/** Distinct names to try against the provider, in order of preference. */
	public static Set<String> lookupCandidates(String raw) {
		Set<String> candidates = new LinkedHashSet<>();
		candidates.add(toSentenceCase(raw));
		candidates.add(toTitleCase(raw));
		return candidates;
	}

	// "guinea-bissau" -> "Guinea-Bissau"
	private static String capitalizeParts(String word) {
		StringBuilder out = new StringBuilder(word.length());
		boolean capitalizeNext = true;
		for (char c : word.toCharArray()) {
			out.append(capitalizeNext ? Character.toUpperCase(c) : c);
			capitalizeNext = (c == '-');
		}
		return out.toString();
	}

	private static String collapseWhitespace(String raw) {
		return (raw == null) ? "" : raw.trim().replaceAll("\\s+", " ");
	}

}
