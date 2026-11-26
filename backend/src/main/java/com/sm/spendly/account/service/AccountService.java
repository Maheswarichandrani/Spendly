package com.sm.spendly.account.service;

import java.math.BigDecimal;
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

import com.sm.spendly.account.dto.AccountResponse;
import com.sm.spendly.account.dto.AccountSummaryResponse;
import com.sm.spendly.account.dto.CreateAccountRequest;
import com.sm.spendly.account.dto.ReorderAccountsRequest;
import com.sm.spendly.account.dto.UpdateAccountRequest;
import com.sm.spendly.account.entity.Account;
import com.sm.spendly.account.entity.AccountType;
import com.sm.spendly.account.mapper.AccountMapper;
import com.sm.spendly.account.repository.AccountRepository;
import com.sm.spendly.account.repository.AccountTotals;
import com.sm.spendly.common.exception.BusinessRuleException;
import com.sm.spendly.common.exception.ConflictException;
import com.sm.spendly.common.exception.ErrorCode;
import com.sm.spendly.common.exception.NotFoundException;
import com.sm.spendly.common.money.Money;
import com.sm.spendly.common.persistence.ConstraintNames;
import com.sm.spendly.user.service.UserService;

/**
 * Accounts: create, edit, archive, reorder, delete, and net worth.
 *
 * <p>Every public method takes the userId from the controller (which got it from the
 * token) and passes it to every repository call. That is the ownership rule.
 */
@Service
public class AccountService {

	private static final Logger log = LoggerFactory.getLogger(AccountService.class);

	/** Keeps lists and sums small. A real person has a handful. */
	static final int MAX_ACTIVE_ACCOUNTS = 100;

	static final String DEFAULT_COLOR = "#2563EB";

	private static final String NAME_UNIQUE_INDEX = "accounts_user_name_active_key";

	private static final String NAME_TAKEN_DETAIL = "You already have an account with this name.";

	private final AccountRepository repository;

	private final UserService userService;

	public AccountService(AccountRepository repository, UserService userService) {
		this.repository = repository;
		this.userService = userService;
	}

	// --------------------------------------------------------------------- reads

