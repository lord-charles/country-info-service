package com.ncbaloop.countryinfo.integration.soap;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.apache.hc.core5.http.ConnectionRequestTimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.soap.client.SoapFaultClientException;

import com.ncbaloop.countryinfo.config.CacheNames;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException.Reason;
import com.ncbaloop.countryinfo.integration.CountryDetails;
import com.ncbaloop.countryinfo.integration.CountryDetails.LanguageDetails;
import com.ncbaloop.countryinfo.integration.CountryInfoClient;
import com.ncbaloop.countryinfo.integration.soap.generated.ArrayOftLanguage;
import com.ncbaloop.countryinfo.integration.soap.generated.CountryISOCode;
import com.ncbaloop.countryinfo.integration.soap.generated.CountryISOCodeResponse;
import com.ncbaloop.countryinfo.integration.soap.generated.FullCountryInfo;
import com.ncbaloop.countryinfo.integration.soap.generated.FullCountryInfoResponse;
import com.ncbaloop.countryinfo.integration.soap.generated.TCountryInfo;

/**
 * SOAP adapter for the oorsprong.org CountryInfoService.
 *
 * <p>Call path for each operation (outermost first):
 * <ol>
 * <li>{@code @Cacheable} - country data is near-static, so repeat lookups never leave the pod</li>
 * <li>{@code @Retry} - retries transient I/O and HTTP 5xx failures with exponential backoff and
 * jitter; its fallback translates the final failure into {@link UpstreamServiceException}</li>
 * <li>{@code @CircuitBreaker} - fails fast while the upstream is unhealthy</li>
 * <li>{@code @Bulkhead} - caps concurrent upstream calls so a slow provider cannot exhaust
 * request threads</li>
 * </ol>
 *
 * <p>The provider signals "not found" in-band (a text message in the result field, or an empty
 * ISO code) rather than with a SOAP fault, so results are validated here and mapped to
 * {@link Optional#empty()}. Not-found is a normal outcome and never trips the circuit breaker.
 */
@Component
public class SoapCountryInfoClient implements CountryInfoClient {

	public static final String RESILIENCE_INSTANCE = "countryInfoSoap";

	static final String OP_ISO_CODE = "CountryISOCode";

	static final String OP_FULL_COUNTRY_INFO = "FullCountryInfo";

	private static final Pattern ISO_ALPHA2 = Pattern.compile("^[A-Z]{2}$");

	private static final Logger log = LoggerFactory.getLogger(SoapCountryInfoClient.class);

	private final WebServiceTemplate webServiceTemplate;

	private final ObservationRegistry observationRegistry;

	public SoapCountryInfoClient(WebServiceTemplate countryInfoWebServiceTemplate,
			ObservationRegistry observationRegistry) {
		this.webServiceTemplate = countryInfoWebServiceTemplate;
		this.observationRegistry = observationRegistry;
	}

