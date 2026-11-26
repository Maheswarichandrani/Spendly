package com.sm.spendly.category.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.sm.spendly.category.dto.CategoryResponse;
import com.sm.spendly.category.entity.Category;

/**
 * Our first MapStruct mapper. We only write the method <i>signatures</i>; at compile time
 * MapStruct generates {@code CategoryMapperImpl} with the code that copies each field
 * (look in target/generated-sources/annotations).
 *
 * <p>It matches fields by name: Category.getName() goes to CategoryResponse.name,
 * Category.isArchived() goes to archived, getId() (from BaseEntity) goes to id, and so on.
 *
 * <ul>
 * <li>componentModel SPRING: the generated class is a Spring bean, so the service gets it
 * by constructor injection like anything else.</li>
 * <li>unmappedTargetPolicy ERROR: if CategoryResponse gains a field that no getter fills,
 * the <b>build fails</b> instead of silently sending null.</li>
 * </ul>
 */
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CategoryMapper {

	CategoryResponse toResponse(Category category);

	/** MapStruct writes the loop, calling toResponse for each element. */
	List<CategoryResponse> toResponses(List<Category> categories);

}
