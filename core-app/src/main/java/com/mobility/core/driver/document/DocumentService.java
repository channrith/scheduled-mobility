package com.mobility.core.driver.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mobility.core.audit.AuditLog;
import com.mobility.core.driver.vehicle.VehicleService;
import com.mobility.core.driver.vehicle.VehicleView;
import com.mobility.core.identity.CurrentUserProvider;
import com.mobility.core.shared.storage.StorageService;
import com.mobility.core.shared.time.BusinessTime;
import com.mobility.core.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Upload, review and retrieval of driver documents. Files are encrypted by StorageService. */
@Service
public class DocumentService {

	public static final int MAX_BYTES = 10 * 1024 * 1024;

	static final int MIN_REASON_LENGTH = 10;

	public record StoredFile(byte[] content, String contentType, String filename) {
	}

	private final DriverDocumentRepository documents;

	private final VehicleService vehicles;

	private final StorageService storage;

	private final AuditLog auditLog;

	private final CurrentUserProvider currentUser;

	private final Clock clock;

	DocumentService(DriverDocumentRepository documents, VehicleService vehicles, StorageService storage,
			AuditLog auditLog, CurrentUserProvider currentUser, Clock clock) {
		this.documents = documents;
		this.vehicles = vehicles;
		this.storage = storage;
		this.auditLog = auditLog;
		this.currentUser = currentUser;
		this.clock = clock;
	}

	/** Every document that is not superseded (pending, approved or rejected). */
	@Transactional(readOnly = true)
	public List<DriverDocument> currentDocuments(UUID driverId) {
		return documents.findCurrent(driverId);
	}

	/**
	 * Stores a new document, replacing (superseding) the current one of the same type. The caller has
	 * already checked that the driver may upload.
	 */
	@Transactional
	public DriverDocument upload(UUID driverId, DocumentType type, UUID vehicleId, LocalDate expiresOn, byte[] content) {
		if (content == null || content.length == 0) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "document.empty");
		}
		if (content.length > MAX_BYTES) {
			throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "document.too-large");
		}
		String contentType = FileTypes.detect(content)
			.filter(t -> type != DocumentType.PROFILE_PHOTO || !t.equals(FileTypes.PDF))
			.orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "document.unsupported-type"));
		LocalDate expiry = validateExpiry(type, expiresOn);
		validateVehicle(driverId, type, vehicleId);

		documents.findCurrent(driverId, type, vehicleId).ifPresent(previous -> {
			previous.supersede();
			// Flush before inserting the replacement: only one current document per type is allowed.
			documents.saveAndFlush(previous);
		});
		DriverDocument document = new DriverDocument(driverId, vehicleId, type, contentType, content.length,
				sha256(content), expiry, clock.instant());
		documents.save(document);
		storage.put(document.getStorageKey(), content);
		deleteFileIfRolledBack(document.getStorageKey());
		return document;
	}

	@Transactional
	public DriverDocument approve(UUID driverId, UUID documentId) {
		DriverDocument document = find(driverId, documentId);
		document.approve(currentUser.require().userId(), clock.instant(), BusinessTime.today(clock));
		documents.saveAndFlush(document);
		auditLog.record("driver.document.approved", "driver_document", documentId, Map.of("driverId", driverId,
				"type", document.getType()));
		return document;
	}

	@Transactional
	public DriverDocument reject(UUID driverId, UUID documentId, String reason) {
		String trimmed = reason == null ? "" : reason.strip();
		if (trimmed.length() < MIN_REASON_LENGTH) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "driver.reason-required", MIN_REASON_LENGTH);
		}
		DriverDocument document = find(driverId, documentId);
		document.reject(currentUser.require().userId(), trimmed, clock.instant());
		documents.saveAndFlush(document);
		auditLog.record("driver.document.rejected", "driver_document", documentId, Map.of("driverId", driverId,
				"type", document.getType()));
		return document;
	}

	/** Staff access to a document file. Viewing ID scans is audited. */
	@Transactional
	public StoredFile content(UUID driverId, UUID documentId) {
		DriverDocument document = find(driverId, documentId);
		byte[] content = storage.get(document.getStorageKey())
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document.not-found"));
		auditLog.record("driver.document.viewed", "driver_document", documentId, Map.of("driverId", driverId,
				"type", document.getType()));
		String filename = document.getType().name().toLowerCase(java.util.Locale.ROOT) + "-" + documentId + "."
				+ FileTypes.extension(document.getContentType());
		return new StoredFile(content, document.getContentType(), filename);
	}

	private DriverDocument find(UUID driverId, UUID documentId) {
		return documents.findByIdAndDriverId(documentId, driverId)
			.orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "document.not-found"));
	}

	private LocalDate validateExpiry(DocumentType type, LocalDate expiresOn) {
		if (!type.requiresExpiry()) {
			return null;
		}
		if (expiresOn == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "document.expiry-required");
		}
		if (expiresOn.isBefore(BusinessTime.today(clock))) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "document.expiry-in-past");
		}
		return expiresOn;
	}

	private void validateVehicle(UUID driverId, DocumentType type, UUID vehicleId) {
		if (!type.isVehicleDocument()) {
			if (vehicleId != null) {
				throw new ApiException(HttpStatus.BAD_REQUEST, "document.vehicle-not-allowed");
			}
			return;
		}
		UUID assigned = vehicles.activeVehicle(driverId).map(VehicleView::id).orElse(null);
		if (vehicleId == null || !Objects.equals(vehicleId, assigned)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "document.vehicle-required");
		}
	}

	/** Don't leave an orphaned file when the metadata insert rolls back. */
	private void deleteFileIfRolledBack(String storageKey) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status == STATUS_ROLLED_BACK) {
					storage.delete(storageKey);
				}
			}
		});
	}

	private static byte[] sha256(byte[] content) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(content);
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}
}
