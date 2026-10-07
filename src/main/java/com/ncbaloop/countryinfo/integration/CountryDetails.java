package com.ncbaloop.countryinfo.integration;

import java.util.List;

/**
 * Anti-corruption layer: an immutable, internal view of the upstream {@code tCountryInfo}
 * type. Nothing outside the integration package depends on the generated JAXB classes, so a
 * change in the SOAP contract is contained to the SOAP adapter.
 */
public record CountryDetails(
		String isoCode,
		String name,
		String capitalCity,
		String phoneCode,
		String continentCode,
		String currencyIsoCode,
		String countryFlag,
		List<LanguageDetails> languages) {

	public CountryDetails {
		languages = languages == null ? List.of() : List.copyOf(languages);
	}

	public record LanguageDetails(String isoCode, String name) {
	}

}
