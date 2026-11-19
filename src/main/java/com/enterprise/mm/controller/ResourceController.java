package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.ResourceFileSafetyService;
import com.enterprise.mm.service.ResourceLibraryService;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/resources")
@RequiredArgsConstructor
public class ResourceController {
    private final ResourceLibraryService resources;
    private final ResourceFileSafetyService safety;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResourceLibraryService.ResourceView> upload(
        @RequestParam(required = false) String title,
        @RequestParam MultipartFile file,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        safety.requireSafe(file);
        return ApiResponse.created(resources.create(title, file, idempotencyKey));
    }

    @GetMapping
    public ApiResponse<ResourceLibraryService.Page<ResourceLibraryService.ResourceView>> list(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(resources.list(search, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ResourceLibraryService.ResourceView> get(@PathVariable UUID id) {
        return ApiResponse.ok(resources.get(id));
    }

    @GetMapping("/{id}/versions")
    public ApiResponse<List<ResourceLibraryService.VersionView>> versions(@PathVariable UUID id) {
        return ApiResponse.ok(resources.versions(id));
    }

    @GetMapping("/{id}/versions/{versionId}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable UUID id, @PathVariable UUID versionId) {
        return file(id, versionId, false);
    }

    @GetMapping("/{id}/versions/{versionId}/preview")
    public ResponseEntity<InputStreamResource> preview(@PathVariable UUID id, @PathVariable UUID versionId) {
        return file(id, versionId, true);
    }

    private ResponseEntity<InputStreamResource> file(UUID id, UUID versionId, boolean preview) {
        AttachmentService.AttachmentDownload download = resources.download(id, versionId);
        AttachmentDto metadata = download.metadata();
        boolean inline = preview && "application/pdf".equals(metadata.getContentType());
        ContentDisposition disposition = (inline ? ContentDisposition.inline() : ContentDisposition.attachment())
            .filename(metadata.getOriginalFileName()).build();
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(metadata.getContentType()))
            .contentLength(metadata.getSizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
            .header("X-Content-Type-Options", "nosniff")
            .body(download.resource());
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<ResourceLibraryService.ResourceView> archive(@PathVariable UUID id) {
        return ApiResponse.ok(resources.archive(id));
    }

    @PostMapping("/{id}/versions/{versionId}/reindex")
    public ApiResponse<ResourceLibraryService.ResourceView> reindex(@PathVariable UUID id, @PathVariable UUID versionId) {
        return ApiResponse.ok(resources.reindex(id, versionId));
    }

    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ResourceLibraryService.ResourceView> replace(
        @PathVariable UUID id,
        @RequestParam(required = false) String title,
        @RequestParam MultipartFile file,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        safety.requireSafe(file);
        return ApiResponse.created(resources.replace(id, title, file, idempotencyKey));
    }

    @ExceptionHandler(ResourceFileSafetyService.ScanUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> scanUnavailable(ResourceFileSafetyService.ScanUnavailableException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(ApiResponse.error(ex.getMessage(), "RESOURCE_SCANNER_UNAVAILABLE"));
    }
}
