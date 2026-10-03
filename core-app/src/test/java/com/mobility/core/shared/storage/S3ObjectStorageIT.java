package com.mobility.core.shared.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.nio.charset.StandardCharsets;

import com.mobility.core.shared.crypto.PiiCrypto;
import com.mobility.core.shared.crypto.PiiProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Wires {@link StorageConfiguration} with {@code storage.type=s3} against Adobe S3Mock, a real S3 HTTP API.
 * Runs outside the shared {@code @IntegrationTest} context, which keeps local storage.
 */
@Testcontainers
class S3ObjectStorageIT {

	private static final String BUCKET = "documents";

	@Container
	static final GenericContainer<?> s3mock = new GenericContainer<>("adobe/s3mock:5.2.3").withExposedPorts(9090)
		.waitingFor(Wait.forHttp("/").forPort(9090));

	final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(StorageConfiguration.class)
		.withBean(PiiCrypto.class, () -> new PiiCrypto(new PiiProperties(
				"dGVzdC1waWktZW5jcnlwdGlvbi1rZXktMzJieXRlcyE=", "test-pii-hash-secret-0123456789abcdef")))
		.withPropertyValues("storage.type=s3", "storage.s3.endpoint=" + endpoint(),
				"storage.s3.bucket=" + BUCKET, "storage.s3.access-key=test",
				"storage.s3.secret-key=test");

	@BeforeAll
	static void createBucket() {
		try (S3Client s3 = rawClient()) {
			s3.createBucket(request -> request.bucket(BUCKET));
		}
	}

	@Test
	void storesEncryptedObjectsInTheBucket() {
		contextRunner.run(context -> {
			StorageService storage = context.getBean(StorageService.class);
			byte[] pdf = "%PDF-1.4 national id scan".getBytes(StandardCharsets.UTF_8);

			storage.put("drivers/abc/doc-1", pdf);

			assertThat(storage.exists("drivers/abc/doc-1")).isTrue();
			assertThat(storage.get("drivers/abc/doc-1")).get().isEqualTo(pdf);
			try (S3Client s3 = rawClient()) {
				byte[] stored = s3.getObjectAsBytes(r -> r.bucket(BUCKET).key("drivers/abc/doc-1")).asByteArray();
				assertThat(new String(stored, StandardCharsets.ISO_8859_1)).doesNotContain("PDF");
			}
		});
	}

	@Test
	void missingObjectsAreEmptyAndDeletesAreIdempotent() {
		contextRunner.run(context -> {
			StorageService storage = context.getBean(StorageService.class);
			storage.put("drivers/abc/doc-2", new byte[] { 1, 2, 3 });

			storage.delete("drivers/abc/doc-2");
			storage.delete("drivers/abc/doc-2");

			assertThat(storage.exists("drivers/abc/doc-2")).isFalse();
			assertThat(storage.get("drivers/abc/doc-2")).isEmpty();
			assertThat(storage.get("drivers/never/written")).isEmpty();
		});
	}

	@Test
	void rejectsUnsafeKeys() {
		contextRunner.run(context -> assertThatThrownBy(
				() -> context.getBean(StorageService.class).put("../escape", new byte[] { 1 }))
			.isInstanceOf(IllegalArgumentException.class));
	}

	@Test
	void failsFastWithoutCredentials() {
		contextRunner.withPropertyValues("storage.s3.access-key=")
			.run(context -> assertThat(context).getFailure()
				.rootCause()
				.hasMessageContaining("STORAGE_S3_ACCESS_KEY"));
	}

	private static String endpoint() {
		return "http://" + s3mock.getHost() + ":" + s3mock.getMappedPort(9090);
	}

	private static S3Client rawClient() {
		return S3Client.builder()
			.endpointOverride(URI.create(endpoint()))
			.region(Region.US_EAST_1)
			.forcePathStyle(true)
			.httpClient(UrlConnectionHttpClient.create())
			.credentialsProvider(StaticCredentialsProvider
				.create(AwsBasicCredentials.create("test", "test")))
			.build();
	}
}
