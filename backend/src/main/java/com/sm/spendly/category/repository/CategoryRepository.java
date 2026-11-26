package com.sm.spendly.category.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sm.spendly.category.entity.Category;
import com.sm.spendly.category.entity.CategoryKind;

/** Same ownership rule as accounts: every method takes the userId. */
public interface CategoryRepository extends JpaRepository<Category, UUID> {

	Optional<Category> findByIdAndUserId(UUID id, UUID userId);

	List<Category> findByUserIdOrderByKindAscSortOrderAscCreatedAtAsc(UUID userId);

	List<Category> findByUserIdAndArchivedAtIsNullOrderByKindAscSortOrderAscCreatedAtAsc(UUID userId);

	List<Category> findByUserIdAndKindOrderBySortOrderAscCreatedAtAsc(UUID userId, CategoryKind kind);

	List<Category> findByUserIdAndKindAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID userId, CategoryKind kind);

	List<Category> findByUserIdAndKindAndIdIn(UUID userId, CategoryKind kind, Collection<UUID> ids);

	long countByUserIdAndArchivedAtIsNull(UUID userId);

	@Query("""
			select count(c) > 0 from Category c
			where c.userId = :userId and c.kind = :kind and lower(c.name) = lower(:name) and c.archivedAt is null""")
	boolean activeNameExists(@Param("userId") UUID userId, @Param("kind") CategoryKind kind, @Param("name") String name);

	@Query("""
			select count(c) > 0 from Category c
			where c.userId = :userId and c.kind = :kind and lower(c.name) = lower(:name) and c.archivedAt is null
			  and c.id <> :id""")
	boolean activeNameExistsExcept(@Param("userId") UUID userId, @Param("kind") CategoryKind kind,
			@Param("name") String name, @Param("id") UUID id);

	@Query("select coalesce(max(c.sortOrder), -1) from Category c where c.userId = :userId and c.kind = :kind")
	int maxSortOrder(@Param("userId") UUID userId, @Param("kind") CategoryKind kind);

}
