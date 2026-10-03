package com.mobility.core.shared.crypto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param encryptionKey base64 AES-256 key for personal data (ID numbers, bank accounts, document files)
 * @param hashSecret HMAC secret for blind indexes, so encrypted values can be checked for uniqueness
 */
@Validated
@ConfigurationProperties("pii")
public record PiiProperties(
		@NotBlank(message = "pii.encryption-key (PII_ENCRYPTION_KEY) is required") String encryptionKey,
		@Size(min = 32, message = "pii.hash-secret (PII_HASH_SECRET) must be at least 32 characters") String hashSecret) {
}
