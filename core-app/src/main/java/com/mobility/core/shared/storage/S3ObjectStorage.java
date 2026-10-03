package com.mobility.core.shared.storage;

import java.util.Optional;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** Objects in one bucket of an S3-compatible store. Content arrives here already encrypted. */
class S3ObjectStorage implements StorageService {

	private final S3Client s3;

	private final String bucket;

	S3ObjectStorage(S3Client s3, String bucket) {
		this.s3 = s3;
		this.bucket = bucket;
	}

	@Override
	public void put(String key, byte[] content) {
		s3.putObject(request -> request.bucket(bucket)
			.key(StorageKeys.requireValid(key))
			.contentType("application/octet-stream"), RequestBody.fromBytes(content));
	}

	@Override
	public Optional<byte[]> get(String key) {
		try {
			return Optional
				.of(s3.getObjectAsBytes(request -> request.bucket(bucket).key(StorageKeys.requireValid(key)))
					.asByteArray());
		}
		catch (S3Exception ex) {
			if (isNotFound(ex)) {
				return Optional.empty();
			}
			throw ex;
		}
	}

	@Override
	public void delete(String key) {
		// S3 deletes are idempotent: deleting a missing key succeeds.
		s3.deleteObject(request -> request.bucket(bucket).key(StorageKeys.requireValid(key)));
	}

	@Override
	public boolean exists(String key) {
		try {
			s3.headObject(request -> request.bucket(bucket).key(StorageKeys.requireValid(key)));
			return true;
		}
		catch (S3Exception ex) {
			if (isNotFound(ex)) {
				return false;
			}
			throw ex;
		}
	}

	private static boolean isNotFound(S3Exception ex) {
		return ex instanceof NoSuchKeyException || ex.statusCode() == 404;
	}
}
