package com.sm.spendly.common.exception;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test-only controller (lives in src/test, never in the production jar).
 * Each endpoint triggers one kind of error so GlobalExceptionHandler can be tested
 * before any real feature endpoint exists.
 */
@RestController
@RequestMapping("/test/errors")
class ErrorProbeController {

	record ProbeRequest(@NotBlank String name, @Positive BigDecimal amount) {
	}

	@GetMapping("/not-found")
	void notFound() {
		throw new NotFoundException("Probe not found.");
	}

	@GetMapping("/conflict")
	void conflict() {
		throw new ConflictException("Probe conflicts.");
	}

	@GetMapping("/business-rule")
	void businessRule() {
		throw new BusinessRuleException("Probe rule violated.");
	}

	@PostMapping("/validate")
	ProbeRequest validate(@Valid @RequestBody ProbeRequest request) {
		return request;
	}

	@GetMapping("/items/{id}")
	UUID item(@PathVariable UUID id) {
		return id;
	}

	@GetMapping("/boom")
	void boom() {
		throw new IllegalStateException("secret internal detail");
	}

	@GetMapping("/integrity")
	void integrity() {
		throw new DataIntegrityViolationException(
				"duplicate key value violates unique constraint users_email_key");
	}

}
