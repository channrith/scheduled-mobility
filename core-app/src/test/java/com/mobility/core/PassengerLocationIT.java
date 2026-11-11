package com.mobility.core;

import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.PricingApi;
import com.mobility.core.pricing.TestCities;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.client.RestTestClient;
import java.time.Instant;
import java.util.*;
import static com.mobility.core.pricing.PricingApi.*;
import static org.assertj.core.api.Assertions.*;

@IntegrationTest
class PassengerLocationIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;
    @Autowired JdbcTemplate jdbc;

    @Test void reversePreservesPinAndSearchSeparatesAddressFromNotes() {
        var api=new PricingApi(client,issuer);var token=api.tokenFor(Role.PASSENGER);
        double[] point=nextSpot();
        var created=api.post("/api/v1/admin/places",api.adminToken,Map.of(
            "nameEn","Passenger Address Fixture", "latitude",point[0],"longitude",point[1],"type","LANDMARK",
            "formattedAddressEn","Test street, Cambodia","pickupNotesEn","Meet at gate 2"))
            .expectStatus().isCreated().expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        var reverse=api.json(api.get("/api/v1/places/reverse?lat="+point[0]+"&lng="+point[1],token));
        assertThat(reverse.path("placeId").asString()).isEqualTo(created.path("id").asString());
        assertThat(reverse.path("latitude").asDouble()).isEqualTo(point[0]);
        assertThat(reverse.path("formattedAddress").asString()).isEqualTo("Test street, Cambodia");
        assertThat(reverse.path("pickupInstructions").asString()).isEqualTo("Meet at gate 2");
        var found=api.json(api.get("/api/v1/places/search?q=Passenger%20Address%20Fixture",token));
        assertThat(found.path(0).path("formattedAddressEn").asString()).isEqualTo("Test street, Cambodia");
        api.get("/api/v1/places/reverse?lat=NaN&lng=104",token).expectStatus().isBadRequest();
        // Nearby is not canonical: no invented UUID or successful absence when provider is unconfigured.
        api.get("/api/v1/places/reverse?lat="+(point[0]+0.00001)+"&lng="+point[1],token).expectStatus().isEqualTo(503);
        client.get().uri("/api/v1/places/reverse?lat=11&lng=104").exchange().expectStatus().isUnauthorized();
    }

    @Test void preflightUsesCoveragePricingAirportRulesAndDoesNotWriteQuotes() {
        var api=new PricingApi(client,issuer);var token=api.tokenFor(Role.PASSENGER);
        String city=TestCities.next();double[] p=nextSpot();double lat=p[0],lng=p[1];
        api.createZone(city,"CITY",square(lat,lng,10),null);api.createRule(usdRule(city,"CAR"));
        long before=jdbc.queryForObject("SELECT count(*) FROM pricing.fare_quotes",Long.class);
        long ridesBefore=jdbc.queryForObject("SELECT count(*) FROM booking.ride_requests",Long.class);
        var body=new HashMap<String,Object>(Map.of("type","SCHEDULED","fareModel","QUOTED","vehicleClass","CAR",
            "pickup",Map.of("latitude",lat,"longitude",lng),"dropoff",Map.of("latitude",lat+0.01,"longitude",lng),
            "pickupAt",Instant.now().plusSeconds(7200).toString()));
        String key=UUID.randomUUID().toString();
        var ok=client.post().uri("/api/v1/booking/availability")
                .header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange()
                .expectStatus().isOk().expectHeader().valueEquals("Cache-Control","no-store")
                .expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        assertThat(ok.path("available").asBoolean()).isTrue();assertThat(ok.path("city").asString()).isEqualTo(city);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pricing.fare_quotes",Long.class)).isEqualTo(before);
        var airport=api.json(api.post("/api/v1/booking/availability",token,with(body,"type","AIRPORT")));
        assertThat(airport.path("reasons").path(0).path("code").asString()).isEqualTo("booking.not-an-airport-trip");
        var outside=api.json(api.post("/api/v1/booking/availability",token,with(body,"pickup",Map.of("latitude",11.8,"longitude",105.1),"dropoff",Map.of("latitude",11.81,"longitude",105.11))));
        assertThat(outside.path("available").asBoolean()).isFalse();
        var mixed=api.json(api.post("/api/v1/booking/availability",token,with(body,"dropoff",Map.of("latitude",11.81,"longitude",105.11))));
        assertThat(mixed.path("available").asBoolean()).isTrue();
        var noRule=api.json(api.post("/api/v1/booking/availability",token,with(body,"vehicleClass","VAN")));
        assertThat(noRule.path("reasons").path(0).path("code").asString()).isEqualTo("pricing.no-rule");
        api.post("/api/v1/booking/availability",token,with(body,"type","ON_DEMAND")).expectStatus().isBadRequest();
        var quote=api.post("/api/v1/quotes",token,Map.of("pickup",body.get("pickup"),"dropoff",body.get("dropoff"),"vehicleClass","CAR","pickupAt",body.get("pickupAt")))
            .expectStatus().isCreated().expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        assertThat(quote.path("route").isNull()).isTrue();
        var stored=api.json(api.get("/api/v1/quotes/"+quote.path("id").asString(),token));
        assertThat(stored.path("route")).isEqualTo(quote.path("route"));
        assertThat(stored.path("totalMinor")).isEqualTo(quote.path("totalMinor"));
        assertThat(stored.path("pickupAt")).isEqualTo(quote.path("pickupAt"));
        assertThat(stored.path("expiresAt")).isEqualTo(quote.path("expiresAt"));
        api.get("/api/v1/quotes/"+quote.path("id").asString(),api.tokenFor(Role.PASSENGER)).expectStatus().isNotFound();
        api.createZone(city,"AIRPORT",square(lat,lng,1),200L);
        var airportOk=api.json(api.post("/api/v1/booking/availability",token,with(body,"type","AIRPORT")));
        assertThat(airportOk.path("available").asBoolean()).isTrue();
        assertThat(airportOk.path("airportZoneId").isNull()).isFalse();
        var meter=Map.of("type","ON_DEMAND","fareModel","METERED","vehicleClass","CAR","pickup",body.get("pickup"));
        assertThat(api.json(api.post("/api/v1/booking/availability",token,meter)).path("available").asBoolean()).isTrue();
        api.post("/api/v1/booking/availability",token,with(meter,"dropoff",body.get("dropoff"))).expectStatus().isBadRequest();
        api.createZone(city,"RESTRICTED",square(lat,lng,1),null);
        var restricted=client.post().uri("/api/v1/booking/availability")
                .header("Authorization","Bearer "+token).header("Idempotency-Key",key)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).body(body).exchange()
                .expectStatus().isOk().expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        assertThat(restricted.path("reasons").path(0).path("code").asString()).isEqualTo("pricing.restricted-zone");
        assertThat(restricted.path("reasons").path(0).path("end").asString()).isEqualTo("pickup");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking.ride_requests",Long.class)).isEqualTo(ridesBefore);

    }
}
