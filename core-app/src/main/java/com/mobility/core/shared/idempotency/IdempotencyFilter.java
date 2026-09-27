package com.mobility.core.shared.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import com.mobility.core.shared.crypto.AesGcm;
import com.mobility.core.shared.web.Problems;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Makes mutating {@code /api/**} requests that carry an {@code Idempotency-Key} header safe to retry:
 * the first response is stored (encrypted) in Redis and replayed for retries with the same key,
 * caller, method, path and body.
 * <ul>
 * <li>same key, different body → 422</li>
 * <li>same key while the first request is still running → 409</li>
 * <li>5xx, 408, 409 and 429 responses are not stored, so the client can retry them</li>
 * </ul>
 * Runs after Spring Security (default filter order), so the caller is known.
 */
@Component
class IdempotencyFilter extends OncePerRequestFilter {

	static final String HEADER = "Idempotency-Key";

	static final String REPLAYED_HEADER = "Idempotent-Replayed";

	private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

	private static final Set<Integer> NOT_STORED = Set.of(408, 409, 429);

	private final StringRedisTemplate redis;

	private final JsonMapper jsonMapper;

	private final Problems problems;

	private final IdempotencyProperties props;

	private final AesGcm aes;

	IdempotencyFilter(StringRedisTemplate redis, JsonMapper jsonMapper, Problems problems,
			IdempotencyProperties props) {
		this.redis = redis;
		this.jsonMapper = jsonMapper;
		this.problems = problems;
		this.props = props;
		this.aes = AesGcm.fromBase64Key(props.encryptionKey());
	}

	/** state is IN_PROGRESS or DONE. */
	record Entry(String state, String fingerprint, int status, String contentType, String location, String body) {
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !METHODS.contains(request.getMethod()) || request.getHeader(HEADER) == null
				|| !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String key = request.getHeader(HEADER).strip();
		if (key.isEmpty() || key.length() > 255) {
			problems.write(request, response, HttpStatus.BAD_REQUEST, "idempotency.key-invalid");
			return;
		}
		byte[] body = request.getInputStream().readNBytes((int) props.maxBodySize().toBytes() + 1);
		if (body.length > props.maxBodySize().toBytes()) {
			problems.write(request, response, HttpStatus.CONTENT_TOO_LARGE, "idempotency.body-too-large");
			return;
		}

		String caller = caller();
		String redisKey = "idempotency:" + sha256Hex(
				(caller + "\n" + request.getMethod() + "\n" + request.getRequestURI() + "\n" + key)
					.getBytes(StandardCharsets.UTF_8));
		String fingerprint = sha256Hex(body);

		Entry lock = new Entry("IN_PROGRESS", fingerprint, 0, null, null, null);
		if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(redisKey, seal(redisKey, lock), props.lockTtl()))) {
			respondToRetry(request, response, redisKey, fingerprint);
			return;
		}

		ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
		try {
			chain.doFilter(new CachedBodyRequest(request, body), wrapped);
		}
		catch (IOException | ServletException | RuntimeException ex) {
			redis.delete(redisKey);
			throw ex;
		}

		int status = wrapped.getStatus();
		if (status >= 500 || NOT_STORED.contains(status)) {
			redis.delete(redisKey);
		}
		else {
			Entry done = new Entry("DONE", fingerprint, status, wrapped.getContentType(),
					wrapped.getHeader(HttpHeaders.LOCATION),
					Base64.getEncoder().encodeToString(wrapped.getContentAsByteArray()));
			redis.opsForValue().set(redisKey, seal(redisKey, done),
					caller.equals("anonymous") ? props.anonymousTtl() : props.ttl());
		}
		wrapped.copyBodyToResponse();
	}

	private void respondToRetry(HttpServletRequest request, HttpServletResponse response, String redisKey,
			String fingerprint) throws IOException {
		String sealed = redis.opsForValue().get(redisKey);
		Entry entry = sealed == null ? null : unseal(redisKey, sealed);
		if (entry == null || "IN_PROGRESS".equals(entry.state())) {
			// Either still running, or it finished and expired in between: tell the client to retry.
			problems.write(request, response, HttpStatus.CONFLICT, "idempotency.in-progress");
			return;
		}
		if (!entry.fingerprint().equals(fingerprint)) {
			problems.write(request, response, HttpStatus.UNPROCESSABLE_CONTENT, "idempotency.key-reused");
			return;
		}
		response.setStatus(entry.status());
		if (entry.contentType() != null) {
			response.setContentType(entry.contentType());
		}
		if (entry.location() != null) {
			response.setHeader(HttpHeaders.LOCATION, entry.location());
		}
		response.setHeader(REPLAYED_HEADER, "true");
		response.getOutputStream().write(Base64.getDecoder().decode(entry.body()));
	}

	private static String caller() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated() || auth instanceof AnonymousAuthenticationToken) {
			return "anonymous";
		}
		return "user:" + auth.getName();
	}

	private String seal(String redisKey, Entry entry) {
		byte[] json = jsonMapper.writeValueAsBytes(entry);
		return Base64.getEncoder().encodeToString(aes.encrypt(json, redisKey.getBytes(StandardCharsets.UTF_8)));
	}

	private Entry unseal(String redisKey, String sealed) {
		byte[] json = aes.decrypt(Base64.getDecoder().decode(sealed), redisKey.getBytes(StandardCharsets.UTF_8));
		return jsonMapper.readValue(json, Entry.class);
	}

	private static String sha256Hex(byte[] data) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Lets the body be read again downstream after it was hashed here. */
	private static final class CachedBodyRequest extends HttpServletRequestWrapper {

		private final byte[] body;

		CachedBodyRequest(HttpServletRequest request, byte[] body) {
			super(request);
			this.body = body;
		}

		@Override
		public ServletInputStream getInputStream() {
			ByteArrayInputStream in = new ByteArrayInputStream(body);
			return new ServletInputStream() {

				@Override
				public int read() {
					return in.read();
				}

				@Override
				public int read(byte[] b, int off, int len) {
					return in.read(b, off, len);
				}

				@Override
				public boolean isFinished() {
					return in.available() == 0;
				}

				@Override
				public boolean isReady() {
					return true;
				}

				@Override
				public void setReadListener(ReadListener listener) {
					throw new UnsupportedOperationException();
				}
			};
		}

		@Override
		public java.io.BufferedReader getReader() {
			return new java.io.BufferedReader(new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
		}

		@Override
		public int getContentLength() {
			return body.length;
		}

		@Override
		public long getContentLengthLong() {
			return body.length;
		}
	}
}
