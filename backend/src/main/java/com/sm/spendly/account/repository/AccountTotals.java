package com.sm.spendly.account.repository;

import java.math.BigDecimal;

/**
 * A DTO projection: the {@code select new ...AccountTotals(...)} query builds this
 * record straight from the aggregate row, no entity involved.
 *
 * @param assets       sum of balances of non-credit accounts
 * @param liabilities  amount owed on credit cards (positive when money is owed)
 * @param accountCount how many accounts were counted
 */
public record AccountTotals(BigDecimal assets, BigDecimal liabilities, long accountCount) {
}
