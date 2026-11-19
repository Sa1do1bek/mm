package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ProductionEvidenceResponse;
import com.enterprise.mm.payload.ProductionEvidenceUpdateRequest;
import com.enterprise.mm.payload.ProductionEvidenceCreateRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionEvidenceAcceptanceRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionEvidenceSecurityChecklistRequest;
import com.enterprise.mm.payload.ProductionEvidenceDtos.ProductionOpsCheckRequest;
import com.enterprise.mm.payload.ProductionEvidenceSearchRequest;
import com.enterprise.mm.service.ProductionEvidenceService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/production-evidence")
@RequiredArgsConstructor
public class ProductionEvidenceController {
    private final ProductionEvidenceService service;

    @GetMapping
    public ApiResponse<PageResponse<ProductionEvidenceResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ProductionEvidenceSearchRequest request = new ProductionEvidenceSearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ProductionEvidenceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProductionEvidenceResponse> create(@Valid @RequestBody ProductionEvidenceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ProductionEvidenceResponse> update(@PathVariable UUID id, @Valid @RequestBody ProductionEvidenceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ProductionEvidenceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/from-preflight")
    public ApiResponse<ProductionEvidenceResponse> fromPreflight(@RequestParam(defaultValue = "true") boolean liveCheck) {
        return ApiResponse.created(service.capturePreflight(liveCheck));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ProductionEvidenceResponse> upload(@RequestParam MultipartFile file) {
        return ApiResponse.created(service.uploadEvidenceJson(file));
    }

    @PostMapping("/ops-check")
    public ApiResponse<ProductionEvidenceResponse> opsCheck(@RequestBody(required = false) ProductionOpsCheckRequest request) {
        return ApiResponse.created(service.runOperationsCheck(request));
    }

    @PostMapping("/{id}/accept")
    public ApiResponse<ProductionEvidenceResponse> accept(@PathVariable UUID id, @RequestBody(required = false) ProductionEvidenceAcceptanceRequest request) {
        return ApiResponse.ok(service.accept(id, request));
    }

    @PostMapping("/{id}/security-checklist")
    public ApiResponse<ProductionEvidenceResponse> securityChecklist(@PathVariable UUID id, @Valid @RequestBody ProductionEvidenceSecurityChecklistRequest request) {
        return ApiResponse.ok(service.updateSecurityChecklist(id, request));
    }

    @GetMapping("/{id}/go-live.pdf")
    public ResponseEntity<byte[]> goLivePdf(@PathVariable UUID id) {
        return file("go-live-readiness.pdf", MediaType.APPLICATION_PDF_VALUE, service.goLivePdf(id));
    }

    @GetMapping("/{id}/go-live.xlsx")
    public ResponseEntity<byte[]> goLiveExcel(@PathVariable UUID id) {
        return file("go-live-readiness.xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", service.goLiveExcel(id));
    }

    private ResponseEntity<byte[]> file(String fileName, String contentType, byte[] bytes) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + fileName)
            .contentType(MediaType.parseMediaType(contentType))
            .body(bytes);
    }

}

