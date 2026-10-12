package com.mobility.core.driver.document;

import com.mobility.core.driver.document.DocumentService.StoredFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Public on purpose: the signed token is the authorization (issued to staff, valid for minutes). */
@RestController
@Tag(name = "Document previews", description = "Signed, short-lived links to document files")
class DocumentPreviewController {

	private final DocumentPreviews previews;

	DocumentPreviewController(DocumentPreviews previews) {
		this.previews = previews;
	}

	@Operation(summary = "Open a document preview link",
			description = "Links come from `POST /api/v1/admin/drivers/{id}/documents/{docId}/files/{side}/preview-url` "
					+ "and expire after a few minutes. Invalid or expired links return 404.")
	@ApiResponse(responseCode = "200", description = "The file", content = {
			@Content(mediaType = "application/pdf", schema = @Schema(type = "string", format = "binary")),
			@Content(mediaType = "image/jpeg", schema = @Schema(type = "string", format = "binary")),
			@Content(mediaType = "image/png", schema = @Schema(type = "string", format = "binary")) })
	@GetMapping(DocumentPreviews.PATH + "{token}")
	ResponseEntity<byte[]> open(@PathVariable String token) {
		StoredFile file = previews.open(token);
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(file.contentType()))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(file.filename()).build().toString())
			.cacheControl(CacheControl.noStore().cachePrivate())
			.header("Referrer-Policy", "no-referrer")
			.body(file.content());
	}
}
