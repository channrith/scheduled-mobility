package com.mobility.location.realtime;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.socket.WebSocketHandler;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class PassengerLocationHandshakeInterceptorTest {
    @Test void usesTripScopeAndRechecksDriverIdentityForEachDelivery() {
        var access = mock(PassengerTrackingAccess.class);
        var interceptor = new PassengerLocationHandshakeInterceptor(access);
        UUID trip = UUID.randomUUID(), driver = UUID.randomUUID();
        var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject(UUID.randomUUID().toString()).expiresAt(Instant.now().plusSeconds(60)).build();
        var request = mock(ServerHttpRequest.class); var response = mock(ServerHttpResponse.class);
        when(request.getPrincipal()).thenReturn(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_PASSENGER"))));
        when(request.getURI()).thenReturn(URI.create("http://fixture/api/v1/passenger/trips/" + trip + "/location/live"));
        when(access.resolve(jwt, trip)).thenReturn(driver, driver, UUID.randomUUID());
        var attributes = new HashMap<String,Object>();
        assertTrue(interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), attributes));
        var authorize = (BooleanSupplier) attributes.get(PassengerLocationHandshakeInterceptor.DELIVERY_AUTHORIZATION);
        assertTrue(authorize.getAsBoolean()); assertFalse(authorize.getAsBoolean());
        assertEquals(driver, attributes.get(DriverLocationHandshakeInterceptor.DRIVER_ID));
        assertEquals(jwt.getExpiresAt(), attributes.get(DriverLocationHandshakeInterceptor.TOKEN_EXPIRES_AT));
    }
    @Test void driverRoleCannotUsePassengerHandshake() {
        var access = mock(PassengerTrackingAccess.class);
        var interceptor = new PassengerLocationHandshakeInterceptor(access);
        var jwt = Jwt.withTokenValue("fixture").header("alg", "RS256").subject(UUID.randomUUID().toString()).expiresAt(Instant.now().plusSeconds(60)).build();
        var request = mock(ServerHttpRequest.class); var response = mock(ServerHttpResponse.class);
        when(request.getPrincipal()).thenReturn(new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority("ROLE_DRIVER"))));
        assertFalse(interceptor.beforeHandshake(request, response, mock(WebSocketHandler.class), new HashMap<>()));
        verifyNoInteractions(access);
    }
}
