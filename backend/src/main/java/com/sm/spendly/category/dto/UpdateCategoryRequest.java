package com.sm.spendly.category.dto;

import java.util.Locale;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/** Body of PATCH /api/v1/categories/{id}. Missing or null fields stay unchanged. Kind can't change. */
@Schema(description = "Send only the fields you want to change.")
public record UpdateCategoryRequest(
		@Size(min = 1, max = 50, message = "Name must be 1 to 50 characters.")
		@Schema(example = "Pets", nullable = true)
		String name,

		@Pattern(regexp = "^[a-z0-9-]{1,50}$", message = "Icon must be a Phosphor icon name like paw-print.")
		@Schema(example = "paw-print", nullable = true)
		String icon,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must look like #1A2B3C.")
		@Schema(example = "#F59E0B", nullable = true)
		String color) {

	public UpdateCategoryRequest {
		name = name == null ? null : name.strip();
		color = color == null ? null : color.toUpperCase(Locale.ROOT);
	}

}
