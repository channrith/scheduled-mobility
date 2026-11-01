package com.mobility.location.realtime;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PassengerTrackingAccess {
    private final RestClient client;
    public PassengerTrackingAccess(@Value("${location.core.base-url}") String baseUrl) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }
    public record Access(UUID tripId, UUID driverId) {}
    public UUID resolve(Jwt jwt, UUID tripId) {
        if (jwt.getExpiresAt() == null || !jwt.getExpiresAt().isAfter(java.time.Instant.now()))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        try {
            var result = client.get().uri("/api/v1/passenger/trips/{id}/tracking-access", tripId)
                .headers(h -> h.setBearerAuth(jwt.getTokenValue())).retrieve().body(Access.class);
            if (result == null || !tripId.equals(result.tripId()) || result.driverId() == null)
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY);
            return result.driverId();
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            throw new ResponseStatusException(status >= 400 && status < 500 ? ex.getStatusCode() : HttpStatus.SERVICE_UNAVAILABLE);
        } catch (RestClientException ex) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }
}
