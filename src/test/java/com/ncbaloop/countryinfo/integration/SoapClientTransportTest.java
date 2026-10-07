package com.ncbaloop.countryinfo.integration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.WebServiceIOException;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.SimpleHttpComponents5MessageSender;

import com.ncbaloop.countryinfo.config.SoapClientConfig;
import com.ncbaloop.countryinfo.config.SoapClientProperties;
import com.ncbaloop.countryinfo.integration.soap.SoapCountryInfoClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP transport (pooled HttpClient 5, headers, timeouts) against an
 * in-process server. MockWebServiceServer replaces the transport, so it cannot catch
 * transport-level misconfiguration; this test can.
 */
class SoapClientTransportTest {

	private HttpServer server;

	private final AtomicReference<String> responseResource = new AtomicReference<>();

	private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

	private volatile long responseDelayMs;

	private SoapCountryInfoClient client;

	@BeforeEach
	void setUp() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/CountryInfoService.wso", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				this.lastRequestBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
			}
			sleep(this.responseDelayMs);
			byte[] body = soapEnvelope(this.responseResource.get());
			exchange.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(body);
			}
		});
		this.server.start();

		String endpoint = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/CountryInfoService.wso";
		SoapClientProperties properties = new SoapClientProperties(endpoint, Duration.ofSeconds(1),
				Duration.ofMillis(500), 10);
		SoapClientConfig config = new SoapClientConfig();
		Jaxb2Marshaller marshaller = config.countryInfoMarshaller();
		marshaller.afterPropertiesSet();
		SimpleHttpComponents5MessageSender sender = config.countryInfoMessageSender(properties);
		WebServiceTemplate template = config.countryInfoWebServiceTemplate(marshaller, sender, properties);
		this.client = new SoapCountryInfoClient(template, ObservationRegistry.NOOP);
	}

	@AfterEach
	void tearDown() {
		this.server.stop(0);
	}

	@Test
	void resolvesIsoCodeOverRealHttp() {
		this.responseResource.set("soap/iso-code-KE.xml");

		assertThat(this.client.findIsoCode("Kenya")).contains("KE");
		assertThat(this.lastRequestBody.get()).contains("<ns2:sCountryName>Kenya</ns2:sCountryName>");
	}

	@Test
	void mapsInBandNotFoundToEmpty() {
		this.responseResource.set("soap/iso-code-not-found.xml");

		assertThat(this.client.findIsoCode("Narnia")).isEmpty();
	}

	@Test
	void mapsFullCountryInfoIncludingLanguages() {
		this.responseResource.set("soap/full-country-info-KE.xml");

		CountryDetails details = this.client.findFullCountryInfo("KE").orElseThrow();
		assertThat(details.name()).isEqualTo("Kenya");
		assertThat(details.capitalCity()).isEqualTo("Nairobi");
		assertThat(details.languages()).extracting(CountryDetails.LanguageDetails::name)
			.containsExactly("English", "Swahili");
	}

	@Test
	void readTimeoutSurfacesAsIoException() {
		this.responseResource.set("soap/iso-code-KE.xml");
		this.responseDelayMs = 1_500;

		assertThatThrownBy(() -> this.client.findIsoCode("Kenya")).isInstanceOf(WebServiceIOException.class)
			.hasMessageContaining("timed out");
	}

	private static byte[] soapEnvelope(String payloadResource) throws IOException {
		String payload = new ClassPathResource(payloadResource).getContentAsString(StandardCharsets.UTF_8);
		return ("<?xml version=\"1.0\" encoding=\"utf-8\"?>"
				+ "<soap:Envelope xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\"><soap:Body>" + payload
				+ "</soap:Body></soap:Envelope>")
			.getBytes(StandardCharsets.UTF_8);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
