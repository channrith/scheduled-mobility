package com.mobility.core.booking.trip;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.Trips;
import com.mobility.core.booking.ride.Ride;
import com.mobility.core.booking.ride.RideRepository;
import com.mobility.core.booking.trip.TripService.Actor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TripsService implements Trips {

	/** For bookings made before the quote's driving time was kept. */
	static final Duration DEFAULT_DURATION = Duration.ofMinutes(45);

	private final TripService trips;

	private final TripRepository tripRepository;

	private final RideRepository rides;

	TripsService(TripService trips, TripRepository tripRepository, RideRepository rides) {
		this.trips = trips;
		this.tripRepository = tripRepository;
		this.rides = rides;
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TripInfo> find(UUID tripId) {
		return tripRepository.findById(tripId).map(t -> info(t, rides.findById(t.getRideId()).orElseThrow()));
	}

	@Override
	@Transactional(readOnly = true)
	public List<TripInfo> findAll(Collection<UUID> tripIds) {
		return infos(tripRepository.findAllById(tripIds));
	}

	@Override
	@Transactional(readOnly = true)
	public List<TripInfo> pickingUpBetween(Instant from, Instant to, Collection<TripStatus> statuses) {
		return infos(tripRepository.forOps(statuses, from, to, PageRequest.of(0, 2000, Sort.by("pickupAt"))).getContent());
	}

	@Override
	@Transactional
	public TripInfo assign(UUID tripId, UUID driverId, long expectedVersion, UUID actorUserId, String reason) {
		Trip trip = trips.assign(tripId, driverId, expectedVersion, new Actor(actorUserId, TripActor.OPS), reason);
		return info(trip, rides.findById(trip.getRideId()).orElseThrow());
	}

	@Override
	@Transactional
	public TripInfo unassign(UUID tripId, long expectedVersion, UUID actorUserId, String reason, boolean byDriver) {
		Trip trip = trips.unassign(tripId, expectedVersion, reason, new Actor(actorUserId, byDriver ? TripActor.DRIVER : TripActor.OPS));
		return info(trip, rides.findById(trip.getRideId()).orElseThrow());
	}

	private List<TripInfo> infos(List<Trip> list) {
		Map<UUID, Ride> byId = new java.util.HashMap<>();
		rides.findAllById(list.stream().map(Trip::getRideId).distinct().toList()).forEach(r -> byId.put(r.getId(), r));
		return list.stream()
			.sorted(java.util.Comparator.comparing(Trip::getPickupAt))
			.map(t -> info(t, byId.get(t.getRideId())))
			.toList();
	}

	private static TripInfo info(Trip t, Ride r) {
		Duration duration = r.getDurationSeconds() == null ? DEFAULT_DURATION : Duration.ofSeconds(r.getDurationSeconds());
		return new TripInfo(t.getId(), r.getId(), r.getType(), t.getStatus(), t.getPickupAt(), duration, r.getVehicleClass(),
				r.getPickupLat(), r.getPickupLng(), r.getDropoffLat(), r.getDropoffLng(), r.getCorporateId(), r.getFlightNo(),
				t.getDriverId(), t.getVersion());
	}
}
