package com.ncbaloop.countryinfo.integration.soap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ws.WebServiceMessage;
import org.springframework.ws.client.WebServiceClientException;
import org.springframework.ws.client.support.interceptor.ClientInterceptorAdapter;
import org.springframework.ws.context.MessageContext;

/**
 * Logs raw SOAP envelopes at DEBUG level only. Enable per environment with
 * {@code LOGGING_LEVEL_COM_NCBALOOP_COUNTRYINFO_INTEGRATION_SOAP=DEBUG} when troubleshooting
 * an upstream contract issue; payloads are never logged at INFO.
 */
public class SoapLoggingInterceptor extends ClientInterceptorAdapter {

	private static final Logger log = LoggerFactory.getLogger(SoapLoggingInterceptor.class);

	@Override
	public boolean handleRequest(MessageContext messageContext) throws WebServiceClientException {
		if (log.isDebugEnabled()) {
			log.debug("SOAP request: {}", asString(messageContext.getRequest()));
		}
		return true;
	}

	@Override
	public boolean handleResponse(MessageContext messageContext) throws WebServiceClientException {
		if (log.isDebugEnabled()) {
			log.debug("SOAP response: {}", asString(messageContext.getResponse()));
		}
		return true;
	}

	@Override
	public boolean handleFault(MessageContext messageContext) throws WebServiceClientException {
		log.warn("SOAP fault received: {}", asString(messageContext.getResponse()));
		return true;
	}

	private static String asString(WebServiceMessage message) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			message.writeTo(out);
			return out.toString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return "<unreadable: " + ex.getMessage() + ">";
		}
	}

}
