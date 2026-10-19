package com.mobility.location.realtime;

import java.time.Instant;
import java.util.Map;

import com.mobility.location.driver.DriverAccessResolver;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
public class DriverLocationHandshakeInterceptor
        implements HandshakeInterceptor {

    public static final String DRIVER_ID = "driverId";
    public static final String TOKEN_EXPIRES_AT = "tokenExpiresAt";

    private final DriverAccessResolver driverAccessResolver;

    public DriverLocationHandshakeInterceptor(
            DriverAccessResolver driverAccessResolver
    ) {
        this.driverAccessResolver = driverAccessResolver;
    }

    @Override
    public boolean beforeHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Map<String, Object> attributes
    ) {
        if (!(request.getPrincipal()
                instanceof JwtAuthenticationToken authentication)
                || !authentication.isAuthenticated()) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        var jwt = authentication.getToken();
        Instant expiresAt = jwt.getExpiresAt();

        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        try {
            var driver = driverAccessResolver.resolve(jwt);

            if (!"APPROVED".equals(driver.status())) {
                response.setStatusCode(HttpStatus.FORBIDDEN);
                return false;
            }

            attributes.put(DRIVER_ID, driver.driverId());
            attributes.put(TOKEN_EXPIRES_AT, expiresAt);

            return true;
        } catch (ResponseStatusException exception) {
            response.setStatusCode(exception.getStatusCode());
            return false;
        }
    }

    @Override
    public void afterHandshake(
            ServerHttpRequest request,
            ServerHttpResponse response,
            WebSocketHandler handler,
            Exception exception
    ) {
        // No additional work required here.
    }
}