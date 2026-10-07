package com.ncbaloop.countryinfo.integration;

import java.util.Optional;

/**
 * Port for the external country-information provider. The service layer depends on this
 * interface only, which keeps it testable and lets the SOAP adapter be swapped (e.g. for a
 * REST provider) without touching business logic.
 */
public interface CountryInfoClient {

	/**
	 * Resolve a country name to its ISO 3166-1 alpha-2 code.
	 * @param countryName the exact name the provider expects (case-sensitive upstream)
	 * @return the ISO code, or empty when the provider does not know the name
	 */
	Optional<String> findIsoCode(String countryName);

	/**
	 * Fetch the full country record for an ISO code.
	 * @return the details, or empty when the provider has no record for the code
	 */
	Optional<CountryDetails> findFullCountryInfo(String isoCode);

}
