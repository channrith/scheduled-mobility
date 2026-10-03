package com.mobility.core.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * Use on entity fields holding personal data: {@code @Convert(converter = EncryptedString.class)} on a
 * {@code BYTEA} column. Hibernate obtains it from the Spring context, so the key is injected.
 */
@Converter
public class EncryptedString implements AttributeConverter<String, byte[]> {

	private final PiiCrypto crypto;

	public EncryptedString(PiiCrypto crypto) {
		this.crypto = crypto;
	}

	@Override
	public byte[] convertToDatabaseColumn(String attribute) {
		return crypto.encrypt(attribute);
	}

	@Override
	public String convertToEntityAttribute(byte[] dbData) {
		return crypto.decrypt(dbData);
	}
}
