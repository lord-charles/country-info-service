package com.ncbaloop.countryinfo.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.oxm.jaxb.Jaxb2Marshaller;
import org.springframework.ws.client.core.WebServiceTemplate;
import org.springframework.ws.transport.http.HttpComponents5ClientFactory;
import org.springframework.ws.transport.http.SimpleHttpComponents5MessageSender;

import com.ncbaloop.countryinfo.integration.soap.SoapLoggingInterceptor;

/**
 * Wires the Spring-WS client for the CountryInfo SOAP service.
 *
 * <p>The HTTP transport uses a pooled Apache HttpClient 5 with explicit connect/read
 * timeouts. The pool size is raised per route because the upstream is a single host and
 * the HttpClient default (2 connections per route) would serialise requests under load.
 */
@Configuration(proxyBeanMethods = false)
public class SoapClientConfig {

	static final String GENERATED_PACKAGE = "com.ncbaloop.countryinfo.integration.soap.generated";

	@Bean
	public Jaxb2Marshaller countryInfoMarshaller() {
		Jaxb2Marshaller marshaller = new Jaxb2Marshaller();
		marshaller.setPackagesToScan(GENERATED_PACKAGE);
		return marshaller;
	}

	@Bean
	public SimpleHttpComponents5MessageSender countryInfoMessageSender(SoapClientProperties properties) {
		HttpComponents5ClientFactory factory = new HttpComponents5ClientFactory();
		factory.setConnectionTimeout(properties.connectTimeout());
		factory.setReadTimeout(properties.readTimeout());
		factory.addConnectionManagerBuilderCustomizer(builder -> builder
				.setMaxConnTotal(properties.maxConnections())
				.setMaxConnPerRoute(properties.maxConnections()));
		// Spring WS sets Content-Length/Transfer-Encoding itself; strip HttpClient's copies or the
		// request fails with "Content-Length header already present".
		factory.addClientBuilderCustomizer(builder -> builder
			.addRequestInterceptorFirst(new HttpComponents5ClientFactory.RemoveSoapHeadersInterceptor()));
		return new SimpleHttpComponents5MessageSender(factory);
	}

	@Bean
	public WebServiceTemplate countryInfoWebServiceTemplate(Jaxb2Marshaller countryInfoMarshaller,
			SimpleHttpComponents5MessageSender countryInfoMessageSender, SoapClientProperties properties) {
		WebServiceTemplate template = new WebServiceTemplate(countryInfoMarshaller);
		template.setDefaultUri(properties.endpoint());
		template.setMessageSender(countryInfoMessageSender);
		template.setInterceptors(new SoapLoggingInterceptor[] { new SoapLoggingInterceptor() });
		return template;
	}

}
