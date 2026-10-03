package com.mobility.core.shared.storage;

import java.net.URI;

import com.mobility.core.shared.crypto.PiiCrypto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StorageProperties.class)
class StorageConfiguration {

	@Bean
	StorageService storageService(StorageProperties properties, ObjectProvider<S3Client> s3, PiiCrypto crypto) {
		StorageService backend = switch (properties.type()) {
			case LOCAL -> new LocalFileStorage(properties.local().dir());
			case S3 -> new S3ObjectStorage(s3.getObject(), properties.s3().bucket());
		};
		return new EncryptingStorageService(backend, crypto);
	}

	/** Closed by the container on shutdown. */
	@Bean
	@ConditionalOnProperty(name = "storage.type", havingValue = "s3")
	S3Client storageS3Client(StorageProperties properties) {
		StorageProperties.S3 s3 = properties.s3();
		require(s3.bucket(), "storage.s3.bucket (STORAGE_S3_BUCKET)");
		require(s3.accessKey(), "storage.s3.access-key (STORAGE_S3_ACCESS_KEY)");
		require(s3.secretKey(), "storage.s3.secret-key (STORAGE_S3_SECRET_KEY)");
		S3ClientBuilder builder = S3Client.builder()
			.httpClient(UrlConnectionHttpClient.create())
			.region(Region.of(s3.region()))
			.credentialsProvider(
					StaticCredentialsProvider.create(AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
			// Path-style URLs and no default checksum trailers: supported by every S3-compatible store.
			.forcePathStyle(true)
			.requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
			.responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
		if (StringUtils.hasText(s3.endpoint())) {
			builder.endpointOverride(URI.create(s3.endpoint()));
		}
		return builder.build();
	}

	private static void require(String value, String name) {
		if (!StringUtils.hasText(value)) {
			throw new IllegalStateException(name + " is required when storage.type=s3");
		}
	}
}
