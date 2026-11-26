package com.sm.spendly.common.web;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.common.exception.ValidationError;

/**
 * Documentation only: the shape of every error body, as written by GlobalExceptionHandler
 * (Spring's ProblemDetail plus our "code", "requestId" and "errors"). No code builds this
 * record. It exists so Swagger can show the frontend one exact schema. If the handler
 * gains a field, add it here too.
 */
@Schema(name = "ApiProblem", description = "RFC 9457 Problem Details. Switch on `code`, never on `title` or `detail`.")
public record ApiProblem(
		@Schema(example = "about:blank") String type,
		@Schema(example = "Validation failed") String title,
		@Schema(example = "400") int status,
		@Schema(description = "Human-readable, safe to show the user.", example = "Request has invalid fields.") String detail,
		@Schema(example = "/api/v1/auth/register") String instance,
		@Schema(description = "Stable machine-readable error code.", example = "VALIDATION_FAILED") String code,
		@Schema(description = "Same id as the X-Request-Id response header; quote it in bug reports.",
				example = "7ae20fcd-8f9c-43df-8e7c-1fe487940a64") String requestId,
		@Schema(description = "Only for VALIDATION_FAILED: one entry per invalid field.", nullable = true)
		List<ValidationError> errors) {
}
