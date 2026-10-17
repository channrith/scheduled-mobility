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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** OSRM's route service ({@code /route/v1/driving/lng,lat;lng,lat}), car profile. */
public class OsrmRoutingClient implements RoutingClient {

	private static final Logger log = LoggerFactory.getLogger(OsrmRoutingClient.class);

	/** How much of an unexpected response body goes into the log. */
	static final int BODY_IN_LOG = 300;

	private final RestClient http;

	private final String baseUrl;

	private final JsonMapper json;

	public OsrmRoutingClient(URI baseUrl, Duration timeout, JsonMapper json) {
		// HTTP/1.1: one small GET gains nothing from HTTP/2, and it rules out HTTP/2 negotiation problems.
		HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(timeout).build();
		JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
		factory.setReadTimeout(timeout);
		// Spring asks for gzip/deflate by default; the public OSRM server answers "deflate" with raw deflate data,
		// which Java's zlib inflater rejects ("incorrect header check"). Answers are ~1 KB: no compression.
		factory.enableCompression(false);
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
			log.warn("OSRM at {} answered HTTP {}: {}", baseUrl, e.getStatusCode().value(), snippet(e.getResponseBodyAsString()));
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM answered " + e.getStatusCode(), e);
		}
		catch (RestClientException e) {
			Throwable cause = rootCause(e);
			log.warn("OSRM at {} did not answer: {}: {}", baseUrl, cause.getClass().getName(), cause.getMessage());
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM did not answer: " + e.getMessage(), e);
		}
		JsonNode root = parse(body);
		String code = root.path("code").asString("");
		if (!code.equals("Ok")) {
			if (isNoRoute(body)) {
				throw new RoutingException(Kind.NO_ROUTE, "OSRM found no route", null);
			}
			log.warn("OSRM at {} answered code '{}': {}", baseUrl, code, snippet(body));
			throw new RoutingException(Kind.UNAVAILABLE, "OSRM answered " + code, null);
		}
		JsonNode route = root.path("routes").path(0);
		if (!route.has("distance") || !route.has("duration")) {
			log.warn("OSRM at {} returned no distance/duration: {}", baseUrl, snippet(body));
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
			log.warn("OSRM at {} sent a body that is not JSON ({}): {}", baseUrl, e.getMessage(), snippet(body));
			return json.createObjectNode();
		}
	}

	static String snippet(String body) {
		if (body == null) {
			return "(no body)";
		}
		String oneLine = body.replaceAll("\\s+", " ").strip();
		return oneLine.length() <= BODY_IN_LOG ? oneLine : oneLine.substring(0, BODY_IN_LOG) + "…";
	}

	private static Throwable rootCause(Throwable e) {
		Throwable cause = e;
		while (cause.getCause() != null && cause.getCause() != cause) {
			cause = cause.getCause();
		}
		return cause;
	}
}
