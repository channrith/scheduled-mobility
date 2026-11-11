package com.mobility.core.place.reverse;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.shared.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@IntegrationTest
class ReversePlacesIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @MockitoBean GeocodingProvider provider;
    @Test void delayedEarlierLookupKeepsItsOwnPinAndLanguage() throws Exception {
        var api=new PricingApi(client,issuer);String token=api.tokenFor(Role.PASSENGER);
        var started=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        when(provider.reverse(11.822,105.122,"en")).thenAnswer(call->{
            started.countDown();
            if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Test lookup timed out");
            return new GeocodingProvider.Label("Earlier pin","Earlier address",true);
        });
        when(provider.reverse(11.823,105.123,"km")).thenReturn(new GeocodingProvider.Label("ទីតាំងថ្មី","អាសយដ្ឋានថ្មី",true));
        try(var executor=java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var earlier=executor.submit(()->api.json(api.get("/api/v1/places/reverse?lat=11.822&lng=105.122",token)));
            try {
                assertThat(started.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                var latest=client.get().uri("/api/v1/places/reverse?lat=11.823&lng=105.123")
                    .header("Authorization","Bearer "+token).header("Accept-Language","km").exchange()
                    .expectStatus().isOk().expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
                assertThat(latest.path("latitude").asDouble()).isEqualTo(11.823);
                assertThat(latest.path("name").asString()).isEqualTo("ទីតាំងថ្មី");
            } finally { release.countDown(); }
            var old=earlier.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(old.path("latitude").asDouble()).isEqualTo(11.822);
            assertThat(old.path("name").asString()).isEqualTo("Earlier pin");
            assertThat(old.path("placeId").isNull()).isTrue();
        }
    }

    @Test void preservesExactPinCachesNoResultButNeverOutages() {
        var api=new PricingApi(client,issuer);String token=api.tokenFor(Role.PASSENGER);
        when(provider.reverse(11.800123456,105.100123456,"en")).thenReturn(new GeocodingProvider.Label(null,null,false));
        String path="/api/v1/places/reverse?lat=11.800123456&lng=105.100123456";
        long placesBefore=jdbc.queryForObject("SELECT count(*) FROM place.places",Long.class);
        var result=api.json(api.get(path,token).expectHeader().valueEquals("Cache-Control","no-store"));
        assertThat(result.path("resolved").asBoolean()).isFalse();
        assertThat(result.path("placeId").isNull()).isTrue();
        assertThat(result.path("latitude").asDouble()).isEqualTo(11.800123456);
        api.get(path,token).expectStatus().isOk();verify(provider,times(1)).reverse(11.800123456,105.100123456,"en");
        when(provider.reverse(11.8123,105.1234,"en")).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"places.geocoding-unavailable"));
        String outage="/api/v1/places/reverse?lat=11.8123&lng=105.1234";
        api.get(outage,token).expectStatus().isEqualTo(503);api.get(outage,token).expectStatus().isEqualTo(503);
        verify(provider,times(2)).reverse(11.8123,105.1234,"en");
        String limited=api.tokenFor(Role.PASSENGER);
        for(int i=0;i<60;i++) api.get(path,limited).expectStatus().isOk();
        api.get(path,limited).expectStatus().isEqualTo(429).expectHeader().valueEquals("Retry-After","60");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM place.places",Long.class)).isEqualTo(placesBefore);

    }
}
