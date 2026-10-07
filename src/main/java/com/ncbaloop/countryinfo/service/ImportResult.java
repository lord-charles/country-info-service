package com.ncbaloop.countryinfo.service;

import com.ncbaloop.countryinfo.web.dto.CountryInfoResponse;

/**
 * Outcome of importing a country from the upstream provider.
 */
public record ImportResult(CountryInfoResponse country, Outcome outcome) {

	public enum Outcome {

		/** New record stored. */
		CREATED,
		/** Existing record (same ISO code) refreshed from upstream. */
		UPDATED,
		/** Upstream unavailable; the last stored copy is returned instead (graceful degradation). */
		SERVED_STALE

	}

}
