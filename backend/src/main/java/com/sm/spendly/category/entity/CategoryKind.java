package com.sm.spendly.category.entity;

/**
 * What a category is for. Only income and expense have categories; transfers, lending and
 * the other money movements don't (AGENTS.md section 7).
 */
public enum CategoryKind {
	INCOME,
	EXPENSE
}
