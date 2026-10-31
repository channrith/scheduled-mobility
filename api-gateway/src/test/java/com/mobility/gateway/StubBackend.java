package com.mobility.gateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.handler.codec.http.HttpHeaders;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tiny backend for routing tests: answers any path with JSON naming itself, the path and the headers it got.
 * {@code /api/v1/slow} answers after 3 s; {@code /ws/echo} echoes WebSocket frames.
 */
final class StubBackend implements AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final DisposableServer server;

	/** Requests that reached the catch-all route (everything but /ws/echo and /api/v1/slow). */
	final AtomicInteger hits = new AtomicInteger();

	StubBackend(String name) {
		this.server = HttpServer.create()
			.host("127.0.0.1")
			.port(0)
			.route(routes -> routes
				.ws("/ws/echo", (in, out) -> out.sendString(in.receive().asString().map(text -> name + ":" + text)))
				.get("/api/v1/slow", (request, response) -> response.sendString(Mono.just("late").delayElement(Duration.ofSeconds(3))))
				.route(request -> hits.incrementAndGet() > 0, (request, response) -> response.header("Content-Type", "application/json")
					.sendString(Mono.just(JSON.writeValueAsString(
							Map.of("service", name, "path", request.uri(), "headers", headers(request.requestHeaders())))))))
			.bindNow();
	}

	String url() {
		return "http://127.0.0.1:" + server.port();
	}

	private static Map<String, String> headers(HttpHeaders headers) {
		Map<String, String> all = new LinkedHashMap<>();
		headers.forEach(e -> all.put(e.getKey().toLowerCase(java.util.Locale.ROOT), e.getValue()));
		return all;
	}

	@Override
	public void close() {
		server.disposeNow();
	}
}
