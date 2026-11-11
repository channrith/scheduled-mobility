package com.mobility.core.pricing.quote;

import com.mobility.core.IntegrationTest;
import com.mobility.core.identity.Role;
import com.mobility.core.identity.token.AccessTokenIssuer;
import com.mobility.core.pricing.*;
import com.mobility.core.pricing.routing.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import java.util.Map;
import static com.mobility.core.pricing.PricingApi.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@IntegrationTest
class QuoteGeometryIT {
    @Autowired RestTestClient client;
    @Autowired AccessTokenIssuer issuer;
    @MockitoBean RoutingClient routing;
    @Test void routeIsPersistedFromOnePricingCallAndReadWithoutRerouting() {
        var api=new PricingApi(client,issuer);var token=api.tokenFor(Role.PASSENGER);
        String city=TestCities.next();double[] p=nextSpot();
        api.createZone(city,"CITY",square(p[0],p[1],10),null);api.createRule(usdRule(city,"CAR"));
        var geometry=new Route.Geometry("polyline",5,"_p~iF~ps|U_ulLnnqC_mqNvxq`@");
        when(routing.route(any(),any())).thenReturn(new Route(1234,456,Route.RoutingSource.OSRM,geometry));
        var quote=api.post("/api/v1/quotes",token,Map.of("pickup",Map.of("latitude",p[0],"longitude",p[1]),
                "dropoff",Map.of("latitude",p[0]+0.01,"longitude",p[1]),"vehicleClass","CAR"))
                .expectStatus().isCreated().expectBody(tools.jackson.databind.JsonNode.class).returnResult().getResponseBody();
        assertThat(quote.path("route").path("encodedPolyline").asString()).isEqualTo(geometry.encodedPolyline());
        assertThat(quote.path("distanceMeters").asLong()).isEqualTo(1234);
        reset(routing);
        var stored=api.json(api.get("/api/v1/quotes/"+quote.path("id").asString(),token));
        assertThat(stored.path("route")).isEqualTo(quote.path("route"));
        assertThat(stored.path("distanceMeters")).isEqualTo(quote.path("distanceMeters"));
        assertThat(stored.path("durationSeconds")).isEqualTo(quote.path("durationSeconds"));
        assertThat(stored.path("totalMinor")).isEqualTo(quote.path("totalMinor"));
        verifyNoInteractions(routing);
    }
}
