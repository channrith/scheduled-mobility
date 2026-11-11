package com.mobility.core.booking.ride;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mobility.core.booking.BookingPolicy;
import com.mobility.core.booking.BookingType;
import com.mobility.core.booking.PaymentMethod;
import com.mobility.core.pricing.FareModel;
import com.mobility.core.shared.web.ApiException;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class BookingCombinationTest {
    private BookingService.BookingRequest request(
            BookingType type, FareModel fare, UUID quote, String flight, Instant at) {
        return new BookingService.BookingRequest(
                quote,
                type,
                flight,
                null,
                null,
                null,
                PaymentMethod.CASH,
                null,
                null,
                null,
                null,
                at,
                null,
                null,
                fare);
    }

    @Test
    void onlyImmediateAndAirportTypesMayUseAnExplicitMeter() {
        for (var type : BookingType.values()) {
            for (var fare : FareModel.values()) {
                var request = request(type, fare, null, null, null);
                if (fare == FareModel.METERED
                        && type != BookingType.ON_DEMAND
                        && type != BookingType.AIRPORT) {
                    assertThatThrownBy(
                                    () -> BookingService.validateCombination(request, type, fare))
                            .isInstanceOfSatisfying(
                                    ApiException.class,
                                    e -> {
                                        assertThat(e.getCode())
                                                .isEqualTo("booking.invalid-combination");
                                        assertThat(e.getProperties())
                                                .containsEntry("field", "fareModel");
                                    });
                } else {
                    assertThatCode(() -> BookingService.validateCombination(request, type, fare))
                            .doesNotThrowAnyException();
                }
            }
        }
    }

    @Test
    void immediateTimeFlightAndMeterQuoteCannotBeSmuggledIntoAValidPair() {
        var bad =
                new BookingService.BookingRequest[] {
                    request(
                            BookingType.ON_DEMAND,
                            FareModel.METERED,
                            UUID.randomUUID(),
                            null,
                            null),
                    request(BookingType.ON_DEMAND, FareModel.METERED, null, null, Instant.now()),
                    request(
                            BookingType.ON_DEMAND,
                            FareModel.QUOTED,
                            UUID.randomUUID(),
                            "K6101",
                            null),
                    request(
                            BookingType.SCHEDULED,
                            FareModel.QUOTED,
                            UUID.randomUUID(),
                            "K6101",
                            null)
                };
        for (var request : bad) {
            assertThatThrownBy(
                            () ->
                                    BookingService.validateCombination(
                                            request, request.type(), request.fareModel()))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.getCode()).isEqualTo("booking.invalid-combination"));
        }
    }

    @Test
    void legacyConstructorDefaultsToQuotedAndRolloutCanStayClosed() {
        var legacy =
                new BookingService.BookingRequest(
                        UUID.randomUUID(),
                        BookingType.RECURRING,
                        null,
                        null,
                        null,
                        null,
                        PaymentMethod.CASH,
                        null,
                        null);
        assertThat(legacy.fareModel()).isEqualTo(FareModel.QUOTED);
        var policy = new BookingPolicy(false, false);
        assertThat(policy.onDemandEnabled()).isFalse();
        assertThat(policy.meteredEnabled()).isFalse();
    }
}
