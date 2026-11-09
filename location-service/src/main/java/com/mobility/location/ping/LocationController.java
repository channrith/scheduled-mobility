package com.mobility.location.ping;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A driver's GPS ping over HTTP (the WebSocket stream comes in Prompt 7b). The app sends one about every 4 s on a trip
 * and every 15 s when idle. A ping dropped by the noise filter still answers 202 ({@code stored: false} with a
 * {@code reason}), so the app doesn't retry it.
 */
@RestController
@RequestMapping("/api/v1/locations")
class LocationController {

	/** A ping may be a little late (bad network), but not stale or from the future. */
	static final Duration MAX_AGE = Duration.ofMinutes(10);

	static final Duration MAX_AHEAD = Duration.ofMinutes(1);

	private final PingIngestion ingestion;

	private final Clock clock;

	LocationController(PingIngestion ingestion) {
		this.ingestion = ingestion;
		this.clock = Clock.systemUTC();
	}

	/** Cambodia, with a margin. Speed and heading are as the phone's GPS reports them. */
	record PingRequest(@NotNull @DecimalMin("9.5") @DecimalMax("15.0") Double latitude,
			@NotNull @DecimalMin("102.0") @DecimalMax("108.0") Double longitude, @PositiveOrZero Double accuracyMeters,
			@PositiveOrZero @DecimalMax("100") Double speedMps,
			@DecimalMin("0") @DecimalMax("360") Double headingDegrees, Instant recordedAt) {
	}

	@PostMapping
	ResponseEntity<Map<String, Object>> ping(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody PingRequest request) {
		Instant now = clock.instant();
		Instant at = request.recordedAt() == null ? now : request.recordedAt();
		if (at.isAfter(now.plus(MAX_AHEAD)) || at.isBefore(now.minus(MAX_AGE))) {
			return ResponseEntity.badRequest().body(Map.of("code", "location.recorded-at-invalid"));
		}
		PingIngestion.Outcome outcome = ingestion.ingest(jwt.getSubject(), new PingIngestion.Ping(request.latitude(),
				request.longitude(), request.accuracyMeters(), request.speedMps(), request.headingDegrees(), at), now);
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("stored", outcome.stored());
		if (outcome.reason() != null) {
			body.put("reason", outcome.reason());
		}
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(body);
	}
}
