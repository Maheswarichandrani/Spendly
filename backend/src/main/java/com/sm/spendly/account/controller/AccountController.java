package com.sm.spendly.account.controller;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.sm.spendly.account.dto.AccountResponse;
import com.sm.spendly.account.dto.AccountSummaryResponse;
import com.sm.spendly.account.dto.CreateAccountRequest;
import com.sm.spendly.account.dto.ReorderAccountsRequest;
import com.sm.spendly.account.dto.UpdateAccountRequest;
import com.sm.spendly.account.service.AccountService;
import com.sm.spendly.common.security.CurrentUser;
import com.sm.spendly.common.web.ApiProblem;

/**
 * HTTP only. The account id comes from the URL ({@code @PathVariable}), but the owner
 * always comes from the token ({@code currentUser.id()}). The service looks the account
 * up by both, so a stranger's id gives 404.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts", description = "Where money lives: cash, bank, cards, wallets. Balances and net worth.")
public class AccountController {

	private final AccountService accountService;

	private final CurrentUser currentUser;

	public AccountController(AccountService accountService, CurrentUser currentUser) {
		this.accountService = accountService;
		this.currentUser = currentUser;
	}

	@GetMapping
	@Operation(summary = "List my accounts", description = "In display order. Archived ones only if asked.")
	public List<AccountResponse> list(
			@Parameter(description = "Also return archived accounts.")
			@RequestParam(defaultValue = "false") boolean includeArchived) {
		return accountService.list(currentUser.id(), includeArchived);
	}

	@GetMapping("/summary")
	@Operation(summary = "Net worth", description = "Totals over active accounts with includeInTotal = true.")
	public AccountSummaryResponse summary() {
		return accountService.summary(currentUser.id());
	}

	@GetMapping("/{id}")
	@Operation(summary = "Get one account")
	@ApiResponse(responseCode = "200", description = "The account")
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND` (also for another user's account)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AccountResponse get(@PathVariable UUID id) {
		return accountService.get(currentUser.id(), id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	@Operation(summary = "Create an account", description = "Only name and type are required. Goes to the end of the list.")
	@ApiResponse(responseCode = "201", description = "Created")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`ACCOUNT_NAME_TAKEN` (names are unique among active accounts, ignoring case)",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`ACCOUNT_LIMIT_REACHED` or `CREDIT_CARD_FIELDS_ONLY`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AccountResponse create(@Valid @RequestBody CreateAccountRequest request) {
		return accountService.create(currentUser.id(), request);
	}

	@PatchMapping("/{id}")
	@Operation(summary = "Edit an account", description = "Send only the fields to change. Type can't change.")
	@ApiResponse(responseCode = "200", description = "The updated account")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`ACCOUNT_NAME_TAKEN`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`ACCOUNT_ARCHIVED` or `CREDIT_CARD_FIELDS_ONLY`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AccountResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateAccountRequest request) {
		return accountService.update(currentUser.id(), id, request);
	}

	@PostMapping("/{id}/archive")
	@Operation(summary = "Archive an account", description = "Hides it and leaves it out of totals. History is kept.")
	@ApiResponse(responseCode = "200", description = "The archived account")
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AccountResponse archive(@PathVariable UUID id) {
		return accountService.archive(currentUser.id(), id);
	}

	@PostMapping("/{id}/unarchive")
	@Operation(summary = "Unarchive an account")
	@ApiResponse(responseCode = "200", description = "The active account")
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "409", description = "`ACCOUNT_NAME_TAKEN`: an active account took the name meanwhile",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "422", description = "`ACCOUNT_LIMIT_REACHED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public AccountResponse unarchive(@PathVariable UUID id) {
		return accountService.unarchive(currentUser.id(), id);
	}

	@PutMapping("/order")
	@Operation(summary = "Reorder accounts", description = "Send the ids top to bottom. Returns the new active list.")
	@ApiResponse(responseCode = "200", description = "Active accounts in the new order")
	@ApiResponse(responseCode = "400", description = "`VALIDATION_FAILED`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND`: an id is unknown or not yours",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public List<AccountResponse> reorder(@Valid @RequestBody ReorderAccountsRequest request) {
		return accountService.reorder(currentUser.id(), request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	@Operation(summary = "Delete an account",
			description = "Permanent. Once transactions exist (next step), accounts with history can only be archived.")
	@ApiResponse(responseCode = "204", description = "Deleted")
	@ApiResponse(responseCode = "404", description = "`ACCOUNT_NOT_FOUND`",
			content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ApiProblem.class)))
	public void delete(@PathVariable UUID id) {
		accountService.delete(currentUser.id(), id);
	}

}
