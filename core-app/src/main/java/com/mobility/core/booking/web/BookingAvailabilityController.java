package com.mobility.core.booking.web;

import com.mobility.core.booking.BookingPolicy;
import com.mobility.core.booking.BookingType;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.FareModel;
import com.mobility.core.pricing.ServiceEligibility;
import com.mobility.core.shared.openapi.ProblemResponse;
import com.mobility.core.shared.web.ApiException;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.context.MessageSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/booking/availability")
class BookingAvailabilityController {
    private final BookingPolicy policy;
    private final ServiceEligibility eligibility;
    private final Clock clock;
    private final MessageSource messages;

    BookingAvailabilityController(
            BookingPolicy policy, ServiceEligibility eligibility, Clock clock, MessageSource messages) {
        this.policy = policy;
        this.eligibility = eligibility;
        this.clock = clock;
        this.messages = messages;
    }

    record Pin(UUID placeId, Double latitude, Double longitude) {
        ServiceEligibility.EligibilityLocation location() {
            return new ServiceEligibility.EligibilityLocation(placeId, latitude, longitude);
        }

        void validate() {
            if (placeId != null) {
                return;
            }
            if (latitude == null || longitude == null
                    || !Double.isFinite(latitude) || !Double.isFinite(longitude)
                    || Math.abs(latitude) > 90 || Math.abs(longitude) > 180) {
                throw invalid("location");
            }
        }
    }

    record AvailabilityRequest(
            @NotNull BookingType type, @NotNull FareModel fareModel,
            @NotNull VehicleClass vehicleClass, @NotNull Pin pickup, Pin dropoff, Instant pickupAt) {}

    record Reason(String code, String end, String field, String detail) {}

    record Availability(
            boolean available, String city, List<String> pickupZoneTypes, List<String> dropoffZoneTypes,
            UUID airportZoneId, List<Reason> reasons) {}

    @ModelAttribute
    void preventStaleEligibility(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
    }

    @PostMapping
    @ProblemResponse(status = 400, description = "Malformed request or booking.invalid-combination")
    @ProblemResponse(status = 503, description = "booking.availability-unavailable")
    @Operation(summary = "Check personal booking eligibility without creating a quote or booking",
            description = "Side-effect-free policy preflight. No driver supply, fare or reservation guarantee. "
                    + "Quoted requires both endpoints; metered requires pickup only. Business rejection returns "
                    + "200 available=false with existing reason codes; malformed shape is 400. "
                    + "Quote and booking revalidate. No routing provider calls.")
    Availability check(@Valid @RequestBody AvailabilityRequest r, Locale locale) {
        r.pickup().validate();
        if (r.dropoff() != null) {
            r.dropoff().validate();
        }
        boolean metered = r.fareModel() == FareModel.METERED;
        if (r.type() == BookingType.RECURRING) {
            throw invalid("type");
        }
        if (metered && (r.type() == BookingType.SCHEDULED || r.dropoff() != null)) {
            throw invalid("fareModel");
        }
        if (!metered && r.dropoff() == null) {
            throw invalid("dropoff");
        }
        if (r.type() == BookingType.ON_DEMAND && r.pickupAt() != null) {
            throw invalid("pickupAt");
        }
        if (r.type() != BookingType.ON_DEMAND && r.pickupAt() == null) {
            throw invalid("pickupAt");
        }
        try {
            policy.requireEnabled(r.type(), r.fareModel());
            var at = r.type() == BookingType.ON_DEMAND ? clock.instant() : r.pickupAt();
            if (r.type() != BookingType.ON_DEMAND) {
                policy.validateSchedule(at, clock.instant());
            }
            var c = eligibility.check(
                    r.pickup().location(), r.dropoff() == null ? null : r.dropoff().location(),
                    r.vehicleClass(), at, r.type() == BookingType.AIRPORT, metered);
            return new Availability(
                    true, c.city(), c.pickupZoneTypes(), c.dropoffZoneTypes(), c.airportZoneId(), List.of());
        } catch (DataAccessException | TransactionException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "booking.availability-unavailable");
        } catch (ApiException e) {
            if (e.getStatus() != HttpStatus.UNPROCESSABLE_CONTENT
                    && !e.getCode().equals("booking.invalid-field")
                    && e.getStatus() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            return new Availability(false, null, List.of(), List.of(), null,
                    List.of(new Reason(e.getCode(), (String) e.getProperties().get("end"),
                            (String) e.getProperties().get("field"),
                            messages.getMessage("error." + e.getCode(), e.getArgs(), locale))));
        }
    }

    private static ApiException invalid(String field) {
        return new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-combination").withProperty("field", field);
    }
}
