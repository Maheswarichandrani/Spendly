package com.sm.spendly.category.dto;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

import com.sm.spendly.category.entity.CategoryKind;

/** One category as the app sees it. Built by CategoryMapper (MapStruct). */
public record CategoryResponse(
		UUID id,
		@Schema(example = "Food & Dining") String name,
		CategoryKind kind,
		@Schema(example = "fork-knife") String icon,
		@Schema(example = "#F97316") String color,
		boolean archived,
		@Schema(description = "Position within its kind, 0 = top.") int sortOrder,
		Instant createdAt) {
}
