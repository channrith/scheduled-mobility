package com.mobility.core.booking;

import com.mobility.core.shared.web.ApiException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/** Deployment capability and scheduling policy, injected into booking and its discovery API. */
@Component
public class BookingPolicy {
    private final boolean meteredEnabled;
    private final boolean onDemandEnabled;

    public BookingPolicy(
            @Value("${booking.metered-enabled:false}") boolean meteredEnabled,
            @Value("${booking.on-demand-enabled:false}") boolean onDemandEnabled) {
        this.meteredEnabled = meteredEnabled;
        this.onDemandEnabled = onDemandEnabled;
    }

    public boolean onDemandEnabled() {
        return onDemandEnabled;
    }

    public boolean meteredEnabled() {
        return meteredEnabled;
    }

    public record Combination(BookingType type, com.mobility.core.pricing.FareModel fareModel) {}

    public java.util.List<Combination> personalCombinations() {
        var result = new java.util.ArrayList<Combination>();
        for (var type : java.util.List.of(BookingType.SCHEDULED, BookingType.AIRPORT, BookingType.ON_DEMAND)) {
            if (type == BookingType.ON_DEMAND && !onDemandEnabled) continue;
            result.add(new Combination(type, com.mobility.core.pricing.FareModel.QUOTED));
            if (type != BookingType.SCHEDULED && meteredEnabled)
                result.add(new Combination(type, com.mobility.core.pricing.FareModel.METERED));
        }
        return java.util.List.copyOf(result);
    }

    public void requireEnabled(BookingType type, com.mobility.core.pricing.FareModel fare) {
        if ((type == BookingType.ON_DEMAND && !onDemandEnabled)
                || (fare == com.mobility.core.pricing.FareModel.METERED && !meteredEnabled))
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "booking.type-not-available");
    }

    public int maximumScheduleDays() {
        return 366;
    }

    public int minimumScheduleNoticeSeconds() {
        return 0;
    }

    public void validateSchedule(Instant at, Instant now) {
        if (at == null
                || !at.isAfter(now.plusSeconds(minimumScheduleNoticeSeconds()))
                || at.isAfter(now.plus(Duration.ofDays(maximumScheduleDays()))))
            throw new ApiException(HttpStatus.BAD_REQUEST, "booking.invalid-field")
                    .withProperty("field", "pickupAt");
    }
}
