package com.mobility.core.shared.web;

import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Renders all API errors as RFC 7807 problem+json with localized title and detail. */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private final Problems problems;

	private final MessageSource messages;

	ApiExceptionHandler(Problems problems, MessageSource messages) {
		this.problems = problems;
		this.messages = messages;
	}

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> handleApiException(ApiException ex) {
		ProblemDetail problem = problems.build(ex.getStatus(), ex.getCode(), LocaleContextHolder.getLocale(),
				ex.getArgs());
		ex.getProperties().forEach(problem::setProperty);
		return ResponseEntity.status(ex.getStatus()).headers(ex.getHeaders()).body(problem);
	}

	/** Two staff members changed the same record at once; the client should reload and retry. */
	@ExceptionHandler(OptimisticLockingFailureException.class)
	ResponseEntity<ProblemDetail> handleOptimisticLock(OptimisticLockingFailureException ex) {
		return ResponseEntity.status(HttpStatus.CONFLICT)
			.body(problems.build(HttpStatus.CONFLICT, "concurrent-update", LocaleContextHolder.getLocale()));
	}

	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		Locale locale = LocaleContextHolder.getLocale();
		ProblemDetail problem = problems.build(HttpStatus.BAD_REQUEST, "validation", locale);
		List<Map<String, String>> errors = ex.getBindingResult()
			.getFieldErrors()
			.stream()
			.map(error -> Map.of("field", error.getField(), "message", messages.getMessage(error, locale)))
			.toList();
		problem.setProperty("errors", errors);
		return ResponseEntity.badRequest().body(problem);
	}
}
