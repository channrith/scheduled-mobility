package com.mobility.core.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import com.mobility.core.shared.crypto.PiiCrypto;
import com.mobility.core.shared.crypto.PiiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StorageTest {

	@TempDir
	Path dir;

	final PiiCrypto crypto = new PiiCrypto(new PiiProperties("dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=",
			"test-pii-hash-secret-0123456789abcdef"));

	@Test
	void localStoragePutsGetsAndDeletes() {
		LocalFileStorage storage = new LocalFileStorage(dir);

		storage.put("drivers/abc/doc-1", new byte[] { 1, 2, 3 });

		assertThat(storage.exists("drivers/abc/doc-1")).isTrue();
		assertThat(storage.get("drivers/abc/doc-1")).get().isEqualTo(new byte[] { 1, 2, 3 });
		storage.delete("drivers/abc/doc-1");
		assertThat(storage.exists("drivers/abc/doc-1")).isFalse();
		assertThat(storage.get("drivers/abc/doc-1")).isEmpty();
	}

	@ParameterizedTest
	@ValueSource(strings = { "../etc/passwd", "drivers/../../x", "/absolute", "drivers//x", "a/b c", "" })
	void rejectsUnsafeKeys(String key) {
		LocalFileStorage storage = new LocalFileStorage(dir);

		assertThatThrownBy(() -> storage.put(key, new byte[] { 1 })).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void encryptingStorageKeepsFilesUnreadableOnDisk() throws Exception {
		StorageService storage = new EncryptingStorageService(new LocalFileStorage(dir), crypto);
		byte[] pdf = "%PDF-1.4 national id scan".getBytes();

		storage.put("drivers/abc/doc-1", pdf);

		byte[] onDisk = Files.readAllBytes(dir.resolve("drivers/abc/doc-1"));
		assertThat(new String(onDisk, java.nio.charset.StandardCharsets.ISO_8859_1)).doesNotContain("PDF");
		assertThat(storage.get("drivers/abc/doc-1")).get().isEqualTo(pdf);
	}

	@Test
	void encryptedBlobCannotBeMovedToAnotherKey() throws Exception {
		StorageService storage = new EncryptingStorageService(new LocalFileStorage(dir), crypto);
		storage.put("drivers/abc/doc-1", new byte[] { 1, 2, 3 });
		Files.createDirectories(dir.resolve("drivers/xyz"));
		Files.copy(dir.resolve("drivers/abc/doc-1"), dir.resolve("drivers/xyz/doc-1"));

		assertThatThrownBy(() -> storage.get("drivers/xyz/doc-1")).isInstanceOf(IllegalStateException.class);
	}
}
