package com.mobility.core.driver.document;

import java.util.Arrays;
import java.util.Optional;

/** Detects the real file type from its first bytes; the client-declared content type is ignored. */
final class FileTypes {

	static final String JPEG = "image/jpeg";

	static final String PNG = "image/png";

	static final String PDF = "application/pdf";

	private static final byte[] JPEG_MAGIC = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private static final byte[] PNG_MAGIC = { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n' };

	private static final byte[] PDF_MAGIC = { '%', 'P', 'D', 'F', '-' };

	private FileTypes() {
	}

	static Optional<String> detect(byte[] content) {
		if (startsWith(content, JPEG_MAGIC)) {
			return Optional.of(JPEG);
		}
		if (startsWith(content, PNG_MAGIC)) {
			return Optional.of(PNG);
		}
		if (startsWith(content, PDF_MAGIC)) {
			return Optional.of(PDF);
		}
		return Optional.empty();
	}

	static String extension(String contentType) {
		return switch (contentType) {
			case JPEG -> "jpg";
			case PNG -> "png";
			default -> "pdf";
		};
	}

	private static boolean startsWith(byte[] content, byte[] magic) {
		return content.length >= magic.length && Arrays.equals(content, 0, magic.length, magic, 0, magic.length);
	}
}
