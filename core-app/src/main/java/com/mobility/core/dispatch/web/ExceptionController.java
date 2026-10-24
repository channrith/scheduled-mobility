package com.mobility.core.dispatch.web;

import java.util.List;
import java.util.UUID;

import com.mobility.core.dispatch.issue.ExceptionService;
import com.mobility.core.dispatch.issue.ExceptionStatus;
import com.mobility.core.dispatch.web.DispatchViews.ExceptionView;
import com.mobility.core.identity.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dispatch/exceptions")
@PreAuthorize("hasAnyRole('ADMIN', 'DISPATCHER')")
@Tag(name = "Dispatch · Exceptions", description = "The exception queue: unassigned, unconfirmed, declined, at-risk trips")
class ExceptionController {

	private final ExceptionService exceptions;

	private final CurrentUserProvider currentUser;

	ExceptionController(ExceptionService exceptions, CurrentUserProvider currentUser) {
		this.exceptions = exceptions;
		this.currentUser = currentUser;
	}

	record TakeExceptionRequest(@NotNull Long version) {
	}

	record ResolveExceptionRequest(@NotNull Long version, String note) {
	}

	@Operation(summary = "Exception queue",
			description = "Severity first (CRITICAL, HIGH, MEDIUM, LOW), then the soonest pickup. Default: OPEN and ACKNOWLEDGED.")
	@GetMapping
	List<ExceptionView> queue(@RequestParam(required = false) List<ExceptionStatus> status) {
		return exceptions.queue(status).stream().map(DispatchViews::exception).toList();
	}

	@Operation(summary = "Take it (assign to me → ACKNOWLEDGED)")
	@PostMapping("/{exceptionId}/take")
	ExceptionView take(@PathVariable UUID exceptionId, @Valid @RequestBody TakeExceptionRequest request) {
		return DispatchViews.exception(exceptions.take(exceptionId, request.version(), currentUser.require().userId()));
	}

	@Operation(summary = "Resolve", description = "`note` (≥ 5 characters): what was done.")
	@PostMapping("/{exceptionId}/resolve")
	ExceptionView resolve(@PathVariable UUID exceptionId, @Valid @RequestBody ResolveExceptionRequest request) {
		return DispatchViews.exception(exceptions.resolve(exceptionId, request.version(), request.note(), currentUser.require().userId()));
	}
}
