package com.mobility.core.pricing.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import com.mobility.core.pricing.routing.Route.RoutingSource;
import com.mobility.core.pricing.routing.RoutingException.Kind;
import com.mobility.core.pricing.zone.LatLng;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** Against a stub HTTP server playing OSRM. */
class OsrmRoutingClientTest {

	static final LatLng RIVERSIDE = new LatLng(11.5683, 104.9310);

	static final LatLng AIRPORT = new LatLng(11.3650, 104.8620);

	HttpServer server;

	final AtomicReference<String> lastPath = new AtomicReference<>();

	volatile int status;

	volatile String body;

	volatile long delayMillis;

	OsrmRoutingClient client;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			lastPath.set(exchange.getRequestURI().toString());
			try {
				Thread.sleep(delayMillis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(status, bytes.length);
			exchange.getResponseBody().write(bytes);
			exchange.close();
		});
		server.start();
		client = new OsrmRoutingClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()), Duration.ofMillis(500),
				JsonMapper.builder().build());
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void readsDistanceAndDurationAndSendsLongitudeFirst() {
		status = 200;
		body = """
				{"code":"Ok","routes":[{"distance":31245.7,"duration":2412.4}],"waypoints":[]}""";

		Route route = client.route(RIVERSIDE, AIRPORT);

		assertThat(route).isEqualTo(new Route(31246, 2412, RoutingSource.OSRM));
		assertThat(lastPath.get()).startsWith("/route/v1/driving/104.931000,11.568300;104.862000,11.365000?overview=false");
	}

	@Test
	void noRouteIsItsOwnError() {
		status = 400;
		body = """
				{"code":"NoRoute","message":"Impossible route between points"}""";

		assertThatThrownBy(() -> client.route(RIVERSIDE, AIRPORT)).isInstanceOfSatisfying(RoutingException.class,
				e -> assertThat(e.kind()).isEqualTo(Kind.NO_ROUTE));
	}

	@Test
	void serverErrorsAndTimeoutsMeanUnavailable() {
		status = 503;
		body = "busy";
		assertThatThrownBy(() -> client.route(RIVERSIDE, AIRPORT)).isInstanceOfSatisfying(RoutingException.class,
				e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));

		status = 200;
		body = """
				{"code":"Ok","routes":[{"distance":1,"duration":1}]}""";
		delayMillis = 1500;
		assertThatThrownBy(() -> client.route(RIVERSIDE, AIRPORT)).isInstanceOfSatisfying(RoutingException.class,
				e -> assertThat(e.kind()).isEqualTo(Kind.UNAVAILABLE));
	}

	@Test
	void straightLineEstimateForTests() {
		Route route = new StraightLineRoutingClient(new java.math.BigDecimal("1.3"), new java.math.BigDecimal("25")).route(RIVERSIDE,
				AIRPORT);

		// About 23.6 km as the crow flies.
		assertThat(route.distanceMeters()).isBetween(30_000L, 31_500L);
		assertThat(route.durationSeconds()).isEqualTo(Math.round(route.distanceMeters() / (25_000.0 / 3600)));
		assertThat(route.source()).isEqualTo(RoutingSource.STRAIGHT_LINE);
	}
}
