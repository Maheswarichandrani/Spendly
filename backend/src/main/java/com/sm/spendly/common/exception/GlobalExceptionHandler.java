package com.sm.spendly.common.exception;

import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.sm.spendly.common.logging.RequestIdFilter;
import com.sm.spendly.common.persistence.ConstraintNames;

/**
 * Turns every exception that escapes a controller into an RFC 9457 Problem Details body
 * with a stable {@code code}.
 *
 * <p>Two paths lead here:
 * <ul>
 * <li>Spring MVC's own exceptions (bad JSON, unknown route, validation...) are handled by
 * the inherited {@link ResponseEntityExceptionHandler} methods, which all funnel through
 * {@link #handleExceptionInternal}. We override that one hook to add our fields.</li>
 * <li>Our exceptions and the catch-all are handled by the {@code @ExceptionHandler}
 * methods below. Spring picks the handler whose exception type is the closest match, so
 * {@code Exception.class} only gets what nothing else claims.</li>
 * </ul>
 *
 * <p>Rule: {@code detail} is always text we wrote. Exception messages from the framework,
 * JDBC or Jackson can contain SQL, class names or the rejected input, so they are logged,
 * never returned.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// ---------------------------------------------------------------- our exceptions

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex, HttpServletRequest request) {
		ErrorCode code = ex.getErrorCode();
		log.debug("API error code={} path={}", code, request.getRequestURI());
		return toResponse(problem(code, ex.getMessage(), request.getRequestURI()));
	}

	/**
	 * Safety net for races the service could not prevent (two requests inserting the same
	 * unique value at once). Services should check first and throw a precise ConflictException.
	 */
	@ExceptionHandler(DataIntegrityViolationException.class)
	ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException ex,
			HttpServletRequest request) {
		log.warn("Data integrity violation code={} path={} constraint={}", ErrorCode.CONFLICT,
				request.getRequestURI(), ConstraintNames.of(ex));
		return toResponse(problem(ErrorCode.CONFLICT, "The request conflicts with existing data.",
				request.getRequestURI()));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request) {
		log.error("Unexpected error code={} path={}", ErrorCode.INTERNAL_ERROR, request.getRequestURI(), ex);
		return toResponse(problem(ErrorCode.INTERNAL_ERROR, "Unexpected error.", request.getRequestURI()));
	}

	// ------------------------------------------------------- Spring MVC's exceptions

	/**
	 * Every inherited handler (400 validation, 404 route, 405, 415, ...) ends here with a
	 * ProblemDetail body already built by Spring. We replace its detail with our own safe
	 * text, add {@code code} and {@code instance}, and add field errors for validation.
	 */
	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		ErrorCode code = codeFor(ex, statusCode);
		String path = path(request);
		ProblemDetail pd = problem(code, detailFor(ex, code), path);
		// Keep Spring's status (e.g. a 503 it raised) rather than forcing our code's status.
		pd.setStatus(statusCode.value());

		List<ValidationError> errors = validationErrors(ex);
		if (!errors.isEmpty()) {
			pd.setDetail(errors.size() == 1 ? "1 field is invalid." : errors.size() + " fields are invalid.");
			pd.setProperty("errors", errors);
		}

		if (statusCode.is5xxServerError()) {
			log.error("Framework error code={} path={}", code, path, ex);
		}
		else {
			log.debug("Request rejected code={} path={} reason={}", code, path, ex.getClass().getSimpleName());
		}
		return super.handleExceptionInternal(ex, pd, headers, statusCode, request);
	}

	private static ErrorCode codeFor(Exception ex, HttpStatusCode status) {
		if (ex instanceof MethodArgumentNotValidException || ex instanceof HandlerMethodValidationException) {
			return ErrorCode.VALIDATION_FAILED;
		}
		if (ex instanceof HttpMessageNotReadableException) {
			return ErrorCode.MALFORMED_REQUEST;
		}
		if (ex instanceof TypeMismatchException || ex instanceof MissingServletRequestParameterException) {
			return ErrorCode.INVALID_PARAMETER;
		}
		if (ex instanceof NoResourceFoundException) {
			return ErrorCode.NOT_FOUND;
		}
		if (ex instanceof HttpRequestMethodNotSupportedException) {
			return ErrorCode.METHOD_NOT_ALLOWED;
		}
		if (ex instanceof HttpMediaTypeNotAcceptableException) {
			return ErrorCode.NOT_ACCEPTABLE;
		}
		if (ex instanceof HttpMediaTypeNotSupportedException) {
			return ErrorCode.UNSUPPORTED_MEDIA_TYPE;
		}
		if (ex instanceof MaxUploadSizeExceededException) {
			return ErrorCode.PAYLOAD_TOO_LARGE;
		}
		return status.is5xxServerError() ? ErrorCode.INTERNAL_ERROR : ErrorCode.BAD_REQUEST;
	}

	/** Safe, hand-written detail. Spring's own text can echo the rejected value. */
	private static String detailFor(Exception ex, ErrorCode code) {
		return switch (code) {
			case MALFORMED_REQUEST -> "The request body could not be read.";
			case INVALID_PARAMETER -> parameterName(ex)
					.map(name -> "Parameter '" + name + "' is missing or invalid.")
					.orElse("A request parameter is missing or invalid.");
			case NOT_FOUND -> "No resource exists at this path.";
			case METHOD_NOT_ALLOWED -> "This HTTP method is not supported here.";
			case NOT_ACCEPTABLE -> "The requested response format is not supported.";
			case UNSUPPORTED_MEDIA_TYPE -> "The request content type is not supported.";
			case PAYLOAD_TOO_LARGE -> "The request is too large.";
			case INTERNAL_ERROR -> "Unexpected error.";
			default -> code.title() + ".";
		};
	}

	private static Optional<String> parameterName(Exception ex) {
		if (ex instanceof TypeMismatchException tme && tme.getPropertyName() != null) {
			return Optional.of(tme.getPropertyName());
		}
		if (ex instanceof MissingServletRequestParameterException mpe) {
			return Optional.of(mpe.getParameterName());
		}
		return Optional.empty();
	}

	private static List<ValidationError> validationErrors(Exception ex) {
		if (ex instanceof MethodArgumentNotValidException manv) {
			// Body validation: one entry per invalid field of the request record.
			return manv.getBindingResult().getAllErrors().stream()
				.map(error -> new ValidationError(
						error instanceof FieldError fe ? fe.getField() : null,
						error.getDefaultMessage()))
				.sorted(BY_FIELD)
				.toList();
		}
		if (ex instanceof HandlerMethodValidationException hmve) {
			// Constraints on @PathVariable / @RequestParam: the "field" is the parameter name.
			return hmve.getParameterValidationResults().stream()
				.flatMap(result -> result.getResolvableErrors().stream()
					.map(error -> new ValidationError(result.getMethodParameter().getParameterName(),
							error.getDefaultMessage())))
				.sorted(BY_FIELD)
				.toList();
		}
		return List.of();
	}

	private static final Comparator<ValidationError> BY_FIELD = Comparator
		.comparing(ValidationError::field, Comparator.nullsFirst(Comparator.naturalOrder()))
		.thenComparing(ValidationError::message, Comparator.nullsFirst(Comparator.naturalOrder()));

	// ---------------------------------------------------------------- helpers

	private static ProblemDetail problem(ErrorCode code, String detail, String path) {
		ProblemDetail pd = ProblemDetail.forStatusAndDetail(code.status(), detail);
		pd.setTitle(code.title());
		pd.setInstance(URI.create(path));
		pd.setProperty("code", code.name());
		// Same id as the X-Request-Id header and the log lines, so a user can quote it
		// from an error screen and we can find the whole request in the logs.
		String requestId = MDC.get(RequestIdFilter.MDC_KEY);
		if (requestId != null) {
			pd.setProperty("requestId", requestId);
		}
		return pd;
	}

	private static ResponseEntity<ProblemDetail> toResponse(ProblemDetail pd) {
		return ResponseEntity.status(pd.getStatus()).body(pd);
	}

	private static String path(WebRequest request) {
		return request instanceof ServletWebRequest swr ? swr.getRequest().getRequestURI() : "/";
	}

}
