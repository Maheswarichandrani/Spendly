package com.sm.spendly.category.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sm.spendly.category.dto.CategoryResponse;
import com.sm.spendly.category.dto.CreateCategoryRequest;
import com.sm.spendly.category.dto.ReorderCategoriesRequest;
import com.sm.spendly.category.dto.UpdateCategoryRequest;
import com.sm.spendly.category.entity.Category;
import com.sm.spendly.category.entity.CategoryKind;
import com.sm.spendly.category.mapper.CategoryMapper;
import com.sm.spendly.category.repository.CategoryRepository;
import com.sm.spendly.common.exception.BusinessRuleException;
import com.sm.spendly.common.exception.ConflictException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.NotFoundException;
import com.sm.spendly.common.persistence.ConstraintNames;

/**
 * Categories: defaults for new users, create, edit, archive, reorder, delete.
 * Every public method is scoped by the userId the controller took from the token.
 */
@Service
public class CategoryService {

	private static final Logger log = LoggerFactory.getLogger(CategoryService.class);

	static final int MAX_ACTIVE_CATEGORIES = 200;

	static final String DEFAULT_ICON = "tag";

	static final String DEFAULT_COLOR = "#6B7280";

	private static final String NAME_UNIQUE_INDEX = "categories_user_kind_name_active_key";

	private static final String NAME_TAKEN_DETAIL = "You already have a category with this name.";

	private final CategoryRepository repository;

	// The generated CategoryMapperImpl, injected like any other bean.
	private final CategoryMapper mapper;

	public CategoryService(CategoryRepository repository, CategoryMapper mapper) {
		this.repository = repository;
		this.mapper = mapper;
	}

	/**
	 * Gives a brand-new user the starter categories.
	 *
	 * <p>Called by UserService.createUser, <b>inside its transaction</b>. The default
	 * propagation, REQUIRED, means "join the transaction that is already running, if any".
	 * So the user row and these 19 rows commit together, or not at all: there is never a
	 * user without categories.
	 */
	@Transactional
	public void createDefaults(UUID userId) {
		List<Category> defaults = new ArrayList<>();
		int[] nextPosition = new int[CategoryKind.values().length];
		for (DefaultCategories.Template template : DefaultCategories.ALL) {
			int position = nextPosition[template.kind().ordinal()]++;
			defaults.add(new Category(userId, template.name(), template.kind(), template.icon(), template.color(),
					position));
		}
		repository.saveAll(defaults);
		log.info("Default categories created userId={} count={}", userId, defaults.size());
	}

	// --------------------------------------------------------------------- reads

