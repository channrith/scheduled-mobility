package com.mobility.core.place.reverse;

import com.mobility.core.shared.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

/** Explicitly configured Nominatim-compatible reverse endpoint; no implicit public-server traffic. */
@Component
class GeocodingProvider {
    record Label(String name, String address, boolean resolved) {}

    private final String endpoint;
    private final RestClient http;
    private final JsonMapper json;

    GeocodingProvider(
            @Value("${places.geocoding.reverse-url:}") String endpoint,
            @Value("${places.geocoding.timeout:3s}") Duration timeout,
            JsonMapper json) {
        this.endpoint = endpoint;
        this.json = json;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(timeout).build());
        factory.setReadTimeout(timeout);
        http = RestClient.builder().requestFactory(factory)
                .defaultHeader("User-Agent", "scheduled-mobility/1.0").build();
    }

    Label reverse(double lat, double lng, String language) {
        if (endpoint.isBlank()) {
            throw unavailable();
        }
        try {
            var uri = URI.create(endpoint + String.format(
                    Locale.ROOT, "?format=jsonv2&lat=%.15f&lon=%.15f&accept-language=%s",
                    lat, lng, language.equals("en") ? "en,km" : "km,en"));
            return http.get().uri(uri).exchange((request, response) -> {
                if (response.getStatusCode().value() == 429) {
                    throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "places.geocoding-rate-limited")
                            .withHeader("Retry-After", "60");
                }
                if (!response.getStatusCode().is2xxSuccessful() && response.getStatusCode().value() != 404) {
                    throw unavailable();
                }
                byte[] body = response.getBody().readNBytes(262145);
                if (body.length > 262144) {
                    throw unavailable();
                }
                var node = json.readTree(body);
                if (node == null || !node.isObject()) {
                    throw unavailable();
                }
                // Only the provider's explicit missing-coverage result is absence; a proxy's
                // generic 404 or a misconfigured endpoint remains an outage.
                if ("Unable to geocode".equals(node.path("error").asString(""))) {
                    return new Label(null, null, false);
                }
                if (response.getStatusCode().value() == 404 || node.has("error")) {
                    throw unavailable();
                }
                String address = bounded(node.path("display_name").asString(null));
                String name = bounded(node.path("name").asString(null));
                if (address == null && name == null) {
                    throw unavailable();
                }
                return new Label(name == null ? address : name, address, true);
            });
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw unavailable();
        }
    }

    private static String bounded(String value) {
        return value == null || value.isBlank() ? null : value.substring(0, Math.min(500, value.length()));
    }

    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "places.geocoding-unavailable");
    }
}
