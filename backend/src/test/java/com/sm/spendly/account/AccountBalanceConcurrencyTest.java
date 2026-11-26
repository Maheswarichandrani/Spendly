package com.sm.spendly.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.sm.spendly.TestcontainersConfiguration;
import com.sm.spendly.account.entity.Account;
import com.sm.spendly.account.entity.AccountType;
import com.sm.spendly.account.repository.AccountRepository;

/**
 * Proves the "lost update" trap from the Account class comment can't happen.
 *
 * <p>Story: transaction 1 loads the account (balance 1000) and renames it. While it is
 * still open, transaction 2 on another thread spends 100 and commits (balance 900). Then
 * transaction 1 commits its rename. If Hibernate wrote every column, it would put
 * balance = 1000 back and the expense would vanish. With current_balance
 * {@code updatable = false}, the rename UPDATE doesn't touch the balance.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AccountBalanceConcurrencyTest {

	@Autowired
	AccountRepository repository;

	@Autowired
	TransactionTemplate transactionTemplate;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void renameDuringConcurrentBalanceChange_keepsBothChanges() {
		UUID userId = newUser();
		UUID accountId = transactionTemplate.execute(status -> repository
			.saveAndFlush(new Account(userId, "Wallet", AccountType.WALLET, "wallet", "#2563EB",
					new BigDecimal("1000.00"), true, 0))
			.getId());

		// TransactionTemplate runs the lambda inside one transaction, like @Transactional.
		transactionTemplate.executeWithoutResult(status -> {
			Account account = repository.findByIdAndUserId(accountId, userId).orElseThrow();
			assertThat(account.getCurrentBalance()).isEqualByComparingTo("1000.00");

			// Another thread = another connection = another transaction. join() waits
			// until it has committed.
			CompletableFuture.runAsync(() -> transactionTemplate.executeWithoutResult(
					other -> repository.adjustBalance(accountId, userId, new BigDecimal("-100.00"), Instant.now())))
				.join();

			account.rename("Daily wallet");   // saved by dirty checking when this transaction commits
		});

		Account after = repository.findById(accountId).orElseThrow();
		assertThat(after.getName()).isEqualTo("Daily wallet");
		assertThat(after.getCurrentBalance()).isEqualByComparingTo("900.00");
	}

	private UUID newUser() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				insert into users (id, email, password_hash, name, created_at, updated_at)
				values (?, ?, null, 'Concurrency', now(), now())""", id, "acc-" + id + "@mail.com");
		return id;
	}

}
