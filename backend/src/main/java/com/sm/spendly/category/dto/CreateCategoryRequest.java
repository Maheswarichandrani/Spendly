package com.sm.spendly.category.dto;

import java.util.Locale;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.category.entity.CategoryKind;

/** Body of POST /api/v1/categories. */
public record CreateCategoryRequest(
		@NotBlank(message = "Name is required.")
		@Size(max = 50, message = "Name must be at most 50 characters.")
		@Schema(example = "Pets")
		String name,

		@NotNull(message = "Kind is required.")
		@Schema(description = "Cannot be changed later.")
		CategoryKind kind,

		@Pattern(regexp = "^[a-z0-9-]{1,50}$", message = "Icon must be a Phosphor icon name like paw-print.")
		@Schema(description = "Phosphor icon name. Default: tag.", example = "paw-print", nullable = true)
		String icon,

		@Pattern(regexp = "^#[0-9A-Fa-f]{6}$", message = "Color must look like #1A2B3C.")
		@Schema(description = "Hex color. Default #6B7280.", example = "#F59E0B", nullable = true)
		String color) {

	public CreateCategoryRequest {
		name = name == null ? null : name.strip();
		color = color == null ? null : color.toUpperCase(Locale.ROOT);
	}

}
