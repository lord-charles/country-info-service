package com.ncbaloop.countryinfo.web.filter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a correlation id and writes one structured access-log line per call.
 *
 * <p>The id is taken from an incoming {@code X-Request-ID} header (so a gateway or caller can
 * correlate across services) or generated, then placed in the MDC as {@code requestId} - every
 * log line for the request carries it - and echoed back on the response. OpenTelemetry adds
 * {@code traceId}/{@code spanId} to the MDC alongside it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

	public static final String REQUEST_ID_HEADER = "X-Request-ID";

	public static final String REQUEST_ID_MDC_KEY = "requestId";

	// Only accept safe ids from callers to prevent log injection.
	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");

	private static final Logger log = LoggerFactory.getLogger(RequestLoggingFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
		MDC.put(REQUEST_ID_MDC_KEY, requestId);
		response.setHeader(REQUEST_ID_HEADER, requestId);
		long start = System.nanoTime();
		try {
			chain.doFilter(request, response);
		}
		finally {
			long durationMs = (System.nanoTime() - start) / 1_000_000;
			log.atInfo()
				.addKeyValue("http.method", request.getMethod())
				.addKeyValue("http.path", request.getRequestURI())
				.addKeyValue("http.status", response.getStatus())
				.addKeyValue("durationMs", durationMs)
				.log("{} {} -> {} ({} ms)", request.getMethod(), request.getRequestURI(), response.getStatus(),
						durationMs);
			MDC.remove(REQUEST_ID_MDC_KEY);
		}
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Probes and scrapes are high-frequency noise; they are covered by metrics instead.
		return request.getRequestURI().startsWith("/actuator");
	}

	private static String resolveRequestId(String incoming) {
		return (incoming != null && SAFE_ID.matcher(incoming).matches()) ? incoming : UUID.randomUUID().toString();
	}

}
