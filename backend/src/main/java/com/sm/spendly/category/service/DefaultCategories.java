package com.sm.spendly.category.service;

import java.util.List;

import com.sm.spendly.category.entity.CategoryKind;

/**
 * The starter set every new user gets. Icons are Phosphor names, colors are uppercase hex.
 *
 * <p>If you change this list, new users get the new list; existing users keep what they
 * have. V6__create_categories.sql has a copy of the original list for users who existed
 * before categories did.
 */
final class DefaultCategories {

	record Template(String name, CategoryKind kind, String icon, String color) {
	}

	static final List<Template> ALL = List.of(
			new Template("Food & Dining", CategoryKind.EXPENSE, "fork-knife", "#F97316"),
			new Template("Groceries", CategoryKind.EXPENSE, "shopping-cart", "#84CC16"),
			new Template("Transport", CategoryKind.EXPENSE, "bus", "#0EA5E9"),
			new Template("Fuel", CategoryKind.EXPENSE, "gas-pump", "#64748B"),
			new Template("Shopping", CategoryKind.EXPENSE, "shopping-bag", "#EC4899"),
			new Template("Bills & Utilities", CategoryKind.EXPENSE, "receipt", "#EAB308"),
			new Template("Rent", CategoryKind.EXPENSE, "house", "#8B5CF6"),
			new Template("Health", CategoryKind.EXPENSE, "heartbeat", "#EF4444"),
			new Template("Entertainment", CategoryKind.EXPENSE, "film-slate", "#A855F7"),
			new Template("Education", CategoryKind.EXPENSE, "graduation-cap", "#3B82F6"),
			new Template("Travel", CategoryKind.EXPENSE, "airplane-tilt", "#14B8A6"),
			new Template("Gifts & Donations", CategoryKind.EXPENSE, "gift", "#F43F5E"),
			new Template("Other", CategoryKind.EXPENSE, "dots-three-circle", "#6B7280"),
			new Template("Salary", CategoryKind.INCOME, "briefcase", "#16A34A"),
			new Template("Business", CategoryKind.INCOME, "storefront", "#0D9488"),
			new Template("Interest", CategoryKind.INCOME, "percent", "#2563EB"),
			new Template("Gifts", CategoryKind.INCOME, "gift", "#DB2777"),
			new Template("Refunds", CategoryKind.INCOME, "arrow-u-up-left", "#7C3AED"),
			new Template("Other", CategoryKind.INCOME, "dots-three-circle", "#6B7280"));

	private DefaultCategories() {
	}

}
