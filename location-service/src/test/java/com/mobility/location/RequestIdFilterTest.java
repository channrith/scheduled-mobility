package com.mobility.location;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestIdFilterTest {

	final RequestIdFilter filter = new RequestIdFilter();

	String idSeenDuring(MockHttpServletRequest request) throws Exception {
		AtomicReference<String> seen = new AtomicReference<>();
		filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> seen.set(MDC.get(RequestIdFilter.MDC_KEY)));
		return seen.get();
	}

	@Test
	void logsTheGatewaysRequestId() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/locations");
		request.addHeader("X-Request-Id", "app-1234abcd");

		assertThat(idSeenDuring(request)).isEqualTo("app-1234abcd");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void makesOneUpWhenMissingOrOdd() throws Exception {
		assertThat(idSeenDuring(new MockHttpServletRequest("POST", "/api/v1/locations"))).matches("[0-9a-f-]{36}");

		MockHttpServletRequest odd = new MockHttpServletRequest("POST", "/api/v1/locations");
		odd.addHeader("X-Request-Id", "short");
		assertThat(idSeenDuring(odd)).matches("[0-9a-f-]{36}");
	}
}
