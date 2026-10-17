package com.mobility.core.pricing.routing;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Locale;

import com.mobility.core.pricing.routing.Route.RoutingSource;
import com.mobility.core.pricing.routing.RoutingException.Kind;
import com.mobility.core.pricing.zone.LatLng;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OSRM's route service ({@code /route/v1/driving/lng,lat;lng,lat}), car profile. */
public class OsrmRoutingClient implements RoutingClient {

	private final RestClient http;

	private final String baseUrl;

	private final JsonMapper json;

	public OsrmRoutingClient(URI baseUrl, Duration timeout, JsonMapper json) {
		HttpClient client = HttpClient.newBuilder().connectTimeout(timeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(timeout);
		this.baseUrl = baseUrl.toString().replaceAll("/+$", "");
		this.http = RestClient.builder()
			.requestFactory(factory)
			// The public OSRM servers ask clients to identify themselves.
			.defaultHeader("User-Agent", "scheduled-mobility/1.0 (fare quotes)")
			.build();
		this.json = json;
	}

	@Override
	public Route route(LatLng from, LatLng to) {
		String coordinates = String.format(Locale.ROOT, "%.6f,%.6f;%.6f,%.6f", from.longitude(), from.latitude(),
				to.longitude(), to.latitude());
		String body;
		try {
			body = http.get()
				// Built by hand: a URI template would percent-encode OSRM's ',' and ';' separators.
				.uri(URI.create(baseUrl + "/route/v1/driving/" + coordinates + "?overview=false&alternatives=false&steps=false"))
				.retrieve()
				.body(String.class);
		}
		catch (RestClientResponseException e) {
			// OSRM answers "no route" with HTTP 400 and a code in the body.
			if (e.getStatusCode().is4xxClientError() && isNoRoute(e.getResponseBodyAsString())) {
				throw new RoutingException(Kind.NO_ROUTE, "OSRM found no route", e);
			}
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM answered " + e.getStatusCode(), e);
		}
		catch (RestClientException e) {
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM did not answer: " + e.getMessage(), e);
		}
		JsonNode root = parse(body);
		String code = root.path("code").asString("");
		if (!code.equals("Ok")) {
			if (isNoRoute(body)) {
				throw new RoutingException(Kind.NO_ROUTE, "OSRM found no route", null);
			}
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM answered " + code, null);
		}
		JsonNode route = root.path("routes").path(0);
		if (!route.has("distance") || !route.has("duration")) {
			throw new RoutingException(Kind.NO_ROUTE, "OSRM returned no route", null);
		}
		return new Route(Math.round(route.get("distance").asDouble()), Math.round(route.get("duration").asDouble()),
				RoutingSource.OSRM);
	}

	private boolean isNoRoute(String body) {
		String code = parse(body).path("code").asString("");
		return code.equals("NoRoute") || code.equals("NoSegment");
	}

	private JsonNode parse(String body) {
		try {
			return body == null ? json.createObjectNode() : json.readTree(body);
		}
		catch (RuntimeException e) {
			return json.createObjectNode();
		}
	}
}
