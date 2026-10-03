package com.mobility.core.driver;

import java.nio.charset.StandardCharsets;

/** Minimal files with valid magic bytes. */
public final class SampleFiles {

	private SampleFiles() {
	}

	public static byte[] pdf() {
		return "%PDF-1.4\n1 0 obj <<>> endobj\ntrailer <<>>\n%%EOF\n".getBytes(StandardCharsets.ISO_8859_1);
	}

	public static byte[] png() {
		return new byte[] { (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 0, 0, 0, 13, 'I', 'H', 'D', 'R' };
	}

	public static byte[] jpeg() {
		return new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 16, 'J', 'F', 'I', 'F', 0 };
	}
}
