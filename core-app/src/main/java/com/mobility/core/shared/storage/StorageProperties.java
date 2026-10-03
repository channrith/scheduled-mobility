package com.mobility.core.shared.storage;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param type which backend holds the (encrypted) objects
 * @param local filesystem backend, for development
 * @param s3 S3-compatible backend (DigitalOcean Spaces, Cloudflare R2, MinIO, AWS S3)
 */
@ConfigurationProperties("storage")
record StorageProperties(@DefaultValue("local") Type type, @DefaultValue Local local, @DefaultValue S3 s3) {

	enum Type {
		LOCAL, S3
	}

	record Local(@DefaultValue("./var/storage") Path dir) {
	}

	/**
	 * @param endpoint e.g. {@code https://sgp1.digitaloceanspaces.com}; empty means AWS S3 itself
	 * @param region signing region; S3-compatible stores mostly accept any value
	 */
	record S3(String endpoint, @DefaultValue("us-east-1") String region, String bucket, String accessKey,
			String secretKey) {
	}
}
