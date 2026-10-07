package com.ncbaloop.countryinfo.integration;

import java.io.IOException;

import javax.xml.transform.Source;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.test.client.MockWebServiceServer;
import org.springframework.xml.transform.StringSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.ncbaloop.countryinfo.integration.soap.SoapCountryInfoClient;
import com.ncbaloop.countryinfo.repository.CountryInfoRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.ws.test.client.RequestMatchers.anything;
import static org.springframework.ws.test.client.RequestMatchers.payload;
import static org.springframework.ws.test.client.ResponseCreators.withException;
import static org.springframework.ws.test.client.ResponseCreators.withPayload;

/**
 * End-to-end: HTTP -> controller -> service -> SOAP client (mocked at the transport level, so
 * marshalling, caching, retry and circuit breaking are all real) -> MySQL (Testcontainers).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CountryInfoIntegrationTest {

	@Container
	@ServiceConnection
	static MySQLContainer mysql = new MySQLContainer("mysql:8.4");

	private static final String NS = "http://www.oorsprong.org/websamples.countryinfo";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private WebServiceTemplate countryInfoWebServiceTemplate;

	@Autowired
	private CountryInfoRepository repository;

	@Autowired
	private CacheManager cacheManager;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	private MockWebServiceServer soapServer;

	@BeforeEach
	void setUp() {
		this.soapServer = MockWebServiceServer.createServer(this.countryInfoWebServiceTemplate);
		this.repository.deleteAll();
		clearCaches();
		this.circuitBreakerRegistry.circuitBreaker(SoapCountryInfoClient.RESILIENCE_INSTANCE).reset();
	}

	@AfterEach
	void verifySoapExpectations() {
		this.soapServer.verify();
	}

	@Test
	void importsCountryAndSupportsFullCrudLifecycle() throws Exception {
		expectIsoCodeLookup("Kenya", "soap/iso-code-KE.xml");
		expectFullCountryInfo("KE", "soap/full-country-info-KE.xml");

		String location = importCountry("kenya").andExpect(status().isCreated())
			.andExpect(jsonPath("$.isoCode").value("KE"))
			.andExpect(jsonPath("$.capitalCity").value("Nairobi"))
			.andExpect(jsonPath("$.languages.length()").value(2))
			.andReturn()
			.getResponse()
			.getHeader("Location");
		assertThat(location).isNotNull();
		long id = Long.parseLong(location.substring(location.lastIndexOf('/') + 1));

		this.mockMvc.perform(get("/api/v1/countries"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalElements").value(1))
			.andExpect(jsonPath("$.content[0].name").value("Kenya"));

		this.mockMvc.perform(get("/api/v1/countries/{id}", id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currencyIsoCode").value("KES"));

		this.mockMvc.perform(put("/api/v1/countries/{id}", id).contentType(MediaType.APPLICATION_JSON).content("""
				{"name": "Kenya", "capitalCity": "Nairobi City", "phoneCode": "254", "continentCode": "AF",
				 "currencyIsoCode": "KES", "languages": [{"isoCode": "swa", "name": "Kiswahili"}]}"""))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.capitalCity").value("Nairobi City"))
			.andExpect(jsonPath("$.languages.length()").value(1))
			.andExpect(jsonPath("$.languages[0].name").value("Kiswahili"))
			.andExpect(jsonPath("$.version").value(1));

		this.mockMvc.perform(delete("/api/v1/countries/{id}", id)).andExpect(status().isNoContent());
		this.mockMvc.perform(get("/api/v1/countries/{id}", id)).andExpect(status().isNotFound());
	}

	@Test
	void reimportIsIdempotentAndServedFromCache() throws Exception {
		expectIsoCodeLookup("Kenya", "soap/iso-code-KE.xml");
		expectFullCountryInfo("KE", "soap/full-country-info-KE.xml");

		importCountry("kenya").andExpect(status().isCreated());
		// Second call: no further SOAP expectations registered, so it must be answered from cache.
		importCountry("KENYA").andExpect(status().isOk()).andExpect(header().string("X-Data-Source", "upstream"));

		assertThat(this.repository.count()).isEqualTo(1);
	}

	@Test
	void unknownCountryReturns404() throws Exception {
		expectIsoCodeLookup("Narnia", "soap/iso-code-not-found.xml");

		importCountry("narnia").andExpect(status().isNotFound())
			.andExpect(jsonPath("$.type").value("urn:problem-type:country-info:country-not-found"));
	}

	@Test
	void retriesTransientFailuresThenReturns503() throws Exception {
		for (int attempt = 0; attempt < 3; attempt++) {
			this.soapServer.expect(anything()).andRespond(withException(new IOException("Connection reset")));
		}

		importCountry("kenya").andExpect(status().isServiceUnavailable())
			.andExpect(header().string("Retry-After", "30"));
	}

	@Test
	void recoversWhenARetrySucceeds() throws Exception {
		this.soapServer.expect(anything()).andRespond(withException(new IOException("Connection reset")));
		expectIsoCodeLookup("Kenya", "soap/iso-code-KE.xml");
		expectFullCountryInfo("KE", "soap/full-country-info-KE.xml");

		importCountry("kenya").andExpect(status().isCreated());
	}

	@Test
	void openCircuitFailsFastAndServesStoredCopy() throws Exception {
		expectIsoCodeLookup("Kenya", "soap/iso-code-KE.xml");
		expectFullCountryInfo("KE", "soap/full-country-info-KE.xml");
		importCountry("kenya").andExpect(status().isCreated());

		clearCaches();
		this.circuitBreakerRegistry.circuitBreaker(SoapCountryInfoClient.RESILIENCE_INSTANCE).transitionToOpenState();

		// No SOAP call is made while the circuit is open; stored data is returned instead.
		importCountry("kenya").andExpect(status().isOk())
			.andExpect(header().string("X-Data-Source", "local-store"))
			.andExpect(jsonPath("$.isoCode").value("KE"));

		// A country we have never stored cannot be served: fail fast with 503.
		importCountry("tanzania").andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.detail").value(containsString("circuit open")));
	}

	@Test
	void exposesHealthProbesAndPrometheusMetrics() throws Exception {
		this.mockMvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
		this.mockMvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
		String metrics = this.mockMvc.perform(get("/actuator/prometheus"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(metrics).contains("resilience4j_circuitbreaker_state");
	}

	@Test
	void rejectsUnknownSortProperty() throws Exception {
		this.mockMvc.perform(get("/api/v1/countries").param("sort", "doesNotExist"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.title").value("Invalid sort parameter"));
	}

	private ResultActions importCountry(String name) throws Exception {
		String body = "{\"name\": \"" + name + "\"}";
		return this.mockMvc.perform(post("/api/v1/countries").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private void expectIsoCodeLookup(String name, String responseResource) throws IOException {
		this.soapServer
			.expect(payload(xml("<CountryISOCode xmlns=\"" + NS + "\"><sCountryName>" + name
					+ "</sCountryName></CountryISOCode>")))
			.andRespond(withPayload(new ClassPathResource(responseResource)));
	}

	private void expectFullCountryInfo(String isoCode, String responseResource) throws IOException {
		this.soapServer
			.expect(payload(xml("<FullCountryInfo xmlns=\"" + NS + "\"><sCountryISOCode>" + isoCode
					+ "</sCountryISOCode></FullCountryInfo>")))
			.andRespond(withPayload(new ClassPathResource(responseResource)));
	}

	private static Source xml(String content) {
		return new StringSource(content);
	}

	private void clearCaches() {
		this.cacheManager.getCacheNames().forEach(name -> this.cacheManager.getCache(name).clear());
	}

}
