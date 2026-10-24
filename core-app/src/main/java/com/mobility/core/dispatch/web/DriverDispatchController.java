package com.mobility.core.dispatch.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mobility.core.dispatch.assignment.AssignmentService;
import com.mobility.core.dispatch.shift.ShiftService;
import com.mobility.core.dispatch.web.DispatchViews.DispatchAssignmentView;
import com.mobility.core.dispatch.web.DispatchViews.ShiftView;
import com.mobility.core.identity.CurrentUserProvider;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/driver")
@PreAuthorize("hasRole('DRIVER')")
@Tag(name = "Driver · Dispatch", description = "A driver's shift blocks and confirming assigned trips")
class DriverDispatchController {

	private final ShiftService shifts;

	private final AssignmentService assignments;

	private final CurrentUserProvider currentUser;

	DriverDispatchController(ShiftService shifts, AssignmentService assignments, CurrentUserProvider currentUser) {
		this.shifts = shifts;
		this.assignments = assignments;
		this.currentUser = currentUser;
	}

	record ShiftRequest(@NotNull Instant startsAt, @NotNull Instant endsAt) {
	}

	record DeclineRequest(@Size(max = 500) String reason) {
	}

	@Operation(summary = "My shift blocks", description = "From 12 hours ago on.")
	@GetMapping("/shifts")
	List<ShiftView> mine() {
		return shifts.mine(me()).stream().map(DispatchViews::shift).toList();
	}

	@Operation(summary = "Declare a shift block",
			description = "At most 14 hours, starting within 30 days; blocks may not overlap (`dispatch.shift-overlap`).")
	@PostMapping("/shifts")
	ResponseEntity<ShiftView> create(@Valid @RequestBody ShiftRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(DispatchViews.shift(shifts.create(me(), request.startsAt(), request.endsAt())));
	}

	@Operation(summary = "Cancel a shift block", description = "Not while I have a trip inside it (`dispatch.shift-has-trips`).")
	@DeleteMapping("/shifts/{shiftId}")
	ShiftView cancel(@PathVariable UUID shiftId) {
		return DispatchViews.shift(shifts.cancel(me(), shiftId));
	}

	@Operation(summary = "Confirm an assigned trip", description = "As its driver or its backup.")
	@PostMapping("/trips/{tripId}/confirm")
	DispatchAssignmentView confirm(@PathVariable UUID tripId) {
		return DispatchViews.assignment(assignments.confirm(tripId, me()));
	}

	@Operation(summary = "Decline an assigned trip", description = "Takes me off it and puts it in the dispatchers' queue.")
	@PostMapping("/trips/{tripId}/decline")
	DispatchAssignmentView decline(@PathVariable UUID tripId, @Valid @RequestBody DeclineRequest request) {
		return DispatchViews.assignment(assignments.decline(tripId, me(), request.reason()));
	}

	private UUID me() {
		return currentUser.require().userId();
	}
}
