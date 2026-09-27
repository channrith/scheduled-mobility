package com.mobility.core.shared.web;

import java.io.IOException;
import java.util.Locale;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.json.JsonMapper;

/** Builds localized problem details; also writes them from filters that run outside Spring MVC. */
@Component
public class Problems {

	private final MessageSource messages;

	private final LocaleResolver localeResolver;

	private final JsonMapper jsonMapper;

	Problems(MessageSource messages, LocaleResolver localeResolver, JsonMapper jsonMapper) {
		this.messages = messages;
		this.localeResolver = localeResolver;
		this.jsonMapper = jsonMapper;
	}

	public ProblemDetail build(HttpStatus status, String code, Locale locale, Object... args) {
		ProblemDetail problem = ProblemDetail.forStatus(status);
		problem.setTitle(messages.getMessage("error.title." + status.value(), null, status.getReasonPhrase(), locale));
		problem.setDetail(messages.getMessage("error." + code, args, code, locale));
		problem.setProperty("code", code);
		return problem;
	}

	/** For servlet filters and security handlers, where Spring MVC has not resolved the locale. */
	public void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String code,
			Object... args) throws IOException {
		ProblemDetail problem = build(status, code, localeResolver.resolveLocale(request), args);
		problem.setInstance(java.net.URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		response.setCharacterEncoding("UTF-8");
		jsonMapper.writeValue(response.getOutputStream(), problem);
	}
}
