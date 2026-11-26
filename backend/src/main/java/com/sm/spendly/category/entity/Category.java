package com.sm.spendly.category.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sm.spendly.common.persistence.BaseEntity;

import lombok.Getter;

/** One row of {@code categories}. Same shape of rules as Account: owner id, archive, order. */
@Entity
@Table(name = "categories")
@Getter
public class Category extends BaseEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "name", nullable = false)
	private String name;

	// Fixed: moving "Salary" to the expense side would flip every report it appears in.
	@Enumerated(EnumType.STRING)
	@Column(name = "kind", nullable = false, updatable = false)
	private CategoryKind kind;

	@Column(name = "icon", nullable = false)
	private String icon;

	@Column(name = "color", nullable = false)
	private String color;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	protected Category() {
	}

	public Category(UUID userId, String name, CategoryKind kind, String icon, String color, int sortOrder) {
		this.userId = userId;
		this.name = name;
		this.kind = kind;
		this.icon = icon;
		this.color = color;
		this.sortOrder = sortOrder;
	}

	public void rename(String name) {
		this.name = name;
	}

	public void changeIcon(String icon) {
		this.icon = icon;
	}

	public void changeColor(String color) {
		this.color = color;
	}

	public void moveTo(int sortOrder) {
		this.sortOrder = sortOrder;
	}

	public void archive(Instant now) {
		if (archivedAt == null) {
			archivedAt = now;
		}
	}

	public void unarchive() {
		archivedAt = null;
	}

	public boolean isArchived() {
		return archivedAt != null;
	}

}
