package com.mobility.core.driver.document;

import java.util.Locale;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** One side of a document. The content is in StorageService (encrypted) under {@link #getStorageKey()}. */
@Entity
@Table(schema = "driver", name = "driver_document_files")
public class DocumentFile {

	@Id
	private UUID id;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "document_id")
	private DriverDocument document;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private DocumentSide side;

	@Column(name = "storage_key", nullable = false, unique = true)
	private String storageKey;

	@Column(name = "content_type", nullable = false)
	private String contentType;

	@Column(name = "size_bytes", nullable = false)
	private int sizeBytes;

	@Column(nullable = false)
	private byte[] sha256;

	protected DocumentFile() {
	}

	DocumentFile(DriverDocument document, DocumentSide side, String contentType, int sizeBytes, byte[] sha256) {
		this.id = UUID.randomUUID();
		this.document = document;
		this.side = side;
		this.storageKey = "drivers/" + document.getDriverId() + "/" + document.getId() + "-"
				+ side.name().toLowerCase(Locale.ROOT);
		this.contentType = contentType;
		this.sizeBytes = sizeBytes;
		this.sha256 = sha256;
	}

	public DocumentSide getSide() {
		return side;
	}

	public String getStorageKey() {
		return storageKey;
	}

	public String getContentType() {
		return contentType;
	}

	public int getSizeBytes() {
		return sizeBytes;
	}
}
