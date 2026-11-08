package com.mobility.gateway.ratelimit;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;

/**
 * The caller's IP address for per-IP limits. Behind Caddy (and the console's BFF) the gateway sees a proxy's
 * private address, so the client is the first {@code X-Forwarded-For} entry, but only when the request comes from a
 * trusted proxy (the same {@code trusted-proxies} pattern the routes use): Caddy replaces any value a client sent.
 * From anyone else the header is ignored and the remote address counts.
 */
@Component
class ClientIp {

	private static final String FORWARDED_FOR = "X-Forwarded-For";

	/** IPv4 or IPv6 text; anything else in the header is ignored. */
	private static final Pattern ADDRESS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

	private final Pattern trustedProxies;

	ClientIp(@Value("${spring.cloud.gateway.server.webflux.trusted-proxies}") String trustedProxies) {
		this.trustedProxies = Pattern.compile(trustedProxies);
	}

	String of(ServerHttpRequest request) {
		String remote = remoteAddress(request);
		if (remote != null && trustedProxies.matcher(remote).matches()) {
			String forwarded = request.getHeaders().getFirst(FORWARDED_FOR);
			if (forwarded != null) {
				String first = forwarded.split(",", 2)[0].strip();
				if (ADDRESS.matcher(first).matches()) {
					return first;
				}
			}
		}
		return remote == null ? "unknown" : remote;
	}

	private static String remoteAddress(ServerHttpRequest request) {
		InetSocketAddress remote = request.getRemoteAddress();
		if (remote == null) {
			return null;
		}
		return remote.getAddress() == null ? remote.getHostString() : remote.getAddress().getHostAddress();
	}
}
