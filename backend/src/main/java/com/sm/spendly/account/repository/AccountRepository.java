package com.sm.spendly.account.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.sm.spendly.account.entity.Account;
import com.sm.spendly.account.entity.AccountType;

/**
 * Every method takes the userId. There is no "find by id" without it, so no code path
 * can load another user's account by accident (ownership, AGENTS.md section 5).
 */
public interface AccountRepository extends JpaRepository<Account, UUID> {

	/** Another user's id gives empty, and the service turns that into 404 (not 403). */
	Optional<Account> findByIdAndUserId(UUID id, UUID userId);

	List<Account> findByUserIdAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(UUID userId);

	List<Account> findByUserIdOrderBySortOrderAscCreatedAtAsc(UUID userId);

	List<Account> findByUserIdAndIdIn(UUID userId, Collection<UUID> ids);

	long countByUserIdAndArchivedAtIsNull(UUID userId);

	/**
	 * Name clash check for active accounts, for a friendly 409 before saving. The unique
	 * index is the real guard (it also catches two requests racing).
	 */
	@Query("""
			select count(a) > 0 from Account a
			where a.userId = :userId and lower(a.name) = lower(:name) and a.archivedAt is null""")
	boolean activeNameExists(@Param("userId") UUID userId, @Param("name") String name);

	/** Same, ignoring one account: renaming "Cash" to "CASH" must not clash with itself. */
	@Query("""
			select count(a) > 0 from Account a
			where a.userId = :userId and lower(a.name) = lower(:name) and a.archivedAt is null and a.id <> :id""")
	boolean activeNameExistsExcept(@Param("userId") UUID userId, @Param("name") String name, @Param("id") UUID id);

	/** New accounts go to the end of the list. -1 when the user has none, so the first gets 0. */
	@Query("select coalesce(max(a.sortOrder), -1) from Account a where a.userId = :userId")
	int maxSortOrder(@Param("userId") UUID userId);

	/**
	 * The only way current_balance changes. One atomic SQL statement:
	 * {@code UPDATE accounts SET current_balance = current_balance + ? ...}. The database
	 * reads and writes the value in one step, so two movements at the same time both count.
	 *
	 * <p>flushAutomatically: first send any pending entity changes of this transaction.
	 * clearAutomatically: then empty the persistence context, so the next find reads the
	 * new balance from the database instead of the stale object in memory (note 007).
	 *
	 * @return rows changed: 1, or 0 if the account isn't this user's
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("""
			update Account a set a.currentBalance = a.currentBalance + :delta, a.updatedAt = :now
			where a.id = :id and a.userId = :userId""")
	int adjustBalance(@Param("id") UUID id, @Param("userId") UUID userId, @Param("delta") BigDecimal delta,
			@Param("now") Instant now);

	/**
	 * Net worth in one aggregate query: the database adds the numbers, we never load every
	 * account into memory to sum them (AGENTS.md section 9). Only active accounts that are
	 * included in totals count. Liabilities are reported as a positive "amount owed".
	 */
	@Query("""
			select new com.sm.spendly.account.repository.AccountTotals(
			    coalesce(sum(case when a.type <> :liability then a.currentBalance end), cast(0 as BigDecimal)),
			    coalesce(sum(case when a.type = :liability then -a.currentBalance end), cast(0 as BigDecimal)),
			    count(a))
			from Account a
			where a.userId = :userId and a.archivedAt is null and a.includeInTotal = true""")
	AccountTotals totals(@Param("userId") UUID userId, @Param("liability") AccountType liability);

}
