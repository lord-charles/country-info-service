package com.ncbaloop.countryinfo.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class CountryNameFormatterTest {

	@ParameterizedTest
	@CsvSource({ "kenya,Kenya", "KENYA,Kenya", "tAnZaNiA,Tanzania", "'  kenya  ',Kenya", "united states,United states" })
	void toSentenceCase(String input, String expected) {
		assertThat(CountryNameFormatter.toSentenceCase(input)).isEqualTo(expected);
	}

	@ParameterizedTest
	@CsvSource({ "united states,United States", "BOSNIA AND HERZEGOVINA,Bosnia and Herzegovina",
			"guinea-bissau,Guinea-Bissau", "'south   africa',South Africa" })
	void toTitleCase(String input, String expected) {
		assertThat(CountryNameFormatter.toTitleCase(input)).isEqualTo(expected);
	}

	@Test
	void lookupCandidatesTriesSentenceCaseFirstThenTitleCase() {
		assertThat(CountryNameFormatter.lookupCandidates("united states")).containsExactly("United states",
				"United States");
	}

	@Test
	void lookupCandidatesAreDeduplicatedForSingleWordNames() {
		assertThat(CountryNameFormatter.lookupCandidates("kenya")).containsExactly("Kenya");
	}

}
