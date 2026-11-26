package com.sm.spendly.category;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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

/** Categories over HTTP against real Postgres. Each test registers its own user. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CategoryIntegrationTest {

	private static final String PASSWORD = "correct-horse-42";

	@Autowired
	MockMvc mockMvc;

	// ---------------------------------------------------------------- defaults

	@Test
	void newUser_getsDefaultCategories_inOrder() throws Exception {
		String token = newUser();

		call(token, get("/api/v1/categories")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(19));
		call(token, get("/api/v1/categories?kind=EXPENSE"))
			.andExpect(jsonPath("$.length()").value(13))
			.andExpect(jsonPath("$[0].name").value("Food & Dining"))
			.andExpect(jsonPath("$[0].icon").value("fork-knife"))
			.andExpect(jsonPath("$[0].sortOrder").value(0))
			.andExpect(jsonPath("$[12].name").value("Other"));
		call(token, get("/api/v1/categories?kind=INCOME"))
			.andExpect(jsonPath("$.length()").value(6))
			.andExpect(jsonPath("$[0].name").value("Salary"))
			.andExpect(jsonPath("$[0].sortOrder").value(0));
	}

	@Test
	void list_unknownKind_returns400() throws Exception {
		String token = newUser();
		call(token, get("/api/v1/categories?kind=TRANSFER"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("INVALID_PARAMETER"));
	}

	// ------------------------------------------------------------------ create

	@Test
	void create_usesDefaults_andGoesToTheEndOfItsKind() throws Exception {
		String token = newUser();

		create(token, "{ \"name\": \"  Pets \", \"kind\": \"EXPENSE\" }")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("Pets"))
			.andExpect(jsonPath("$.icon").value("tag"))
			.andExpect(jsonPath("$.color").value("#6B7280"))
			.andExpect(jsonPath("$.archived").value(false))
			.andExpect(jsonPath("$.sortOrder").value(13));
	}

	@Test
	void names_areUniquePerKind_ignoringCase() throws Exception {
		String token = newUser();

		create(token, "{ \"name\": \"food & dining\", \"kind\": \"EXPENSE\" }")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
		// The same name on the other side is fine.
		create(token, "{ \"name\": \"Food & Dining\", \"kind\": \"INCOME\" }").andExpect(status().isCreated());
	}

	@Test
	void create_invalidInput_returns400() throws Exception {
		String token = newUser();
		create(token, "{ \"name\": \"\", \"kind\": null, \"color\": \"red\" }")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
			.andExpect(jsonPath("$.errors.length()").value(3));
	}

	// ------------------------------------------------------- update / archive

	@Test
	void patch_changesNameIconColor() throws Exception {
		String token = newUser();
		String id = idOf(create(token, "{ \"name\": \"Pets\", \"kind\": \"EXPENSE\" }"));

		call(token, patch("/api/v1/categories/" + id).contentType(MediaType.APPLICATION_JSON)
			.content("{ \"name\": \"Pet care\", \"icon\": \"paw-print\", \"color\": \"#f59e0b\" }"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("Pet care"))
			.andExpect(jsonPath("$.icon").value("paw-print"))
			.andExpect(jsonPath("$.color").value("#F59E0B"))
			.andExpect(jsonPath("$.kind").value("EXPENSE"));
	}

	@Test
	void archive_hidesFromList_andBlocksEdits() throws Exception {
		String token = newUser();
		String id = idOf(create(token, "{ \"name\": \"Pets\", \"kind\": \"EXPENSE\" }"));

		call(token, post("/api/v1/categories/" + id + "/archive")).andExpect(jsonPath("$.archived").value(true));

		call(token, get("/api/v1/categories?kind=EXPENSE")).andExpect(jsonPath("$.length()").value(13));
		call(token, get("/api/v1/categories?kind=EXPENSE&includeArchived=true")).andExpect(jsonPath("$.length()").value(14));
		call(token, patch("/api/v1/categories/" + id).contentType(MediaType.APPLICATION_JSON).content("{ \"name\": \"X\" }"))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("CATEGORY_ARCHIVED"));

		// Archived name is free; then the old one can't come back.
		create(token, "{ \"name\": \"pets\", \"kind\": \"EXPENSE\" }").andExpect(status().isCreated());
		call(token, post("/api/v1/categories/" + id + "/unarchive"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"));
	}

	// ----------------------------------------------------------------- reorder

	@Test
	void reorder_movesListedToTheTop_onlyWithinTheKind() throws Exception {
		String token = newUser();
		List<String> expenseIds = ids(call(token, get("/api/v1/categories?kind=EXPENSE")));
		String other = expenseIds.get(12);
		String food = expenseIds.get(0);

		call(token, put("/api/v1/categories/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"kind\": \"EXPENSE\", \"categoryIds\": [\"%s\", \"%s\"] }".formatted(other, food)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].name").value("Other"))
			.andExpect(jsonPath("$[1].name").value("Food & Dining"))
			.andExpect(jsonPath("$[2].name").value("Groceries"));

		// Income order untouched.
		call(token, get("/api/v1/categories?kind=INCOME")).andExpect(jsonPath("$[0].name").value("Salary"));
	}

	@Test
	void reorder_idOfOtherKind_returns404() throws Exception {
		String token = newUser();
		String salary = ids(call(token, get("/api/v1/categories?kind=INCOME"))).get(0);

		call(token, put("/api/v1/categories/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"kind\": \"EXPENSE\", \"categoryIds\": [\"%s\"] }".formatted(salary)))
			.andExpect(status().isNotFound());
	}

	// ------------------------------------------------------------------ delete

	@Test
	void delete_withBadReplacement_returns422_andKeepsTheCategory() throws Exception {
		String token = newUser();
		List<String> expense = ids(call(token, get("/api/v1/categories?kind=EXPENSE")));
		List<String> income = ids(call(token, get("/api/v1/categories?kind=INCOME")));
		String food = expense.get(0);
		String archived = expense.get(1);
		call(token, post("/api/v1/categories/" + archived + "/archive"));

		for (String bad : List.of(food, income.get(0), archived, UUID.randomUUID().toString())) {
			call(token, delete("/api/v1/categories/" + food + "?replaceWith=" + bad))
				.andExpect(status().isUnprocessableContent())
				.andExpect(jsonPath("$.code").value("INVALID_REPLACEMENT_CATEGORY"));
		}
		call(token, get("/api/v1/categories/" + food)).andExpect(status().isOk());
	}

	@Test
	void delete_withValidReplacement_orNone_returns204() throws Exception {
		String token = newUser();
		List<String> expense = ids(call(token, get("/api/v1/categories?kind=EXPENSE")));

		call(token, delete("/api/v1/categories/" + expense.get(0) + "?replaceWith=" + expense.get(1)))
			.andExpect(status().isNoContent());
		call(token, delete("/api/v1/categories/" + expense.get(2))).andExpect(status().isNoContent());

		call(token, get("/api/v1/categories/" + expense.get(0))).andExpect(status().isNotFound());
		call(token, get("/api/v1/categories?kind=EXPENSE")).andExpect(jsonPath("$.length()").value(11));
	}

	// -------------------------------------------------------- ownership (A vs B)

	@Test
	void otherUsersCategory_looksLikeItDoesNotExist() throws Exception {
		String tokenA = newUser();
		String tokenB = newUser();
		String idA = ids(call(tokenA, get("/api/v1/categories?kind=EXPENSE"))).get(0);
		String idB = ids(call(tokenB, get("/api/v1/categories?kind=EXPENSE"))).get(0);

		call(tokenB, get("/api/v1/categories/" + idA)).andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
		call(tokenB, patch("/api/v1/categories/" + idA).contentType(MediaType.APPLICATION_JSON)
			.content("{ \"name\": \"Hacked\" }")).andExpect(status().isNotFound());
		call(tokenB, post("/api/v1/categories/" + idA + "/archive")).andExpect(status().isNotFound());
		call(tokenB, delete("/api/v1/categories/" + idA)).andExpect(status().isNotFound());
		// B can't use A's category as a replacement either.
		call(tokenB, delete("/api/v1/categories/" + idB + "?replaceWith=" + idA))
			.andExpect(status().isUnprocessableContent());
		call(tokenB, put("/api/v1/categories/order").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"kind\": \"EXPENSE\", \"categoryIds\": [\"%s\"] }".formatted(idA)))
			.andExpect(status().isNotFound());

		call(tokenA, get("/api/v1/categories/" + idA))
			.andExpect(jsonPath("$.name").value("Food & Dining"))
			.andExpect(jsonPath("$.archived").value(false));
	}

	// ----------------------------------------------------------------- helpers

	private String newUser() throws Exception {
		String email = "cat-" + UUID.randomUUID() + "@mail.com";
		mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON).content("""
				{ "name": "Category Test", "email": "%s", "password": "%s" }
				""".formatted(email, PASSWORD))).andExpect(status().isCreated());
		String json = mockMvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
			.content("{ \"email\": \"%s\", \"password\": \"%s\" }".formatted(email, PASSWORD)))
			.andReturn().getResponse().getContentAsString();
		return JsonPath.read(json, "$.accessToken");
	}

	private ResultActions create(String token, String body) throws Exception {
		return call(token, post("/api/v1/categories").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions call(String token, MockHttpServletRequestBuilder request) throws Exception {
		return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
	}

	private static String idOf(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$.id");
	}

	private static List<String> ids(ResultActions result) throws Exception {
		return JsonPath.read(result.andReturn().getResponse().getContentAsString(), "$[*].id");
	}

}
