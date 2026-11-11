package com.mobility.core.booking.ride;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.booking.BookingEvents.RideBooked;
import com.mobility.core.booking.BookingPolicy;
import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.booking.TripStatus;
import com.mobility.core.booking.trip.Trip;
import com.mobility.core.booking.trip.TripActor;
import com.mobility.core.booking.trip.TripRepository;
import com.mobility.core.booking.trip.TripService;
import com.mobility.core.booking.trip.TripService.Actor;
import com.mobility.core.corporate.CorporateMembers;
import com.mobility.core.corporate.CorporateMembers.CostCenterSummary;
import com.mobility.core.corporate.CorporateMembers.MemberSummary;
import com.mobility.core.corporate.PolicyChecker;
import com.mobility.core.corporate.PolicyChecker.PolicyDecision;
import com.mobility.core.corporate.RideRequest;
import com.mobility.core.identity.Authz;
import com.mobility.core.identity.CurrentUser;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.pricing.FareModel;
import com.mobility.core.pricing.FareQuotes;
import com.mobility.core.pricing.FareQuotes.QuoteSummary;
import com.mobility.core.pricing.PickupTiming;
import com.mobility.core.shared.outbox.Outbox;
import com.mobility.core.shared.time.StoredTime;
import com.mobility.core.shared.web.ApiException;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Creates quoted or explicitly metered bookings; route, timing and fare remain
 * backend-authoritative.
 */
@Service
public class BookingService {

    public record RecurrenceInput(Set<DayOfWeek> days, LocalDate until) {}

    /**
     * @param riderMemberId corporate rides: the member riding (default: the booker's own
     *     membership)
     */
    public record BookingRequest(
            UUID quoteId,
            BookingType type,
            String flightNo,
            RecurrenceInput recurrence,
            String pickupNotes,
            String pickupVoiceNoteKey,
            PaymentMethod paymentMethod,
            UUID costCenterId,
            UUID riderMemberId,
            Pickup pickup,
            com.mobility.core.driver.VehicleClass vehicleClass,
            Instant pickupAt,
            String meetingPoint,
            Integer contractVersion,
            FareModel fareModel) {
        public BookingRequest(
                UUID quoteId,
                BookingType type,
                String flightNo,
                RecurrenceInput recurrence,
                String pickupNotes,
                String pickupVoiceNoteKey,
                PaymentMethod paymentMethod,
                UUID costCenterId,
                UUID riderMemberId) {
            this(
                    quoteId,
                    type,
                    flightNo,
                    recurrence,
                    pickupNotes,
                    pickupVoiceNoteKey,
                    paymentMethod,
                    costCenterId,
                    riderMemberId,
                    null,
                    null,
                    null,
                    null,
                    null,
                    FareModel.QUOTED);
        }
    }

    public record Pickup(UUID placeId, Double latitude, Double longitude) {}

    public record Booked(Ride ride, List<Trip> trips) {}

    /**
     * IATA airline code (2 letters/digits) + 1–4 digits + an optional suffix, e.g. K6 123, PG932.
     */
    static final Pattern FLIGHT = Pattern.compile("^[A-Z0-9]{2}\\s?\\d{1,4}[A-Z]?$");

