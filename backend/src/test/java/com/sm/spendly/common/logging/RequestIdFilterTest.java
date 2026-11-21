package com.sm.spendly.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Plain unit test: no Spring context at all. The filter is an ordinary object, so we
 * create it with {@code new} and hand it fake request/response objects.
 */
class RequestIdFilterTest {

	private final RequestIdFilter filter = new RequestIdFilter();

	@Test
	void generatesRequestId_whenHeaderMissing() throws Exception {
		MockHttpServletResponse response = run(new MockHttpServletRequest("GET", "/api/v1/x"));

		String id = response.getHeader(RequestIdFilter.HEADER);
		assertThat(id).isNotNull();
		// Throws if the value is not a UUID.
		assertThat(UUID.fromString(id).toString()).isEqualTo(id);
	}

	@Test
	void reusesRequestId_whenHeaderIsSafe() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
		request.addHeader(RequestIdFilter.HEADER, "abc12345-xyz");

		MockHttpServletResponse response = run(request);

		assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("abc12345-xyz");
	}

	@Test
	void ignoresRequestId_whenHeaderIsUnsafe() throws Exception {
		for (String unsafe : new String[] { "bad\nvalue-12345", "a".repeat(200), "bad!!", "short" }) {
			MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/x");
			request.addHeader(RequestIdFilter.HEADER, unsafe);

			String id = run(request).getHeader(RequestIdFilter.HEADER);

			assertThat(id).isNotEqualTo(unsafe);
			assertThat(UUID.fromString(id).toString()).isEqualTo(id);
		}
	}

	@Test
	void putsIdInMdcDuringRequest_andClearsItAfter() throws Exception {
		AtomicReference<String> seenInsideRequest = new AtomicReference<>();
		// The "chain" stands in for the controller: it records what MDC held while running.
		MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
			@Override
			protected void service(jakarta.servlet.http.HttpServletRequest req,
					jakarta.servlet.http.HttpServletResponse res) {
				seenInsideRequest.set(MDC.get(RequestIdFilter.MDC_KEY));
			}
		});
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), response, chain);

		assertThat(seenInsideRequest.get()).isEqualTo(response.getHeader(RequestIdFilter.HEADER));
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void clearsMdc_evenWhenControllerThrows() throws Exception {
		MockFilterChain chain = new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
			@Override
			protected void service(jakarta.servlet.http.HttpServletRequest req,
					jakarta.servlet.http.HttpServletResponse res) {
				throw new IllegalStateException("boom");
			}
		});

		try {
			filter.doFilter(new MockHttpServletRequest("GET", "/api/v1/x"), new MockHttpServletResponse(), chain);
		}
		catch (IllegalStateException expected) {
			// the filter must not swallow it
		}

		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	private MockHttpServletResponse run(MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, new MockFilterChain());
		return response;
	}

}
