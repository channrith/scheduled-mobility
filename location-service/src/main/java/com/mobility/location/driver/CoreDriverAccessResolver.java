package com.mobility.location.driver;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

@Component
public class CoreDriverAccessResolver implements DriverAccessResolver {

    private final RestClient client;

    public CoreDriverAccessResolver(
            @Value("${location.core.base-url}") String baseUrl
    ) {
        var httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        var requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(Duration.ofSeconds(5));

        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public DriverIdentity resolve(Jwt jwt) {
        UUID authenticatedUserId;

        try {
            authenticatedUserId = UUID.fromString(jwt.getSubject());
        } catch (IllegalArgumentException | NullPointerException ex) {
            throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Token subject must be a user UUID"
            );
        }

        CoreDriverProfile profile = loadProfile(jwt.getTokenValue());

        if (profile == null
                || profile.id() == null
                || profile.userId() == null
                || profile.status() == null
                || profile.status().isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Core returned an incomplete driver profile"
            );
        }

        if (!authenticatedUserId.equals(profile.userId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Driver profile does not belong to this user"
            );
        }

        return new DriverIdentity(
                profile.id(),
                profile.userId(),
                profile.status()
        );
    }

    private CoreDriverProfile loadProfile(String accessToken) {
        try {
            return client.get()
                    .uri("/api/v1/drivers/me")
                    .headers(headers ->
                            headers.setBearerAuth(accessToken)
                    )
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(CoreDriverProfile.class);

        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();

            if (status == 401) {
                throw new ResponseStatusException(
                        HttpStatus.UNAUTHORIZED,
                        "Core rejected the access token"
                );
            }

            if (status == 403) {
                throw new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "Core denied driver access"
                );
            }

            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Driver verification is temporarily unavailable"
            );

        } catch (RestClientException ex) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Driver verification is temporarily unavailable"
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CoreDriverProfile(
            UUID id,
            UUID userId,
            String status
    ) {
    }
}