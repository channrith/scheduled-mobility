package com.mobility.location.logging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerMapping;

@ExtendWith(OutputCaptureExtension.class)
class RequestLoggingFilterTest {

    final RequestLoggingFilter filter = new RequestLoggingFilter();

    @Test
    void logsCompletionWithoutTokensCoordinatesRawPathsOrPersonalData(CapturedOutput output) throws Exception {
        var request = new MockHttpServletRequest("GET", "/api/private-personal-path");
        request.addHeader("Authorization", "Bearer private-token");
        request.addHeader("X-Request-Id", "private-personal-header");
        request.setQueryString("token=private-token&latitude=11.5564");
        request.setContent("{\"latitude\":11.5564}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            req.setAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE, "/api/v1/drivers/me/presence");
            assertThat(MDC.get("request.id")).isEqualTo(response.getHeader("X-Request-Id"));
        });

        assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
        assertThat(output.getOut()).contains("method=GET", "route=/api/v1/drivers/me/presence", "status=200", "durationMs=")
                .doesNotContain("private-token", "11.5564", "private-personal-path", "private-personal-header");
        assertThat(MDC.get("request.id")).isNull();
    }

    @Test
    void keepsValidRequestIdAndRestoresPreviousMdc(CapturedOutput output) throws Exception {
        String id = UUID.randomUUID().toString();
        var request = new MockHttpServletRequest("GET", "/unknown-private-path");
        request.addHeader("X-Request-Id", id);
        var response = new MockHttpServletResponse();
        MDC.put("request.id", "outer-context");
        try {
            filter.doFilter(request, response, (req, res) -> response.setStatus(401));
            assertThat(response.getHeader("X-Request-Id")).isEqualTo(id);
            assertThat(MDC.get("request.id")).isEqualTo("outer-context");
            assertThat(output.getOut()).contains("status=401", "route=<unmatched>").doesNotContain("unknown-private-path");
        } finally {
            MDC.remove("request.id");
        }
    }

    @Test
    void unhandledFailureIsLoggedWithoutItsSensitiveExceptionMessage(CapturedOutput output) {
        var request = new MockHttpServletRequest("POST", "/private-path");
        var response = new MockHttpServletResponse();
        assertThatThrownBy(() -> filter.doFilter(request, response, (req, res) -> {
            throw new ServletException("private-exception-value");
        })).isInstanceOf(ServletException.class);
        assertThat(output.getOut()).contains("status=500").doesNotContain("private-exception-value", "private-path");
        assertThat(MDC.get("request.id")).isNull();
    }
}
