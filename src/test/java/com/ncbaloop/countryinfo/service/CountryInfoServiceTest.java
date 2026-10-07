package com.ncbaloop.countryinfo.service;

import java.util.List;
import java.util.Optional;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import com.ncbaloop.countryinfo.exception.CountryNotFoundException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException.Reason;
import com.ncbaloop.countryinfo.integration.CountryDetails;
import com.ncbaloop.countryinfo.integration.CountryDetails.LanguageDetails;
import com.ncbaloop.countryinfo.integration.CountryInfoClient;
import com.ncbaloop.countryinfo.model.CountryInfo;
import com.ncbaloop.countryinfo.repository.CountryInfoRepository;
import com.ncbaloop.countryinfo.service.ImportResult.Outcome;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CountryInfoServiceTest {

	private static final CountryDetails US = new CountryDetails("US", "United States", "Washington", "1", "AM", "USD",
			"http://flags/USA.jpg", List.of(new LanguageDetails("eng", "English")));

	@Mock
	private CountryInfoClient client;

	@Mock
	private CountryInfoRepository repository;

	private CountryInfoService service;

	@BeforeEach
	void setUp() {
		this.service = new CountryInfoService(this.client, this.repository, new CountryInfoMapper(),
				new TransactionTemplate(mock(PlatformTransactionManager.class)), new SimpleMeterRegistry());
	}

	@Test
	void fallsBackToTitleCaseWhenSentenceCaseIsUnknownUpstream() {
		given(this.client.findIsoCode("United states")).willReturn(Optional.empty());
		given(this.client.findIsoCode("United States")).willReturn(Optional.of("US"));
		given(this.client.findFullCountryInfo("US")).willReturn(Optional.of(US));
		given(this.repository.findByIsoCode("US")).willReturn(Optional.empty());
		given(this.repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

		ImportResult result = this.service.importCountry("united states");

		assertThat(result.outcome()).isEqualTo(Outcome.CREATED);
		assertThat(result.country().isoCode()).isEqualTo("US");
		assertThat(result.country().languages()).extracting("name").containsExactly("English");
	}

	@Test
	void reimportingAnExistingCountryUpdatesIt() {
		given(this.client.findIsoCode("United states")).willReturn(Optional.of("US"));
		given(this.client.findFullCountryInfo("US")).willReturn(Optional.of(US));
		given(this.repository.findByIsoCode("US")).willReturn(Optional.of(new CountryInfo("US")));
		given(this.repository.saveAndFlush(any())).willAnswer(invocation -> invocation.getArgument(0));

		assertThat(this.service.importCountry("united states").outcome()).isEqualTo(Outcome.UPDATED);
	}

	@Test
	void unknownCountryRaisesNotFoundWithoutCallingFullInfo() {
		given(this.client.findIsoCode(anyString())).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.importCountry("narnia")).isInstanceOf(CountryNotFoundException.class)
			.hasMessageContaining("Narnia");
		verify(this.client, never()).findFullCountryInfo(anyString());
	}

	@Test
	void servesStoredCopyWhenUpstreamIsUnavailable() {
		CountryInfo stored = new CountryInfo("KE");
		stored.setName("Kenya");
		given(this.client.findIsoCode("Kenya"))
			.willThrow(new UpstreamServiceException(Reason.UNAVAILABLE, "CountryISOCode", "down", null));
		given(this.repository.findFirstByNameIgnoreCase("Kenya")).willReturn(Optional.of(stored));

		ImportResult result = this.service.importCountry("kenya");

		assertThat(result.outcome()).isEqualTo(Outcome.SERVED_STALE);
		assertThat(result.country().isoCode()).isEqualTo("KE");
	}

	@Test
	void propagatesUpstreamFailureWhenNothingIsStored() {
		given(this.client.findIsoCode("Kenya"))
			.willThrow(new UpstreamServiceException(Reason.TIMEOUT, "CountryISOCode", "slow", null));
		given(this.repository.findFirstByNameIgnoreCase("Kenya")).willReturn(Optional.empty());

		assertThatThrownBy(() -> this.service.importCountry("kenya")).isInstanceOf(UpstreamServiceException.class);
	}

}
