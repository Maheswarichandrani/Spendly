package com.sm.spendly.account.entity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.sm.spendly.common.persistence.BaseEntity;

import lombok.Getter;

/**
 * One row of {@code accounts}. Owned by one user (plain {@code userId}, like RefreshToken).
 *
 * <p><b>The balance trap.</b> By default, when Hibernate saves a changed entity it writes
 * <i>every</i> column in the UPDATE, not just the changed ones. Imagine: request A renames
 * the account (loads balance 100), request B adds an expense (balance becomes 90 in the
 * DB), then A commits and writes "balance = 100" back. B's expense is lost.
 *
 * <p>So {@code currentBalance} is {@code updatable = false}: Hibernate never includes it in
 * an UPDATE. It's only changed by {@link com.sm.spendly.account.repository.AccountRepository#adjustBalance},
 * an atomic {@code SET current_balance = current_balance + :delta} that can't lose updates.
 */
@Entity
@Table(name = "accounts")
@Getter
public class Account extends BaseEntity {

	@Column(name = "user_id", nullable = false, updatable = false)
	private UUID userId;

	@Column(name = "name", nullable = false)
	private String name;

	// Fixed at creation: switching an asset to a liability would flip the meaning of its balance.
	@Enumerated(EnumType.STRING)
	@Column(name = "type", nullable = false, updatable = false)
	private AccountType type;

	@Column(name = "icon", nullable = false)
	private String icon;

	@Column(name = "color", nullable = false)
	private String color;

	// precision/scale mirror NUMERIC(19,4). ddl-auto=validate checks the type matches.
	@Column(name = "opening_balance", nullable = false, precision = 19, scale = 4)
	private BigDecimal openingBalance;

	// Written on INSERT, never on UPDATE (see the class comment).
	@Column(name = "current_balance", nullable = false, precision = 19, scale = 4, updatable = false)
	private BigDecimal currentBalance;

	@Column(name = "include_in_total", nullable = false)
	private boolean includeInTotal;

	@Column(name = "archived_at")
	private Instant archivedAt;

	@Column(name = "sort_order", nullable = false)
	private int sortOrder;

	@Column(name = "credit_limit", precision = 19, scale = 4)
	private BigDecimal creditLimit;

	@Column(name = "statement_day")
	private Integer statementDay;

	@Column(name = "due_day")
	private Integer dueDay;

	protected Account() {
	}

	/** A new account starts with its balance equal to its opening balance. */
	public Account(UUID userId, String name, AccountType type, String icon, String color, BigDecimal openingBalance,
			boolean includeInTotal, int sortOrder) {
		this.userId = userId;
		this.name = name;
		this.type = type;
		this.icon = icon;
		this.color = color;
		this.openingBalance = openingBalance;
		this.currentBalance = openingBalance;
		this.includeInTotal = includeInTotal;
		this.sortOrder = sortOrder;
	}

	// ---------------------------------------------------------------- changes
	// Saved by dirty checking (note 008): no save() call needed inside a transaction.

	public void rename(String name) {
		this.name = name;
	}

	public void changeIcon(String icon) {
		this.icon = icon;
	}

	public void changeColor(String color) {
		this.color = color;
	}

	public void changeIncludeInTotal(boolean includeInTotal) {
		this.includeInTotal = includeInTotal;
	}

	/**
	 * Only the opening balance column. The caller must also move current_balance by the
	 * same difference, with AccountRepository.adjustBalance.
	 */
	public void changeOpeningBalance(BigDecimal openingBalance) {
		this.openingBalance = openingBalance;
	}

	public void changeCreditLimit(BigDecimal creditLimit) {
		this.creditLimit = creditLimit;
	}

	public void changeStatementDay(Integer statementDay) {
		this.statementDay = statementDay;
	}

	public void changeDueDay(Integer dueDay) {
		this.dueDay = dueDay;
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
