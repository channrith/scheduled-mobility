package com.mobility.core.shared.web;

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
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
		request.addHeader("X-Request-Id", "a87d92dd-846c-45e9-88b2-818acb2011cf");

		assertThat(idSeenDuring(request)).isEqualTo("a87d92dd-846c-45e9-88b2-818acb2011cf");
		assertThat(MDC.get(RequestIdFilter.MDC_KEY)).isNull();
	}

	@Test
	void makesOneUpWhenMissingOrOdd() throws Exception {
		assertThat(idSeenDuring(new MockHttpServletRequest("GET", "/api/v1/me"))).matches("[0-9a-f-]{36}");

		MockHttpServletRequest odd = new MockHttpServletRequest("GET", "/api/v1/me");
		odd.addHeader("X-Request-Id", "x\n{\"level\":\"ERROR\"}");
		assertThat(idSeenDuring(odd)).matches("[0-9a-f-]{36}");
	}
}
