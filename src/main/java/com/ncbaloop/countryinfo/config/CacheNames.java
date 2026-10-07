package com.ncbaloop.countryinfo.config;

/**
 * Cache names, declared once so annotations and configuration cannot drift apart.
 * Sizes and TTLs live in {@code spring.cache.caffeine.spec}.
 */
public final class CacheNames {

	/** Country name -> ISO code lookups. */
	public static final String ISO_CODES = "countryIsoCodes";

	/** ISO code -> full country info lookups. */
	public static final String FULL_COUNTRY_INFO = "fullCountryInfo";

	private CacheNames() {
	}

}
