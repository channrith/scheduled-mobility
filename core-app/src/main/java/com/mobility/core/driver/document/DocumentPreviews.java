package com.mobility.core.driver.document;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.document.DocumentService.StoredFile;
import com.mobility.core.shared.crypto.PiiCrypto;
import com.mobility.core.shared.web.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short-lived signed preview URLs for document files. The URL carries no storage path: only the document
 * id, side and expiry, signed with an HMAC key derived from the PII hash secret. Issuing a URL is the
 * audited "view" (the viewer is known then; the URL itself is a bearer link).
 */
@Component
public class DocumentPreviews {

	public static final String PATH = "/api/v1/document-previews/";

	public record PreviewUrl(String url, Instant expiresAt) {
	}

	private final DocumentService documents;

	private final AuditLog auditLog;

	private final Clock clock;

	private final Duration ttl;

	private final byte[] key;

	DocumentPreviews(DocumentService documents, AuditLog auditLog, Clock clock, PiiCrypto crypto,
			@Value("${driver.documents.preview-ttl:5m}") Duration ttl) {
		this.documents = documents;
		this.auditLog = auditLog;
		this.clock = clock;
		this.ttl = ttl;
		this.key = crypto.blindIndex("document-preview-signing-key-v1");
	}

	@Transactional
	public PreviewUrl issue(UUID driverId, UUID documentId, String side) {
		DocumentFile file = documents.requireFile(driverId, documentId, side);
		Instant expiresAt = clock.instant().plus(ttl);
		String payload = documentId + "." + file.getSide() + "." + expiresAt.getEpochSecond();
		String token = base64(payload.getBytes(StandardCharsets.US_ASCII)) + "." + base64(sign(payload));
		auditLog.record("driver.document.viewed", "driver_document", documentId,
				Map.of("driverId", driverId, "side", file.getSide()));
		return new PreviewUrl(PATH + token, expiresAt);
	}

	/** @throws ApiException 404 for an invalid, tampered or expired token */
	StoredFile open(String token) {
		Payload payload = verify(token)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document.preview-invalid"));
		return documents.content(payload.documentId(), payload.side());
	}

	private record Payload(UUID documentId, DocumentSide side) {
	}

	private Optional<Payload> verify(String token) {
		try {
			String[] parts = token.split("\\.");
			if (parts.length != 2) {
				return Optional.empty();
			}
			String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.US_ASCII);
			if (!MessageDigest.isEqual(sign(payload), Base64.getUrlDecoder().decode(parts[1]))) {
				return Optional.empty();
			}
			String[] fields = payload.split("\\.");
			if (Instant.ofEpochSecond(Long.parseLong(fields[2])).isBefore(clock.instant())) {
				return Optional.empty();
			}
			return Optional.of(new Payload(UUID.fromString(fields[0]), DocumentSide.valueOf(fields[1])));
		}
		catch (IllegalArgumentException | ArrayIndexOutOfBoundsException ex) {
			return Optional.empty();
		}
	}

	private byte[] sign(String payload) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(payload.getBytes(StandardCharsets.US_ASCII));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static String base64(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}
}
