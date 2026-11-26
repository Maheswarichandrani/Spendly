package com.sm.spendly.category.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.category.dto.CategoryResponse;
import com.sm.spendly.category.dto.CreateCategoryRequest;
import com.sm.spendly.category.dto.ReorderCategoriesRequest;
import com.sm.spendly.category.dto.UpdateCategoryRequest;
import com.sm.spendly.category.entity.CategoryKind;
import com.sm.spendly.category.service.CategoryService;
import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.web.ApiProblem;

@RestController
@RequestMapping("/api/v1/categories")
@Tag(name = "Categories", description = "What money is spent on or comes from. Every user starts with a default set.")
public class CategoryController {

	private final CategoryService categoryService;

	private final CurrentUser currentUser;

	public CategoryController(CategoryService categoryService, CurrentUser currentUser) {
		this.categoryService = categoryService;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(summary = "List my categories",
			description = "Grouped by kind (EXPENSE first, then INCOME), each in display order.")
	public List<CategoryResponse> list(
			@Parameter(description = "Only this kind. Leave empty for both.")
			@RequestParam(required = false) CategoryKind kind,
			@Parameter(description = "Also return archived categories.")
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return categoryService.list(currentUser.id(), kind, includeArchived);
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one category")
	@ApiResponse(responseCode = "200", description = "The category")
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CategoryResponse get(@PathVariable UUID id) {
		return categoryService.get(currentUser.id(), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create a category", description = "Goes to the end of its kind's list.")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`CATEGORY_NAME_TAKEN` (unique per kind among active ones, ignoring case)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`CATEGORY_LIMIT_REACHED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest request) {
		return categoryService.create(currentUser.id(), request);
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Edit a category", description = "Name, icon, color. Kind can't change.")
	@ApiResponse(responseCode = "200", description = "The updated category")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`CATEGORY_NAME_TAKEN`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`CATEGORY_ARCHIVED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CategoryResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateCategoryRequest request) {
		return categoryService.update(currentUser.id(), id, request);
	}

	@PostMapping("/{id}/archive")
	@Operation(summary = "Archive a category", description = "Hidden from pickers; past transactions keep it.")
	@ApiResponse(responseCode = "200", description = "The archived category")
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CategoryResponse archive(@PathVariable UUID id) {
		return categoryService.archive(currentUser.id(), id);
	}

	@PostMapping("/{id}/unarchive")
	@Operation(summary = "Unarchive a category")
	@ApiResponse(responseCode = "200", description = "The active category")
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`CATEGORY_NAME_TAKEN`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`CATEGORY_LIMIT_REACHED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public CategoryResponse unarchive(@PathVariable UUID id) {
		return categoryService.unarchive(currentUser.id(), id);
	}

	@PutMapping("/order")
	@Operation(summary = "Reorder categories of one kind", description = "Send the ids top to bottom.")
	@ApiResponse(responseCode = "200", description = "Active categories of that kind in the new order")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`: an id is unknown, not yours, or of the other kind",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public List<CategoryResponse> reorder(@Valid @RequestBody ReorderCategoriesRequest request) {
		return categoryService.reorder(currentUser.id(), request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete a category",
			description = "If the category has transactions, pass `replaceWith`: they move to that category "
					+ "(same kind, active). Without it you get 409 `CATEGORY_IN_USE`. Prefer archive to keep history.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "404", description = "`CATEGORY_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`CATEGORY_IN_USE`: it has transactions and no `replaceWith` was given",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`INVALID_REPLACEMENT_CATEGORY`: replacement missing, archived, itself, or other kind",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public void delete(@PathVariable UUID id,
			@Parameter(description = "Category that takes over this one's transactions.")
			@RequestParam(required = false) UUID replaceWith) {
		categoryService.delete(currentUser.id(), id, replaceWith);
	}

}
