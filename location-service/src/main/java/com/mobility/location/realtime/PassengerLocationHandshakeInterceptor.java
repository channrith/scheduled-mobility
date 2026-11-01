package com.mobility.location.realtime;

import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class PassengerLocationHandshakeInterceptor implements HandshakeInterceptor {
    public static final String DELIVERY_AUTHORIZATION = "passengerDeliveryAuthorization";
    private final PassengerTrackingAccess access;
    public PassengerLocationHandshakeInterceptor(PassengerTrackingAccess access) { this.access = access; }
    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Map<String, Object> attributes) {
        if (!(request.getPrincipal() instanceof JwtAuthenticationToken auth) || !auth.isAuthenticated()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED); return false;
        }
        if (auth.getAuthorities().stream().noneMatch(a -> a.getAuthority().equals("ROLE_PASSENGER"))) {
            response.setStatusCode(HttpStatus.FORBIDDEN); return false;
        }
        try {
            String[] path = request.getURI().getPath().split("/");
            UUID tripId = UUID.fromString(path[path.length - 3]);
            var jwt = auth.getToken();
            UUID driverId = access.resolve(jwt, tripId);
            attributes.put(DriverLocationHandshakeInterceptor.DRIVER_ID, driverId);
            attributes.put(DriverLocationHandshakeInterceptor.TOKEN_EXPIRES_AT, jwt.getExpiresAt());
            // Fail closed on cancellation, completion, reassignment, expired credentials or core failure.
            attributes.put(DELIVERY_AUTHORIZATION, (BooleanSupplier) () -> driverId.equals(access.resolve(jwt, tripId)));
            return true;
        } catch (IllegalArgumentException ex) {
            response.setStatusCode(HttpStatus.BAD_REQUEST); return false;
        } catch (ResponseStatusException ex) {
            response.setStatusCode(ex.getStatusCode()); return false;
        }
    }
    @Override public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler handler, Exception ex) {}
}
