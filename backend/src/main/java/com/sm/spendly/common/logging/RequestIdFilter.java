package com.sm.spendly.common.logging;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request an id, puts it in the logging MDC so every log line of the request
 * shows it, and returns it in the {@code X-Request-Id} response header.
 *
 * <p>Runs first among all filters, so even a request rejected by a later filter (security,
 * from step 004) has an id in its logs.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Request-Id";

	/** MDC key. The log pattern in application.properties prints it as %X{requestId}. */
	public static final String MDC_KEY = "requestId";

	// Letters, digits and dashes only: no spaces or line breaks, so a client cannot
	// forge extra log lines (log injection). Length bounded so logs stay readable.
	private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9-]{8,64}$");

	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
			FilterChain chain) throws ServletException, IOException {
		String requestId = resolveRequestId(request.getHeader(HEADER));
		long start = System.nanoTime();

		MDC.put(MDC_KEY, requestId);
		// Set before the chain runs: once the body is written the response is committed
		// and headers can no longer be added.
		response.setHeader(HEADER, requestId);
		try {
			chain.doFilter(request, response);
		}
		finally {
			logSummary(request, response, start);
			// The thread goes back to Tomcat's pool and serves another request next.
			// Without this, that request would log with our id.
			MDC.remove(MDC_KEY);
		}
	}

	private static String resolveRequestId(String incoming) {
		if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
			return incoming;
		}
		if (incoming != null) {
			// The value itself is never printed: it is exactly what we refuse to trust.
			log.debug("Ignored unsafe {} header", HEADER);
		}
		return UUID.randomUUID().toString();
	}

	private static void logSummary(HttpServletRequest request, HttpServletResponse response, long start) {
		long millis = (System.nanoTime() - start) / 1_000_000;
		// getRequestURI() is the path without the query string. Query strings can hold
		// search text or an email, so they are never logged.
		String path = request.getRequestURI();
		if (path.startsWith("/actuator")) {
			// Health checks hit this every few seconds; keep them out of the INFO log.
			log.debug("{} {} -> {} in {} ms", request.getMethod(), path, response.getStatus(), millis);
		}
		else {
			log.info("{} {} -> {} in {} ms", request.getMethod(), path, response.getStatus(), millis);
		}
	}

}
