package com.mobility.core.dispatch.suggest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.Trips.TripInfo;
import com.mobility.core.dispatch.DispatchProperties;
import com.mobility.core.dispatch.assignment.AssignmentService;
import com.mobility.core.dispatch.assignment.ReasonCode;
import com.mobility.core.dispatch.shift.ShiftBlock;
import com.mobility.core.dispatch.shift.ShiftBlockRepository;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Commitment;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.DayPlan;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.DayProblem;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Driver;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Point;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Rules;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.TripToPlan;
import com.mobility.core.dispatch.suggest.AssignmentStrategy.Window;
import com.mobility.core.driver.DriverDirectory;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Suggestions for a day (Phnom Penh date), and accepting them as manual assignments with SUGGESTION_ACCEPTED. */
@Service
public class SuggestionService {

	/** @param error null when it was applied; otherwise the problem code (e.g. concurrent-update) */
	public record Applied(UUID tripId, String error) {
	}

	public record Accept(UUID tripId, UUID driverId, UUID backupDriverId, long tripVersion) {
	}

	private final Trips trips;

	private final DriverDirectory drivers;

	private final ShiftBlockRepository shifts;

	private final AssignmentStrategy strategy;

	private final AssignmentService assignments;

	private final DispatchProperties properties;

	private final TransactionTemplate transactions;

	SuggestionService(Trips trips, DriverDirectory drivers, ShiftBlockRepository shifts, AssignmentStrategy strategy,
			AssignmentService assignments, DispatchProperties properties, TransactionTemplate transactions) {
		this.trips = trips;
		this.drivers = drivers;
		this.shifts = shifts;
		this.strategy = strategy;
		this.assignments = assignments;
		this.properties = properties;
		this.transactions = transactions;
	}

	@Transactional(readOnly = true)
	public DayPlan forDay(LocalDate day) {
		Instant from = day.atStartOfDay(BusinessTime.ZONE).toInstant();
		Instant to = day.plusDays(1).atStartOfDay(BusinessTime.ZONE).toInstant();
		List<TripInfo> dayTrips = trips.pickingUpBetween(from, to, TripStatus.OPEN);
		List<TripToPlan> open = dayTrips.stream().filter(t -> t.status() == TripStatus.SCHEDULED)
			.map(t -> new TripToPlan(t.tripId(), t.vehicleClass(), t.pickupAt(), t.dropoffAt(), new Point(t.pickupLat(), t.pickupLng()),
					new Point(t.dropoffLat(), t.dropoffLng())))
			.toList();
		List<Commitment> held = dayTrips.stream().filter(t -> t.driverId() != null)
			.map(t -> new Commitment(t.driverId(), t.tripId(), t.pickupAt(), t.dropoffAt(), new Point(t.pickupLat(), t.pickupLng()),
					new Point(t.dropoffLat(), t.dropoffLng())))
			.toList();
		Map<UUID, List<Window>> windows = shifts.overlapping(from, to).stream()
			.collect(Collectors.groupingBy(ShiftBlock::getDriverId,
					Collectors.mapping(s -> new Window(s.getStartsAt(), s.getEndsAt()), Collectors.toList())));
		List<Driver> pool = drivers.approved().stream()
			.filter(d -> d.vehicle() != null)
			.map(d -> new Driver(d.driverId(), d.fullName(), d.vehicle().vehicleClass(), HomeZoneCentres.of(d.homeZone()),
					windows.getOrDefault(d.driverId(), List.of())))
			.toList();
		Rules rules = new Rules(properties.planning().buffer(), properties.planning().fairnessMinutes());
		return strategy.plan(new DayProblem(open, pool, held, rules));
	}

	/** Each item in its own transaction: one conflict does not undo the others. */
	public List<Applied> accept(List<Accept> items, UUID actorUserId) {
		List<Applied> results = new ArrayList<>();
		for (Accept item : items) {
			try {
				transactions.executeWithoutResult(s -> assignments.assign(item.tripId(), item.driverId(), item.backupDriverId(),
						ReasonCode.SUGGESTION_ACCEPTED, null, item.tripVersion(), actorUserId));
				results.add(new Applied(item.tripId(), null));
			}
			catch (ApiException e) {
				results.add(new Applied(item.tripId(), e.getCode()));
			}
			catch (org.springframework.dao.OptimisticLockingFailureException e) {
				results.add(new Applied(item.tripId(), "concurrent-update"));
			}
		}
		return results;
	}
}
