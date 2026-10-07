package com.ncbaloop.countryinfo.web.error;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.validation.ConstraintViolationException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.ncbaloop.countryinfo.exception.CountryNotFoundException;
import com.ncbaloop.countryinfo.exception.ResourceNotFoundException;
import com.ncbaloop.countryinfo.exception.UpstreamServiceException;
import com.ncbaloop.countryinfo.web.filter.RequestLoggingFilter;

/**
 * Translates every failure into an RFC 9457 {@code application/problem+json} body with a
 * stable {@code type} URI, a human-readable {@code detail} and the {@code requestId} so a
 * caller can quote it to support. Internal details (stack traces, SQL, upstream payloads) are
 * logged, never returned.
 *
 * <p>Extending {@link ResponseEntityExceptionHandler} covers Spring MVC's own exceptions
 * (malformed JSON, wrong media type, unsupported method, ...) with correct status codes.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	private static final String TYPE_BASE = "urn:problem-type:country-info:";

	@ExceptionHandler(CountryNotFoundException.class)
	ProblemDetail handleCountryNotFound(CountryNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "country-not-found", "Country not found", ex.getMessage());
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	ProblemDetail handleResourceNotFound(ResourceNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "resource-not-found", "Resource not found", ex.getMessage());
	}

	@ExceptionHandler(UpstreamServiceException.class)
	ResponseEntity<ProblemDetail> handleUpstream(UpstreamServiceException ex) {
		HttpStatus status = ex.getReason().status();
		ProblemDetail body = problem(status, "upstream-" + ex.getReason().name().toLowerCase().replace('_', '-'),
				"Upstream service error", ex.getMessage() + ". Please retry shortly.");
		body.setProperty("operation", ex.getOperation());
		HttpHeaders headers = new HttpHeaders();
		if (status == HttpStatus.SERVICE_UNAVAILABLE) {
			headers.set(HttpHeaders.RETRY_AFTER, "30");
		}
		return ResponseEntity.status(status).headers(headers).body(body);
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException ex) {
		log.atInfo().addKeyValue("entity", ex.getPersistentClassName()).log("Optimistic lock conflict");
		return problem(HttpStatus.CONFLICT, "concurrent-modification", "Concurrent modification",
				"The record was modified by another request. Fetch the latest version and retry.");
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	ProblemDetail handleDataIntegrity(DataIntegrityViolationException ex) {
		log.atWarn().setCause(ex).log("Data integrity violation");
		return problem(HttpStatus.CONFLICT, "data-conflict", "Data conflict",
				"The request conflicts with existing data (e.g. duplicate language ISO code).");
	}

	@ExceptionHandler(PropertyReferenceException.class)
	ProblemDetail handleBadSort(PropertyReferenceException ex) {
		return problem(HttpStatus.BAD_REQUEST, "invalid-sort", "Invalid sort parameter",
				"Cannot sort by '" + ex.getPropertyName() + "'.");
	}

	@ExceptionHandler(ConstraintViolationException.class)
	ProblemDetail handleConstraintViolation(ConstraintViolationException ex) {
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
				"One or more parameters are invalid.");
		body.setProperty("errors", ex.getConstraintViolations()
			.stream()
			.map(v -> Map.of("field", String.valueOf(v.getPropertyPath()), "message", v.getMessage()))
			.toList());
		return body;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		log.atError().setCause(ex).log("Unhandled exception");
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
				"An unexpected error occurred. Quote the requestId when contacting support.");
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
				"One or more fields are invalid.");
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", String.valueOf(error.getDefaultMessage())))
			.toList();
		body.setProperty("errors", errors);
		return ResponseEntity.badRequest().headers(headers).body(body);
	}

	@Override
	protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		ProblemDetail body = problem(HttpStatus.BAD_REQUEST, "validation-error", "Validation failed",
				"One or more parameters are invalid.");
		body.setProperty("errors", ex.getAllErrors()
			.stream()
			.map(error -> Map.of("message", String.valueOf(error.getDefaultMessage())))
			.toList());
		return ResponseEntity.badRequest().headers(headers).body(body);
	}

	@Override
	protected ResponseEntity<Object> createResponseEntity(Object body, HttpHeaders headers, HttpStatusCode statusCode,
			WebRequest request) {
		// Decorate Spring MVC's built-in problem details with our common properties.
		if (body instanceof ProblemDetail problemDetail) {
			enrich(problemDetail);
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private static ProblemDetail problem(HttpStatus status, String type, String title, String detail) {
		ProblemDetail body = ProblemDetail.forStatusAndDetail(status, detail);
		body.setType(URI.create(TYPE_BASE + type));
		body.setTitle(title);
		return enrich(body);
	}

	private static ProblemDetail enrich(ProblemDetail body) {
		body.setProperty("timestamp", Instant.now());
		String requestId = MDC.get(RequestLoggingFilter.REQUEST_ID_MDC_KEY);
		if (requestId != null) {
			body.setProperty("requestId", requestId);
		}
		return body;
	}

}
