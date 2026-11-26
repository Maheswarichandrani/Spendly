package com.sm.spendly.category.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.category.entity.CategoryKind;

/**
 * Body of PUT /api/v1/categories/order. Income and expense lists are ordered separately
 * (they are separate pickers in the app), so one request reorders one kind.
 */
public record ReorderCategoriesRequest(
		@NotNull(message = "Kind is required.")
		CategoryKind kind,

		@NotEmpty(message = "Category ids are required.")
		@Size(max = 300, message = "At most 300 category ids.")
		@Schema(description = "Ids of this kind in the new order, first = top.")
		List<@NotNull(message = "Category id must not be null.") UUID> categoryIds) {
}
