package com.ncbaloop.countryinfo.exception;

import org.springframework.http.HttpStatus;

/**
 * The external country-information provider could not serve the request. The {@link Reason}
 * maps to the HTTP status returned to our callers so they can tell a transient outage
 * (retry later) apart from a contract problem.
 */
public class UpstreamServiceException extends RuntimeException {

	public enum Reason {

		/** Circuit open, connection refused or 5xx from upstream. */
		UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
		/** Upstream did not answer within the configured read timeout. */
		TIMEOUT(HttpStatus.GATEWAY_TIMEOUT),
		/** Upstream answered with a SOAP fault or a payload we could not interpret. */
		BAD_RESPONSE(HttpStatus.BAD_GATEWAY);

		private final HttpStatus status;

		Reason(HttpStatus status) {
			this.status = status;
		}

		public HttpStatus status() {
			return this.status;
		}

	}

	private final Reason reason;

	private final String operation;

	public UpstreamServiceException(Reason reason, String operation, String message, Throwable cause) {
		super(message, cause);
		this.reason = reason;
		this.operation = operation;
	}

	public Reason getReason() {
		return this.reason;
	}

	public String getOperation() {
		return this.operation;
	}

}