	@Override
	@Cacheable(cacheNames = CacheNames.ISO_CODES, key = "#countryName")
	@Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "isoCodeFallback")
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@Bulkhead(name = RESILIENCE_INSTANCE)
	public Optional<String> findIsoCode(String countryName) {
		CountryISOCode request = new CountryISOCode();
		request.setSCountryName(countryName);

		CountryISOCodeResponse response = call(OP_ISO_CODE, () -> (CountryISOCodeResponse) this.webServiceTemplate
			.marshalSendAndReceive(request));

		String result = (response != null) ? StringUtils.trimWhitespace(response.getCountryISOCodeResult()) : null;
		if (result != null && ISO_ALPHA2.matcher(result).matches()) {
			return Optional.of(result);
		}
		log.atInfo()
			.addKeyValue("operation", OP_ISO_CODE)
			.addKeyValue("countryName", countryName)
			.addKeyValue("upstreamResult", result)
			.log("Upstream did not resolve country name to an ISO code");
		return Optional.empty();
	}

	@Override
	@Cacheable(cacheNames = CacheNames.FULL_COUNTRY_INFO, key = "#isoCode")
	@Retry(name = RESILIENCE_INSTANCE, fallbackMethod = "fullCountryInfoFallback")
	@CircuitBreaker(name = RESILIENCE_INSTANCE)
	@Bulkhead(name = RESILIENCE_INSTANCE)
	public Optional<CountryDetails> findFullCountryInfo(String isoCode) {
		FullCountryInfo request = new FullCountryInfo();
		request.setSCountryISOCode(isoCode);

		FullCountryInfoResponse response = call(OP_FULL_COUNTRY_INFO,
				() -> (FullCountryInfoResponse) this.webServiceTemplate.marshalSendAndReceive(request));

		TCountryInfo info = (response != null) ? response.getFullCountryInfoResult() : null;
		if (info == null || !StringUtils.hasText(info.getSISOCode())) {
			log.atInfo()
				.addKeyValue("operation", OP_FULL_COUNTRY_INFO)
				.addKeyValue("isoCode", isoCode)
				.log("Upstream returned no country for ISO code");
			return Optional.empty();
		}
		return Optional.of(toDetails(info));
	}

	// Fallbacks run after retries are exhausted or when the circuit is open. They translate
	// low-level transport exceptions into a domain exception the web layer maps to 502/503/504.

	@SuppressWarnings("unused")
	private Optional<String> isoCodeFallback(String countryName, Throwable failure) {
		throw translate(OP_ISO_CODE, failure);
	}

	@SuppressWarnings("unused")
	private Optional<CountryDetails> fullCountryInfoFallback(String isoCode, Throwable failure) {
		throw translate(OP_FULL_COUNTRY_INFO, failure);
	}

	private <T> T call(String operation, Supplier<T> soapCall) {
		return Observation.createNotStarted("countryinfo.soap.client", this.observationRegistry)
			.contextualName("soap " + operation)
			.lowCardinalityKeyValue("operation", operation)
			.observe(soapCall);
	}

	private static UpstreamServiceException translate(String operation, Throwable failure) {
		if (failure instanceof UpstreamServiceException upstream) {
			return upstream;
		}
		Reason reason;
		String message;
		if (failure instanceof CallNotPermittedException) {
			reason = Reason.UNAVAILABLE;
			message = "Country information provider is temporarily unavailable (circuit open)";
		}
		else if (isTimeout(failure)) {
			reason = Reason.TIMEOUT;
			message = "Country information provider did not respond in time";
		}
		else if (failure instanceof SoapFaultClientException) {
			reason = Reason.BAD_RESPONSE;
			message = "Country information provider rejected the request";
		}
		else {
			reason = Reason.UNAVAILABLE;
			message = "Country information provider is unavailable";
		}
		log.atWarn()
			.addKeyValue("operation", operation)
			.addKeyValue("reason", reason)
			.addKeyValue("errorType", failure.getClass().getSimpleName())
			.setCause(failure)
			.log("Upstream SOAP call failed: {}", failure.getMessage());
		return new UpstreamServiceException(reason, operation, message, failure);
	}

	private static boolean isTimeout(Throwable failure) {
		Throwable root = NestedExceptionUtils.getMostSpecificCause(failure);
		return root instanceof SocketTimeoutException || root instanceof ConnectionRequestTimeoutException;
	}

	private static CountryDetails toDetails(TCountryInfo info) {
		ArrayOftLanguage languages = info.getLanguages();
		List<LanguageDetails> languageDetails = (languages == null) ? List.of()
				: languages.getTLanguage()
					.stream()
					.filter(language -> language != null && StringUtils.hasText(language.getSISOCode()))
					.map(language -> new LanguageDetails(language.getSISOCode().trim(), clean(language.getSName())))
					.toList();
		return new CountryDetails(clean(info.getSISOCode()), clean(info.getSName()), clean(info.getSCapitalCity()),
				clean(info.getSPhoneCode()), clean(info.getSContinentCode()), clean(info.getSCurrencyISOCode()),
				clean(info.getSCountryFlag()), languageDetails);
	}

	private static String clean(String value) {
		return StringUtils.hasText(value) ? value.trim() : null;
	}

}