	@Transactional(readOnly = true)
	public List<AccountResponse> list(UUID userId, boolean includeArchived) {
		List<Account> accounts = includeArchived
				? repository.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId)
				: repository.findByUserIdAndArchivedAtIsNullOrderBySortOrderAscCreatedAtAsc(userId);
		return accounts.stream().map(AccountMapper::toResponse).toList();
	}

	@Transactional(readOnly = true)
	public AccountResponse get(UUID userId, UUID accountId) {
		return AccountMapper.toResponse(load(userId, accountId));
	}

	/** Net worth. The sums are computed by the database in one query. */
	@Transactional(readOnly = true)
	public AccountSummaryResponse summary(UUID userId) {
		AccountTotals totals = repository.totals(userId, AccountType.CREDIT_CARD);
		BigDecimal netWorth = totals.assets().subtract(totals.liabilities());
		String currency = userService.getById(userId).currency();
		return new AccountSummaryResponse(Money.forDisplay(totals.assets()), Money.forDisplay(totals.liabilities()),
				Money.forDisplay(netWorth), totals.accountCount(), currency);
	}

	/**
	 * For other features (transactions, goals): "does this active account belong to this
	 * user?". They must never use AccountRepository directly.
	 */
	@Transactional(readOnly = true)
	public void requireActiveAccount(UUID userId, UUID accountId) {
		if (load(userId, accountId).isArchived()) {
			throw archived();
		}
	}

	// -------------------------------------------------------------------- writes

	@Transactional
	public AccountResponse create(UUID userId, CreateAccountRequest request) {
		if (repository.countByUserIdAndArchivedAtIsNull(userId) >= MAX_ACTIVE_ACCOUNTS) {
			throw new BusinessRuleException(ErrorCode.ACCOUNT_LIMIT_REACHED,
					"You can have at most " + MAX_ACTIVE_ACCOUNTS + " active accounts. Archive one first.");
		}
		requireCreditFieldsOnlyOnCards(request.type(), request.creditLimit(), request.statementDay(), request.dueDay());
		if (repository.activeNameExists(userId, request.name())) {
			throw new ConflictException(ErrorCode.ACCOUNT_NAME_TAKEN, NAME_TAKEN_DETAIL);
		}

		Account account = new Account(
				userId,
				request.name(),
				request.type(),
				request.icon() != null ? request.icon() : request.type().defaultIcon(),
				request.color() != null ? request.color() : DEFAULT_COLOR,
				request.openingBalance() != null ? request.openingBalance() : BigDecimal.ZERO,
				request.includeInTotal() == null || request.includeInTotal(),
				repository.maxSortOrder(userId) + 1);
		account.changeCreditLimit(request.creditLimit());
		account.changeStatementDay(request.statementDay());
		account.changeDueDay(request.dueDay());

		Account saved = saveCatchingNameClash(account);
		log.info("Account created userId={} accountId={} type={}", userId, saved.getId(), saved.getType());
		return AccountMapper.toResponse(saved);
	}

	@Transactional
	public AccountResponse update(UUID userId, UUID accountId, UpdateAccountRequest request) {
		Account account = load(userId, accountId);
		if (account.isArchived()) {
			throw archived();
		}
		requireCreditFieldsOnlyOnCards(account.getType(), request.creditLimit(), request.statementDay(),
				request.dueDay());

		if (request.name() != null && !request.name().equals(account.getName())) {
			if (repository.activeNameExistsExcept(userId, request.name(), accountId)) {
				throw new ConflictException(ErrorCode.ACCOUNT_NAME_TAKEN, NAME_TAKEN_DETAIL);
			}
			account.rename(request.name());
		}
		if (request.icon() != null) {
			account.changeIcon(request.icon());
		}
		if (request.color() != null) {
			account.changeColor(request.color());
		}
		if (request.includeInTotal() != null) {
			account.changeIncludeInTotal(request.includeInTotal());
		}
		if (request.creditLimit() != null) {
			account.changeCreditLimit(request.creditLimit());
		}
		if (request.statementDay() != null) {
			account.changeStatementDay(request.statementDay());
		}
		if (request.dueDay() != null) {
			account.changeDueDay(request.dueDay());
		}
		flushCatchingNameClash();

		if (request.openingBalance() != null && request.openingBalance().compareTo(account.getOpeningBalance()) != 0) {
			// Opening 1000 -> 1500: the whole history shifts up by 500, so the balance does too.
			BigDecimal delta = request.openingBalance().subtract(account.getOpeningBalance());
			account.changeOpeningBalance(request.openingBalance());
			// adjustBalance first flushes the opening-balance change (flushAutomatically),
			// then moves current_balance atomically, then clears the persistence context.
			repository.adjustBalance(accountId, userId, delta, Instant.now());
			// The old object is detached and stale now; read the fresh row.
			account = load(userId, accountId);
			log.info("Opening balance changed userId={} accountId={}", userId, accountId);
		}
		log.info("Account updated userId={} accountId={}", userId, accountId);
		return AccountMapper.toResponse(account);
	}

	@Transactional
	public AccountResponse archive(UUID userId, UUID accountId) {
		Account account = load(userId, accountId);
		account.archive(Instant.now());
		log.info("Account archived userId={} accountId={}", userId, accountId);
		return AccountMapper.toResponse(account);
	}

	@Transactional
	public AccountResponse unarchive(UUID userId, UUID accountId) {
		Account account = load(userId, accountId);
		if (!account.isArchived()) {
			return AccountMapper.toResponse(account);
		}
		if (repository.countByUserIdAndArchivedAtIsNull(userId) >= MAX_ACTIVE_ACCOUNTS) {
			throw new BusinessRuleException(ErrorCode.ACCOUNT_LIMIT_REACHED,
					"You can have at most " + MAX_ACTIVE_ACCOUNTS + " active accounts. Archive one first.");
		}
		// An active account may have taken the name while this one was archived.
		if (repository.activeNameExists(userId, account.getName())) {
			throw new ConflictException(ErrorCode.ACCOUNT_NAME_TAKEN,
					"An active account already uses this name. Rename one of them first.");
		}
		account.unarchive();
		flushCatchingNameClash();
		log.info("Account unarchived userId={} accountId={}", userId, accountId);
		return AccountMapper.toResponse(account);
	}

	/**
	 * Hard delete. Allowed while an account has no history. From step 011 (transactions)
	 * an account with transactions can only be archived.
	 */
	@Transactional
	public void delete(UUID userId, UUID accountId) {
		repository.delete(load(userId, accountId));
		log.info("Account deleted userId={} accountId={}", userId, accountId);
	}

	/**
	 * The listed accounts get positions 0, 1, 2... in that order; the user's other accounts
	 * keep their relative order after them. Unknown or foreign ids give 404 for the request.
	 */
	@Transactional
	public List<AccountResponse> reorder(UUID userId, ReorderAccountsRequest request) {
		// LinkedHashSet: drops duplicates but keeps the order the app sent.
		Set<UUID> wanted = new LinkedHashSet<>(request.accountIds());
		Map<UUID, Account> found = repository.findByUserIdAndIdIn(userId, wanted).stream()
			.collect(Collectors.toMap(Account::getId, Function.identity()));
		if (found.size() != wanted.size()) {
			throw notFound();
		}

		List<Account> ordered = new ArrayList<>();
		wanted.forEach(id -> ordered.add(found.get(id)));
		repository.findByUserIdOrderBySortOrderAscCreatedAtAsc(userId).stream()
			.filter(account -> !wanted.contains(account.getId()))
			.forEach(ordered::add);

		for (int position = 0; position < ordered.size(); position++) {
			ordered.get(position).moveTo(position);   // dirty checking writes the changed rows
		}
		log.info("Accounts reordered userId={} count={}", userId, wanted.size());
		return ordered.stream().filter(account -> !account.isArchived()).map(AccountMapper::toResponse).toList();
	}

	// ------------------------------------------------------------------- helpers

	/** findByIdAndUserId: another user's account looks exactly like a missing one (404). */
	private Account load(UUID userId, UUID accountId) {
		return repository.findByIdAndUserId(accountId, userId).orElseThrow(AccountService::notFound);
	}

	private static void requireCreditFieldsOnlyOnCards(AccountType type, BigDecimal creditLimit, Integer statementDay,
			Integer dueDay) {
		boolean hasCreditFields = creditLimit != null || statementDay != null || dueDay != null;
		if (hasCreditFields && !type.isLiability()) {
			throw new BusinessRuleException(ErrorCode.CREDIT_CARD_FIELDS_ONLY,
					"Credit limit, statement day and due day are only for credit cards.");
		}
	}

	/** saveAndFlush so a unique-index clash happens here, where we can turn it into a 409. */
	private Account saveCatchingNameClash(Account account) {
		try {
			return repository.saveAndFlush(account);
		}
		catch (DataIntegrityViolationException ex) {
			throw nameClashOr(ex);
		}
	}

	/** Same for changes made by dirty checking: flush now instead of at commit. */
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
			log.info("Account name clash caught by unique index");
			return new ConflictException(ErrorCode.ACCOUNT_NAME_TAKEN, NAME_TAKEN_DETAIL);
		}
		return ex;
	}

	private static NotFoundException notFound() {
		return new NotFoundException(ErrorCode.ACCOUNT_NOT_FOUND, "Account not found.");
	}

	private static BusinessRuleException archived() {
		return new BusinessRuleException(ErrorCode.ACCOUNT_ARCHIVED, "This account is archived. Unarchive it first.");
	}

}
