package com.mobility.core.booking.web;

import com.mobility.core.booking.*;
import com.mobility.core.driver.VehicleClass;
import com.mobility.core.pricing.*;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.StaticMessageSource;
import java.time.*;
import java.util.Locale;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BookingAvailabilityControllerTest {
    @Test void transactionOutageIs503RatherThanBusinessIneligibility() {
        var eligibility=mock(ServiceEligibility.class);
        when(eligibility.check(any(),any(),any(),any(),anyBoolean(),anyBoolean()))
                .thenThrow(new org.springframework.transaction.CannotCreateTransactionException("Database unavailable"));
        var controller=new BookingAvailabilityController(new BookingPolicy(true,true),eligibility,Clock.systemUTC(),new StaticMessageSource());
        var pickup=new BookingAvailabilityController.Pin(null,11.5,104.9);
        var request=new BookingAvailabilityController.AvailabilityRequest(BookingType.ON_DEMAND,FareModel.QUOTED,VehicleClass.CAR,pickup,pickup,null);
        assertThatThrownBy(()->controller.check(request,Locale.ENGLISH))
                .isInstanceOfSatisfying(com.mobility.core.shared.web.ApiException.class,e->assertThat(e.getStatus().value()).isEqualTo(503));
    }
    @Test void disabledModesAndInvalidTimeNeverCallPricing() {
        var eligibility=mock(ServiceEligibility.class);
        var messages=new StaticMessageSource();
        messages.addMessage("error.booking.type-not-available",Locale.ENGLISH,"Not enabled");
        var controller=new BookingAvailabilityController(new BookingPolicy(false,false),eligibility,Clock.systemUTC(),messages);
        var pickup=new BookingAvailabilityController.Pin(null,11.5,104.9);
        var request=new BookingAvailabilityController.AvailabilityRequest(BookingType.ON_DEMAND,FareModel.METERED,VehicleClass.CAR,pickup,null,null);
        var result=controller.check(request,Locale.ENGLISH);
        assertThat(result.available()).isFalse();
        assertThat(result.reasons().getFirst().code()).isEqualTo("booking.type-not-available");
        verifyNoInteractions(eligibility);
        assertThat(new BookingPolicy(false,false).personalCombinations()).hasSize(2);
    }
}
