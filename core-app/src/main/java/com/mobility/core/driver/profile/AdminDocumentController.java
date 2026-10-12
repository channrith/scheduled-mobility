package com.mobility.core.driver.profile;

import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mobility.core.driver.DriverStatus;
import com.mobility.core.driver.document.DocumentService;
import com.mobility.core.driver.document.DocumentStatus;
import com.mobility.core.driver.document.DocumentType;
import com.mobility.core.driver.document.DocumentView;
import com.mobility.core.driver.document.DriverDocument;
import com.mobility.core.shared.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/documents")
@Tag(name = "Admin · Documents", description = "Document review across all drivers")
class AdminDocumentController {

	private final DocumentService documents;

	private final DriverRepository drivers;

	AdminDocumentController(DocumentService documents, DriverRepository drivers) {
		this.documents = documents;
		this.drivers = drivers;
	}

	record DriverBrief(UUID id, String fullName, String phone, DriverStatus status) {
	}

	record QueueItem(DocumentView document, DriverBrief driver) {
	}

	@Operation(summary = "Document review queue",
			description = "Oldest upload first. Defaults to `status=PENDING_REVIEW`; filter by `type`.")
	@GetMapping
	@PreAuthorize(AdminDriverController.ADMIN)
	PageResponse<QueueItem> list(@RequestParam(defaultValue = "PENDING_REVIEW") DocumentStatus status,
			@RequestParam(required = false) DocumentType type, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		Page<DriverDocument> result = documents.page(status, type, page, size);
		Map<UUID, Driver> byId = drivers.findAllById(result.getContent().stream().map(DriverDocument::getDriverId).distinct().toList())
			.stream()
			.collect(Collectors.toMap(Driver::getId, Function.identity()));
		return PageResponse.of(result, doc -> {
			Driver d = byId.get(doc.getDriverId());
			return new QueueItem(DocumentView.of(doc, true),
					new DriverBrief(d.getId(), d.getFullName(), d.getPhoneE164(), d.getStatus()));
		});
	}
}