	/** kind null = both kinds (expense list first, then income: enum order of names). */
	@Transactional(readOnly = true)
	public List<CategoryResponse> list(UUID userId, CategoryKind kind, boolean includeArchived) {
		List<Category> categories;
		if (kind == null) {
			categories = includeArchived
					? repository.findByUserIdOrderByKindAscSortOrderAscCreatedAtAsc(userId)
					: repository.findByUserIdAndArchivedAtIsNullOrderByKindAscSortOrderAscCreatedAtAsc(userId);
		}
		else {
			categories = includeArchived
					? repository.findByUserIdAndKindOrderBySortOrderAscCreatedAtAsc(userId, kind)
					: repository.findByUserIdAndKindAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId, kind);
		}
		return mapper.toResponses(categories);
	}

	@Transactional(readOnly = true)
	public CategoryResponse get(UUID userId, UUID categoryId) {
		return mapper.toResponse(load(userId, categoryId));
	}

	/**
	 * For the transactions feature (step 011): "is this an active category of this user and
	 * of the right kind?". Other features must not use CategoryRepository directly.
	 */
	@Transactional(readOnly = true)
	public void requireActiveCategory(UUID userId, UUID categoryId, CategoryKind kind) {
		Category category = load(userId, categoryId);
		if (category.isArchived()) {
			throw archived();
		}
		if (category.getKind() != kind) {
			throw new BusinessRuleException(ErrorCode.CATEGORY_KIND_MISMATCH,
					"This is " + article(category.getKind()) + " category; pick " + article(kind) + " one.");
		}
	}

	// -------------------------------------------------------------------- writes

	@Transactional
	public CategoryResponse create(UUID userId, CreateCategoryRequest request) {
		if (repository.countByUserIdAndArchivedAtIsNull(userId) >= MAX_ACTIVE_CATEGORIES) {
			throw new BusinessRuleException(ErrorCode.CATEGORY_LIMIT_REACHED,
					"You can have at most " + MAX_ACTIVE_CATEGORIES + " active categories. Archive some first.");
		}
		if (repository.activeNameExists(userId, request.kind(), request.name())) {
			throw new ConflictException(ErrorCode.CATEGORY_NAME_TAKEN, NAME_TAKEN_DETAIL);
		}
		Category category = new Category(userId, request.name(), request.kind(),
				request.icon() != null ? request.icon() : DEFAULT_ICON,
				request.color() != null ? request.color() : DEFAULT_COLOR,
				repository.maxSortOrder(userId, request.kind()) + 1);
		Category saved = saveCatchingNameClash(category);
		log.info("Category created userId={} categoryId={} kind={}", userId, saved.getId(), saved.getKind());
		return mapper.toResponse(saved);
	}

	@Transactional
	public CategoryResponse update(UUID userId, UUID categoryId, UpdateCategoryRequest request) {
		Category category = load(userId, categoryId);
		if (category.isArchived()) {
			throw archived();
		}
		if (request.name() != null && !request.name().equals(category.getName())) {
			if (repository.activeNameExistsExcept(userId, category.getKind(), request.name(), categoryId)) {
				throw new ConflictException(ErrorCode.CATEGORY_NAME_TAKEN, NAME_TAKEN_DETAIL);
			}
			category.rename(request.name());
		}
		if (request.icon() != null) {
			category.changeIcon(request.icon());
		}
		if (request.color() != null) {
			category.changeColor(request.color());
		}
		flushCatchingNameClash();
		log.info("Category updated userId={} categoryId={}", userId, categoryId);
		return mapper.toResponse(category);
	}

	@Transactional
	public CategoryResponse archive(UUID userId, UUID categoryId) {
		Category category = load(userId, categoryId);
		category.archive(Instant.now());
		log.info("Category archived userId={} categoryId={}", userId, categoryId);
		return mapper.toResponse(category);
	}

	@Transactional
	public CategoryResponse unarchive(UUID userId, UUID categoryId) {
		Category category = load(userId, categoryId);
		if (!category.isArchived()) {
			return mapper.toResponse(category);
		}
		if (repository.countByUserIdAndArchivedAtIsNull(userId) >= MAX_ACTIVE_CATEGORIES) {
			throw new BusinessRuleException(ErrorCode.CATEGORY_LIMIT_REACHED,
					"You can have at most " + MAX_ACTIVE_CATEGORIES + " active categories. Archive some first.");
		}
		if (repository.activeNameExists(userId, category.getKind(), category.getName())) {
			throw new ConflictException(ErrorCode.CATEGORY_NAME_TAKEN,
					"An active category already uses this name. Rename one of them first.");
		}
		category.unarchive();
		flushCatchingNameClash();
		log.info("Category unarchived userId={} categoryId={}", userId, categoryId);
		return mapper.toResponse(category);
	}

	/**
	 * Deletes a category. {@code replaceWithId} names where its transactions should move.
	 * Categories are never left "orphaned" (AGENTS.md section 7).
	 *
	 * <p>Step 011 adds the transactions: then a category that is in use needs a replacement
	 * (409 CATEGORY_IN_USE without one), and its transactions are moved in this same
	 * transaction before the delete. Today no category is in use yet, but the replacement is
	 * already checked so the API stays the same.
	 */
	@Transactional
	public void delete(UUID userId, UUID categoryId, UUID replaceWithId) {
		Category category = load(userId, categoryId);
		if (replaceWithId != null) {
			requireValidReplacement(userId, category, replaceWithId);
		}
		repository.delete(category);
		log.info("Category deleted userId={} categoryId={} replacedBy={}", userId, categoryId, replaceWithId);
	}

	/** Reorders one kind. Listed ids first, the rest of that kind keep their order after them. */
	@Transactional
	public List<CategoryResponse> reorder(UUID userId, ReorderCategoriesRequest request) {
		Set<UUID> wanted = new LinkedHashSet<>(request.categoryIds());
		Map<UUID, Category> found = repository.findByUserIdAndKindAndIdIn(userId, request.kind(), wanted).stream()
			.collect(Collectors.toMap(Category::getId, Function.identity()));
		if (found.size() != wanted.size()) {
			// Unknown id, someone else's id, or an id of the other kind.
			throw notFound();
		}
		List<Category> ordered = new ArrayList<>();
		wanted.forEach(id -> ordered.add(found.get(id)));
		repository.findByUserIdAndKindOrderBySortOrderAscCreatedAtAsc(userId, request.kind()).stream()
			.filter(category -> !wanted.contains(category.getId()))
			.forEach(ordered::add);
		for (int position = 0; position < ordered.size(); position++) {
			ordered.get(position).moveTo(position);
		}
		log.info("Categories reordered userId={} kind={} count={}", userId, request.kind(), wanted.size());
		return mapper.toResponses(ordered.stream().filter(category -> !category.isArchived()).toList());
	}

	// ------------------------------------------------------------------- helpers

	private void requireValidReplacement(UUID userId, Category category, UUID replaceWithId) {
		if (replaceWithId.equals(category.getId())) {
			throw invalidReplacement("A category can't replace itself.");
		}
		Category replacement = repository.findByIdAndUserId(replaceWithId, userId)
			.orElseThrow(() -> invalidReplacement("Replacement category not found."));
		if (replacement.isArchived()) {
			throw invalidReplacement("Replacement category is archived.");
		}
		if (replacement.getKind() != category.getKind()) {
			throw invalidReplacement("Replacement must be " + article(category.getKind()) + " category too.");
		}
	}

	private Category load(UUID userId, UUID categoryId) {
		return repository.findByIdAndUserId(categoryId, userId).orElseThrow(CategoryService::notFound);
	}

	private Category saveCatchingNameClash(Category category) {
		try {
			return repository.saveAndFlush(category);
		}
		catch (DataIntegrityViolationException ex) {
			throw nameClashOr(ex);
		}
	}

	private void flushCatchingNameClash() {
		try {
			repository.flush();
		}
		catch (DataIntegrityViolationException ex) {
			throw nameClashOr(ex);
		}
	}

	private static RuntimeException nameClashOr(DataIntegrityViolationException ex) {
		if (NAME_UNIQUE_INDEX.equals(ConstraintNames.of(ex))) {
			log.info("Category name clash caught by unique index");
			return new ConflictException(ErrorCode.CATEGORY_NAME_TAKEN, NAME_TAKEN_DETAIL);
		}
		return ex;
	}

	private static String article(CategoryKind kind) {
		return kind == CategoryKind.INCOME ? "an income" : "an expense";
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.CATEGORY_NOT_FOUND, "Category not found.");
	}

	private static BusinessRuleException archived() {
		return new BusinessRuleException(ErrorCode.CATEGORY_ARCHIVED, "This category is archived. Unarchive it first.");
	}

	private static BusinessRuleException invalidReplacement(String detail) {
		return new BusinessRuleException(ErrorCode.INVALID_REPLACEMENT_CATEGORY, detail);
	}

}
