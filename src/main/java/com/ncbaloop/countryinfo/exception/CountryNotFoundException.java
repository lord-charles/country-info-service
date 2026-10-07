package com.ncbaloop.countryinfo.exception;

/**
 * The upstream provider does not recognise the requested country name or ISO code.
 */
public class CountryNotFoundException extends RuntimeException {

	private final String query;

	public CountryNotFoundException(String query) {
		super("No country found matching '" + query + "'");
		this.query = query;
	}

	public String getQuery() {
		return this.query;
	}

}
