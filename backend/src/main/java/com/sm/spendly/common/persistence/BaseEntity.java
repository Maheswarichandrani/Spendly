package com.sm.spendly.common.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;

import org.hibernate.Hibernate;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.annotations.UuidGenerator;

import lombok.Getter;

/**
 * Columns every table has: a UUID id and two UTC timestamps.
 *
 * <p>{@code @MappedSuperclass}: this class is not a table itself. Its fields are copied
 * into the table of every entity that extends it.
 */
@MappedSuperclass
@Getter
public abstract class BaseEntity {

	// UUIDv7 starts with the current time, so new rows land at the end of the primary key
	// index instead of at random places (random v4 ids fragment the index).
	// Generated in Java at save time, so we don't depend on the Postgres version.
	@Id
	@GeneratedValue
	@UuidGenerator(style = UuidGenerator.Style.VERSION_7)
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/**
	 * Two entities are equal when they are the same class and have the same id.
	 * A new entity has no id yet, so it is only equal to itself.
	 *
	 * <p>{@code Hibernate.getClass} looks through lazy-loading proxies, which are
	 * generated subclasses. {@code getId()} (not the field) works on a proxy too.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof BaseEntity that)) {
			return false;
		}
		if (Hibernate.getClass(this) != Hibernate.getClass(that)) {
			return false;
		}
		return getId() != null && getId().equals(that.getId());
	}

	/**
	 * Same value for every instance of a class, before and after the id is assigned.
	 * If hashCode used the id, an entity put in a HashSet before saving would be "lost"
	 * after saving, because its hash would change.
	 */
	@Override
	public int hashCode() {
		return Hibernate.getClass(this).hashCode();
	}

}
