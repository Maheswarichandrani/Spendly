package com.sm.spendly.account;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.sm.spendly.TestcontainersConfiguration;

/** Accounts over HTTP against real Postgres. Each test makes its own user. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class AccountIntegrationTest {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	// ------------------------------------------------------------------ create

	@Test
	void create_minimal_usesDefaults_andMoneyIsAString() throws Exception {
		String token = newUser();

		create(token, "{ \"name\": \"  Cash  \", \"type\": \"CASH\" }")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Cash"))
			.andExpect(jsonPath("$.icon").value("money"))
			.andExpect(jsonPath("$.color").value("#2563EB"))
			// A JSON string, not a number: jsonPath compares with the String "0.00".
			.andExpect(jsonPath("$.currentBalance").value("0.00"))
			.andExpect(jsonPath("$.openingBalance").value("0.00"))
			.andExpect(jsonPath("$.liability").value(false))
			.andExpect(jsonPath("$.includeInTotal").value(true))
			.andExpect(jsonPath("$.sortOrder").value(0))
			.andExpect(jsonPath("$.amountOwed").doesNotExist());

		create(token, "{ \"name\": \"Bank\", \"type\": \"BANK\" }").andExpect(jsonPath("$.sortOrder").value(1));
	}

	@Test
	void create_acceptsMoneyAsStringOrNumber_andKeepsExactDecimals() throws Exception {
		String token = newUser();

		create(token, "{ \"name\": \"A\", \"type\": \"BANK\", \"openingBalance\": \"25000.5\" }")
			.andExpect(jsonPath("$.currentBalance").value("25000.50"));
		create(token, "{ \"name\": \"B\", \"type\": \"BANK\", \"openingBalance\": 0.1 }")
			.andExpect(jsonPath("$.currentBalance").value("0.10"));
		create(token, "{ \"name\": \"C\", \"type\": \"BANK\", \"openingBalance\": \"12.3456\" }")
			.andExpect(jsonPath("$.currentBalance").value("12.3456"));
	}

	@Test
	void create_tooManyDecimals_returns400ForThatField() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"A\", \"type\": \"BANK\", \"openingBalance\": \"1.23456\" }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors[0].field").value("openingBalance"));
	}

	@Test
	void create_badColorAndIcon_return400() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"A\", \"type\": \"BANK\", \"color\": \"blue\", \"icon\": \"Bank Icon\" }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.errors.length()").value(2));
	}

	@Test
	void create_creditCard_showsAmountOwedAndAvailableCredit() throws Exception {
		String token = newUser();

		create(token, """
				{ "name": "Amex", "type": "CREDIT_CARD", "openingBalance": "-5000",
				  "creditLimit": "100000", "statementDay": 5, "dueDay": 25 }
				""")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.liability").value(true))
			.andExpect(jsonPath("$.icon").value("credit-card"))
			.andExpect(jsonPath("$.currentBalance").value("-5000.00"))
			.andExpect(jsonPath("$.amountOwed").value("5000.00"))
			.andExpect(jsonPath("$.availableCredit").value("95000.00"))
			.andExpect(jsonPath("$.dueDay").value(25));
	}

	@Test
	void create_creditFieldsOnBank_returns422() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"A\", \"type\": \"BANK\", \"creditLimit\": \"1000\" }")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CREDIT_CARD_FIELDS_ONLY"));
	}

	@Test
	void names_areUniqueAmongActiveAccounts_ignoringCase() throws Exception {
		String token = newUser();
		String first = idOf(create(token, "{ \"name\": \"Wallet\", \"type\": \"WALLET\" }"));

		create(token, "{ \"name\": \"WALLET\", \"type\": \"CASH\" }")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NAME_TAKEN"));

		// Archived names are free again...
		call(token, post("/api/v1/accounts/" + first + "/archive")).andExpect(status().isOk());
		create(token, "{ \"name\": \"wallet\", \"type\": \"WALLET\" }").andExpect(status().isCreated());

		// ...but then the old one can't come back with the same name.
		call(token, post("/api/v1/accounts/" + first + "/unarchive"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NAME_TAKEN"));
	}

	// ------------------------------------------------------------------ update

	@Test
	void patch_openingBalance_movesCurrentBalanceByTheDifference() throws Exception {
		String token = newUser();
		String id = idOf(create(token, "{ \"name\": \"Bank\", \"type\": \"BANK\", \"openingBalance\": \"1000\" }"));

		patchAccount(token, id, "{ \"openingBalance\": \"1500.25\" }")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.openingBalance").value("1500.25"))
			.andExpect(jsonPath("$.currentBalance").value("1500.25"));

		// Renaming alone never touches the balance.
		patchAccount(token, id, "{ \"name\": \"Main bank\", \"color\": \"#10b981\" }")
			.andExpect(jsonPath("$.name").value("Main bank"))
			.andExpect(jsonPath("$.color").value("#10B981"))
			.andExpect(jsonPath("$.currentBalance").value("1500.25"));
	}

	@Test
	void patch_archivedAccount_returns422() throws Exception {
		String token = newUser();
		String id = idOf(create(token, "{ \"name\": \"Old\", \"type\": \"CASH\" }"));
		call(token, post("/api/v1/accounts/" + id + "/archive"));

		patchAccount(token, id, "{ \"name\": \"New\" }")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("ACCOUNT_ARCHIVED"));
	}

	// ------------------------------------------------------------ list, archive

	@Test
	void list_hidesArchivedUnlessAsked() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"Keep\", \"type\": \"CASH\" }");
		String old = idOf(create(token, "{ \"name\": \"Old\", \"type\": \"CASH\" }"));
		call(token, post("/api/v1/accounts/" + old + "/archive")).andExpect(jsonPath("$.archived").value(true));

		call(token, get("/api/v1/accounts")).andExpect(jsonPath("$.length()").value(1));
		call(token, get("/api/v1/accounts?includeArchived=true")).andExpect(jsonPath("$.length()").value(2));
	}

	// ----------------------------------------------------------------- summary

	@Test
	void summary_countsOnlyActiveIncludedAccounts_andTreatsCardsAsLiabilities() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"Bank\", \"type\": \"BANK\", \"openingBalance\": \"1000\" }");
		create(token, "{ \"name\": \"Cash\", \"type\": \"CASH\", \"openingBalance\": \"500.50\" }");
		create(token, "{ \"name\": \"Card\", \"type\": \"CREDIT_CARD\", \"openingBalance\": \"-300\" }");
		create(token, "{ \"name\": \"Hidden\", \"type\": \"WALLET\", \"openingBalance\": \"99\", \"includeInTotal\": false }");
		String archived = idOf(create(token, "{ \"name\": \"Gone\", \"type\": \"BANK\", \"openingBalance\": \"1000\" }"));
		call(token, post("/api/v1/accounts/" + archived + "/archive"));

		call(token, get("/api/v1/accounts/summary"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalAssets").value("1500.50"))
			.andExpect(jsonPath("$.totalLiabilities").value("300.00"))
			.andExpect(jsonPath("$.netWorth").value("1200.50"))
			.andExpect(jsonPath("$.accountCount").value(3));
	}

	@Test
	void summary_withNoAccounts_isAllZero() throws Exception {
		String token = newUser();
		call(token, get("/api/v1/accounts/summary"))
			.andExpect(jsonPath("$.totalAssets").value("0.00"))
			.andExpect(jsonPath("$.netWorth").value("0.00"))
			.andExpect(jsonPath("$.accountCount").value(0));
	}

	// ----------------------------------------------------------------- reorder

	@Test
	void reorder_putsListedIdsFirst_inThatOrder() throws Exception {
		String token = newUser();
		String a = idOf(create(token, "{ \"name\": \"A\", \"type\": \"CASH\" }"));
		String b = idOf(create(token, "{ \"name\": \"B\", \"type\": \"CASH\" }"));
		String c = idOf(create(token, "{ \"name\": \"C\", \"type\": \"CASH\" }"));

		call(token, put("/api/v1/accounts/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"accountIds\": [\"%s\", \"%s\"] }".formatted(c, a)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].id").value(c))
			.andExpect(jsonPath("$[1].id").value(a))
			.andExpect(jsonPath("$[2].id").value(b));

		call(token, get("/api/v1/accounts")).andExpect(jsonPath("$[0].name").value("C"));
	}

	@Test
	void reorder_unknownId_returns404() throws Exception {
		String token = newUser();
		call(token, put("/api/v1/accounts/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"accountIds\": [\"%s\"] }".formatted(UUID.randomUUID())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
	}

	// ------------------------------------------------------------------ delete

	@Test
	void delete_thenGet_returns404() throws Exception {
		String token = newUser();
		String id = idOf(create(token, "{ \"name\": \"Temp\", \"type\": \"CASH\" }"));

		call(token, delete("/api/v1/accounts/" + id)).andExpect(status().isNoContent());
		call(token, get("/api/v1/accounts/" + id)).andExpect(status().isNotFound());
	}

	// -------------------------------------------------------- ownership (A vs B)

	@Test
	void otherUsersAccount_looksLikeItDoesNotExist() throws Exception {
		String tokenA = newUser();
		String tokenB = newUser();
		String idA = idOf(create(tokenA, "{ \"name\": \"Mine\", \"type\": \"BANK\", \"openingBalance\": \"100\" }"));

		call(tokenB, get("/api/v1/accounts/" + idA)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
		patchAccount(tokenB, idA, "{ \"name\": \"Stolen\" }").andExpect(status().isNotFound());
		patchAccount(tokenB, idA, "{ \"openingBalance\": \"999999\" }").andExpect(status().isNotFound());
		call(tokenB, post("/api/v1/accounts/" + idA + "/archive")).andExpect(status().isNotFound());
		call(tokenB, delete("/api/v1/accounts/" + idA)).andExpect(status().isNotFound());
		call(tokenB, put("/api/v1/accounts/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"accountIds\": [\"%s\"] }".formatted(idA))).andExpect(status().isNotFound());
		call(tokenB, get("/api/v1/accounts")).andExpect(jsonPath("$.length()").value(0));
		call(tokenB, get("/api/v1/accounts/summary")).andExpect(jsonPath("$.netWorth").value("0.00"));

		// A's account is untouched.
		call(tokenA, get("/api/v1/accounts/" + idA))
			.andExpect(jsonPath("$.name").value("Mine"))
			.andExpect(jsonPath("$.currentBalance").value("100.00"));
	}

	@Test
	void badUuidInPath_returns400() throws Exception {
		String token = newUser();
		call(token, get("/api/v1/accounts/not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	@Test
	void withoutToken_returns401() throws Exception {
		mockMvc.perform(get("/api/v1/accounts")).andExpect(status().isUnauthorized());
	}

	// ----------------------------------------------------------------- helpers

	private String newUser() throws Exception {
		String email = "acc-" + UUID.randomUUID() + "@mail.com";
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Account Test", "email": "%s", "password": "%s" }
				""".formatted(email, PASSWORD))).andExpect(status().isCreated());
		String json = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, PASSWORD)))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.accessToken");
	}

	private ResultActions create(String token, String body) throws Exception {
		return call(token, post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions patchAccount(String token, String id, String body) throws Exception {
		return call(token, patch("/api/v1/accounts/" + id).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

}