    static final Pattern VOICE_KEY = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.-]{0,199}$");

    static final int MAX_NOTES = 500;

    private final RideRepository rides;

    private final TripRepository trips;

    private final TripService tripService;

    private final com.mobility.core.pricing.MeteredPricing meteredPricing;
    private final FareQuotes quotes;

    private final CorporateMembers members;

    private final PolicyChecker policies;

    private final CurrentUserProvider currentUser;

    private final Authz authz;

    private final Outbox outbox;

    private final AuditLog auditLog;

    private final Clock clock;
    private final BookingPolicy bookingPolicy;

    BookingService(
            RideRepository rides,
            TripRepository trips,
            TripService tripService,
            FareQuotes quotes,
            CorporateMembers members,
            PolicyChecker policies,
            CurrentUserProvider currentUser,
            Authz authz,
            Outbox outbox,
            AuditLog auditLog,
            Clock clock,
            com.mobility.core.pricing.MeteredPricing meteredPricing,
            BookingPolicy bookingPolicy) {
        this.bookingPolicy = bookingPolicy;
        this.meteredPricing = meteredPricing;
        this.rides = rides;
        this.trips = trips;
        this.tripService = tripService;
        this.quotes = quotes;
        this.members = members;
        this.policies = policies;
        this.currentUser = currentUser;
        this.authz = authz;
        this.outbox = outbox;
        this.auditLog = auditLog;
        this.clock = clock;
    }

    @Transactional
    public Booked book(BookingRequest r) {
        CurrentUser user = currentUser.require();
        Instant now = StoredTime.of(clock.instant());
        BookingType requestedType = r.type() == null ? BookingType.SCHEDULED : r.type();
        FareModel fareModel = r.fareModel() == null ? FareModel.QUOTED : r.fareModel();
        validateCombination(r, requestedType, fareModel);
        bookingPolicy.requireEnabled(requestedType, fareModel);
        if (fareModel == FareModel.METERED) {
            return bookMetered(r, user, now);
        }
        if (r.quoteId() == null) throw invalid("quoteId");
        if (r.pickup() != null || r.pickupAt() != null || r.vehicleClass() != null)
            throw invalid("quoteOverrides");
        QuoteSummary quote =
                quotes.find(r.quoteId())
                        .filter(q -> q.requestedBy().equals(user.userId()))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND, "booking.quote-not-found"));
        if (!now.isBefore(quote.expiresAt())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.quote-expired");
        }
        if (rides.existsByQuoteId(quote.quoteId())) {
            throw new ApiException(HttpStatus.CONFLICT, "booking.quote-used");
        }
        BookingType type = r.type() == null ? BookingType.SCHEDULED : r.type();
        String flightNo = null;
        List<Instant> pickups = List.of(quote.pickupAt());
        switch (type) {
            case ON_DEMAND -> {
                if (quote.corporateId() != null || quote.pickupTiming() != PickupTiming.NOW)
                    throw combination("quoteId");
                pickups = List.of(now);
            }
            case AIRPORT -> {
                flightNo =
                        r.flightNo() == null
                                ? ""
                                : r.flightNo()
                                        .strip()
                                        .toUpperCase(java.util.Locale.ROOT)
                                        .replaceAll("\\s+", " ");
                if (!FLIGHT.matcher(flightNo).matches()) {
                    throw invalid("flightNo");
                }
                if (!quote.airport()) {
                    throw new ApiException(
                            HttpStatus.UNPROCESSABLE_CONTENT, "booking.not-an-airport-trip");
                }
            }
            case RECURRING -> {
                if (r.recurrence() == null) {
                    throw invalid("recurrence");
                }
                pickups =
                        Recurrence.pickups(
                                quote.pickupAt(), r.recurrence().days(), r.recurrence().until());
            }
            case SCHEDULED -> {}
        }
        if (type == BookingType.SCHEDULED || type == BookingType.AIRPORT) {
            if (quote.pickupTiming() != PickupTiming.SCHEDULED) throw combination("quoteId");
            bookingPolicy.validateSchedule(quote.pickupAt(), now);
        }
        if (type == BookingType.AIRPORT
                && (Integer.valueOf(2).equals(r.contractVersion())
                        || r.fareModel() != null
                        || r.meetingPoint() != null)) {
            if (r.meetingPoint() == null
                    || r.meetingPoint().isBlank()
                    || r.meetingPoint().length() > 200) throw invalid("meetingPoint");
            meteredPricing.prepare(
                    quote.pickupPlaceId(),
                    quote.pickupLat(),
                    quote.pickupLng(),
                    quote.vehicleClass(),
                    quote.pickupAt(),
                    true);
        }
        PaymentMethod payment = payment(r.paymentMethod(), quote);
        String notes =
                r.pickupNotes() == null || r.pickupNotes().isBlank()
                        ? null
                        : r.pickupNotes().strip();
        if (notes != null && notes.length() > MAX_NOTES) {
            throw invalid("pickupNotes");
        }
        String voiceKey =
                r.pickupVoiceNoteKey() == null || r.pickupVoiceNoteKey().isBlank()
                        ? null
                        : r.pickupVoiceNoteKey().strip();
        if (voiceKey != null && !VOICE_KEY.matcher(voiceKey).matches()) {
            throw invalid("pickupVoiceNoteKey");
        }

        UUID riderUserId = user.userId();
        UUID memberId = null;
        UUID costCenterId = null;
        if (quote.corporateId() != null) {
            UUID corporateId = quote.corporateId();
            if (!authz.canAccessCorporate(corporateId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "auth.forbidden");
            }
            MemberSummary rider =
                    (r.riderMemberId() != null
                                    ? members.member(corporateId, r.riderMemberId())
                                    : members.memberByUser(corporateId, user.userId()))
                            .filter(MemberSummary::active)
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    HttpStatus.UNPROCESSABLE_CONTENT,
                                                    "booking.rider-invalid"));
            riderUserId = rider.userId();
            memberId = rider.memberId();
            costCenterId = r.costCenterId() != null ? r.costCenterId() : rider.costCenterId();
            if (costCenterId != null) {
                CostCenterSummary center =
                        members.costCenter(corporateId, costCenterId)
                                .filter(CostCenterSummary::active)
                                .orElseThrow(
                                        () ->
                                                new ApiException(
                                                        HttpStatus.UNPROCESSABLE_CONTENT,
                                                        "booking.cost-center-invalid"));
                costCenterId = center.costCenterId();
            }
            checkPolicy(corporateId, riderUserId, quote, pickups);
        } else if (r.riderMemberId() != null || r.costCenterId() != null) {
            throw invalid(r.riderMemberId() != null ? "riderMemberId" : "costCenterId");
        }

        Ride ride =
                new Ride(
                        new Ride.Details(
                                type,
                                FareModel.QUOTED,
                                user.userId(),
                                riderUserId,
                                quote.corporateId(),
                                memberId,
                                costCenterId,
                                quote.quoteId(),
                                quote.city(),
                                quote.vehicleClass(),
                                quote.pickupPlaceId(),
                                quote.pickupLat(),
                                quote.pickupLng(),
                                notes,
                                voiceKey,
                                quote.dropoffPlaceId(),
                                quote.dropoffLat(),
                                quote.dropoffLng(),
                                flightNo,
                                type == BookingType.RECURRING ? r.recurrence().days() : null,
                                type == BookingType.RECURRING ? r.recurrence().until() : null,
                                payment,
                                quote.currency(),
                                quote.totalMinor(),
                                quote.equivalentCurrency(),
                                quote.equivalentMinor(),
                                quote.serviceFeeMinor(),
                                quote.durationSeconds(),
                                now));
        ride.setMeetingPoint(
                type == BookingType.AIRPORT && r.meetingPoint() != null
                        ? r.meetingPoint().strip()
                        : null);
        try {
            rides.saveAndFlush(ride);
        } catch (DataIntegrityViolationException e) {
            // Another request booked the same quote at the same moment (unique quote_id).
            throw new ApiException(HttpStatus.CONFLICT, "booking.quote-used");
        }
        TripActor role =
                riderUserId.equals(user.userId())
                        ? TripActor.RIDER
                        : user.isStaff() ? TripActor.OPS : TripActor.BOOKER;
        List<Trip> created = tripService.create(ride, pickups, new Actor(user.userId(), role));
        outbox.publish(
                "booking.ride-booked",
                "ride",
                ride.getId(),
                new RideBooked(
                        ride.getId(),
                        type,
                        ride.getCorporateId(),
                        created.stream().map(Trip::getId).toList(),
                        created.getFirst().getPickupAt(),
                        ride.getCurrency(),
                        ride.getFareMinor(),
                        payment));
        if (user.isStaff()) {
            auditLog.record(
                    "booking.ride.booked",
                    "ride",
                    ride.getId(),
                    Map.of("type", type.name(), "trips", created.size()));
        }
        return new Booked(ride, created);
    }

    private Booked bookMetered(BookingRequest r, CurrentUser user, Instant now) {
        boolean airport = r.type() == BookingType.AIRPORT;
        if (r.quoteId() != null
                || r.recurrence() != null
                || r.costCenterId() != null
                || r.riderMemberId() != null) throw invalid("metered");
        if (r.paymentMethod() != PaymentMethod.CASH) throw invalid("paymentMethod");
        if (r.pickup() == null) throw invalid("pickup");
        if (r.vehicleClass() == null) throw invalid("vehicleClass");
        Instant at = airport ? r.pickupAt() : now;
        if (!airport && r.pickupAt() != null) throw invalid("pickupAt");
        if (airport) bookingPolicy.validateSchedule(at, now);
        String flight = null;
        if (airport) {
            flight =
                    r.flightNo() == null
                            ? ""
                            : r.flightNo().strip().toUpperCase(java.util.Locale.ROOT);
            if (!FLIGHT.matcher(flight).matches()) throw invalid("flightNo");
            if (r.meetingPoint() == null
                    || r.meetingPoint().isBlank()
                    || r.meetingPoint().length() > 200) throw invalid("meetingPoint");
        } else if (r.flightNo() != null || r.meetingPoint() != null) throw invalid("flightNo");
        if (r.pickupVoiceNoteKey() != null) throw invalid("pickupVoiceNoteKey");
        String notes = r.pickupNotes() == null ? null : r.pickupNotes().strip();
        if (notes != null && notes.length() > MAX_NOTES) throw invalid("pickupNotes");
        var terms =
                meteredPricing.prepare(
                        r.pickup().placeId(),
                        r.pickup().latitude(),
                        r.pickup().longitude(),
                        r.vehicleClass(),
                        at,
                        airport);

        Ride ride =
                new Ride(
                        new Ride.Details(
                                r.type(),
                                FareModel.METERED,
                                user.userId(),
                                user.userId(),
                                null,
                                null,
                                null,
                                null,
                                terms.city(),
                                terms.vehicleClass(),
                                terms.pickupPlaceId(),
                                terms.latitude(),
                                terms.longitude(),
                                notes,
                                null,
                                null,
                                null,
                                null,
                                flight,
                                null,
                                null,
                                PaymentMethod.CASH,
                                terms.currency(),
                                null,
                                terms.currency().equals("USD") ? "KHR" : "USD",
                                null,
                                null,
                                null,
                                now));
        ride.setMeterTerms(terms);
        ride.setMeetingPoint(airport ? r.meetingPoint().strip() : null);
        rides.saveAndFlush(ride);
        var created =
                tripService.create(ride, List.of(at), new Actor(user.userId(), TripActor.RIDER));
        outbox.publish(
                "booking.ride-booked",
                "ride",
                ride.getId(),
                new RideBooked(
                        ride.getId(),
                        r.type(),
                        null,
                        created.stream().map(Trip::getId).toList(),
                        at,
                        ride.getCurrency(),
                        null,
                        PaymentMethod.CASH));
        return new Booked(ride, created);
    }

    /** Explicit fare selection; a missing destination never selects METERED. */
    static void validateCombination(BookingRequest r, BookingType type, FareModel fare) {
        if (fare == FareModel.METERED
                && type != BookingType.ON_DEMAND
                && type != BookingType.AIRPORT) throw combination("fareModel");
        if (type != BookingType.AIRPORT && (r.flightNo() != null || r.meetingPoint() != null))
            throw combination("flightNo");
        if (type != BookingType.RECURRING && r.recurrence() != null)
            throw combination("recurrence");
        if (type == BookingType.ON_DEMAND && r.pickupAt() != null) throw combination("pickupAt");
        if (fare == FareModel.METERED && r.quoteId() != null) throw combination("quoteId");
        if (fare == FareModel.QUOTED
                && (r.pickup() != null || r.pickupAt() != null || r.vehicleClass() != null))
            throw combination("quoteOverrides");
    }

    private static ApiException combination(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-combination")
                .withProperty("field", field);
    }

    /**
     * The ride, for the booker, the rider, staff and the company's admins/bookers; otherwise "not
     * found".
     */
    @Transactional(readOnly = true)
    public Booked get(UUID rideId) {
        CurrentUser user = currentUser.require();
        Ride ride =
                rides.findById(rideId)
                        .filter(r -> canSee(r, user))
                        .orElseThrow(
                                () ->
                                        new ApiException(
                                                HttpStatus.NOT_FOUND, "booking.ride-not-found"));
        return new Booked(ride, trips.findByRideIdOrderByOccurrence(ride.getId()));
    }

    /**
     * Cancels one trip, or (no {@code tripId}) every trip of the ride that can still be cancelled;
     * trips already under way or finished are left as they are.
     */
    @Transactional
    public Booked cancel(UUID rideId, UUID tripId, String reason) {
        CurrentUser user = currentUser.require();
        Booked booked = get(rideId);
        Ride ride = booked.ride();
        if (!ride.belongsTo(user.userId()) && !authz.canAccessCorporate(ride.getCorporateId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "auth.forbidden");
        }
        Actor actor =
                new Actor(
                        user.userId(),
                        ride.getRiderUserId().equals(user.userId())
                                ? TripActor.RIDER
                                : TripActor.BOOKER);
        if (tripId != null) {
            Trip trip =
                    booked.trips().stream()
                            .filter(t -> t.getId().equals(tripId))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new ApiException(
                                                    HttpStatus.NOT_FOUND,
                                                    "booking.trip-not-found"));
            tripService.cancelByRider(trip, reason, actor);
        } else {
            List<Trip> cancellable =
                    booked.trips().stream()
                            .filter(
                                    t ->
                                            t.getStatus() == TripStatus.SCHEDULED
                                                    || t.getStatus() == TripStatus.ASSIGNED
                                                    || t.getStatus() == TripStatus.DRIVER_EN_ROUTE)
                            .toList();
            if (cancellable.isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "booking.nothing-to-cancel");
            }
            cancellable.forEach(t -> tripService.cancelByRider(t, reason, actor));
        }
        return new Booked(ride, trips.findByRideIdOrderByOccurrence(ride.getId()));
    }

    /** Open trips of rides the user booked or rides in, from an hour ago on, soonest first. */
    @Transactional(readOnly = true)
    public org.springframework.data.domain.Page<Trip> upcoming(int page, int size) {
        CurrentUser user = currentUser.require();
        return trips.forUser(
                user.userId(),
                TripStatus.OPEN,
                clock.instant().minus(java.time.Duration.ofHours(1)),
                org.springframework.data.domain.PageRequest.of(
                        Math.max(page, 0), Math.clamp(size, 1, 100)));
    }

    /** The rides of these trips, by id (for list views). */
    @Transactional(readOnly = true)
    public Map<UUID, Ride> rides(java.util.Collection<UUID> rideIds) {
        Map<UUID, Ride> byId = new java.util.HashMap<>();
        rides.findAllById(rideIds).forEach(r -> byId.put(r.getId(), r));
        return byId;
    }

    boolean canSee(Ride ride, CurrentUser user) {
        return ride.belongsTo(user.userId())
                || user.isStaff()
                || (ride.getCorporateId() != null
                        && authz.canAccessCorporate(ride.getCorporateId()));
    }

    private void checkPolicy(
            UUID corporateId, UUID riderUserId, QuoteSummary quote, List<Instant> pickups) {
        Map<String, List<String>> refused = new LinkedHashMap<>();
        for (Instant pickup : pickups) {
            PolicyDecision decision =
                    policies.check(
                            corporateId,
                            riderUserId,
                            new RideRequest(
                                    quote.vehicleClass(),
                                    pickup,
                                    quote.totalMinor(),
                                    quote.currency()));
            if (!decision.allowed()) {
                refused.put(
                        pickup.toString(), decision.violations().stream().map(Enum::name).toList());
            }
        }
        if (!refused.isEmpty()) {
            List<Map<String, Object>> list = new ArrayList<>();
            refused.forEach((at, v) -> list.add(Map.of("pickupAt", at, "violations", v)));
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.policy-refused")
                    .withProperty("refused", list);
        }
    }

    /** Corporate quotes are invoiced; KHQR waits for the payment module. */
    private static PaymentMethod payment(PaymentMethod requested, QuoteSummary quote) {
        if (requested == null) {
            throw invalid("paymentMethod");
        }
        if (requested == PaymentMethod.KHQR) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "booking.payment-method-unavailable");
        }
        if ((quote.corporateId() != null) != (requested == PaymentMethod.CORPORATE_INVOICE)) {
            throw new ApiException(
                    HttpStatus.UNPROCESSABLE_CONTENT, "booking.payment-method-invalid");
        }
        return requested;
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-field")
                .withProperty("field", field);
    }
}
