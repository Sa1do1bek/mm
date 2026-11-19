package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierAccreditationDocumentCreateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentActionRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentResponse;
import com.enterprise.mm.payload.SupplierAccreditationDocumentUpdateRequest;
import com.enterprise.mm.payload.SupplierAccreditationDocumentSearchRequest;
import com.enterprise.mm.service.SupplierAccreditationDocumentService;
import com.enterprise.mm.service.SupplierAccreditationEvidenceAggregateService;
import com.enterprise.mm.service.SupplierAccreditationDocumentCommandService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/supplier-accreditation-documents")
@RequiredArgsConstructor
public class SupplierAccreditationDocumentController {
    private final SupplierAccreditationDocumentService service;
    private final SupplierAccreditationEvidenceAggregateService aggregateService;
    private final SupplierAccreditationDocumentCommandService commandService;

    @GetMapping
    public ApiResponse<PageResponse<SupplierAccreditationDocumentResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate validTo,
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
        SupplierAccreditationDocumentSearchRequest request = new SupplierAccreditationDocumentSearchRequest(search, status, dateFrom, dateTo, includeDeleted, validFrom, validTo);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<SupplierAccreditationDocumentResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierAccreditationDocumentResponse> create(@Valid @RequestBody SupplierAccreditationDocumentCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PostMapping(value = "/with-file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<SupplierAccreditationDocumentResponse> createWithFile(
        @RequestParam UUID applicationId,
        @RequestParam String documentType,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate validFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) java.time.LocalDate validTo,
        @RequestParam MultipartFile file,
        @RequestHeader("Idempotency-Key") String idempotencyKey
    ) {
        return ApiResponse.created(aggregateService.create(
            applicationId, documentType, name, validFrom, validTo, file, idempotencyKey));
    }

    @PutMapping("/{id}")
    public ApiResponse<SupplierAccreditationDocumentResponse> update(@PathVariable UUID id, @Valid @RequestBody SupplierAccreditationDocumentUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<SupplierAccreditationDocumentResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/verify")
    public ApiResponse<SupplierAccreditationDocumentResponse> verify(
        @PathVariable UUID id,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierAccreditationDocumentActionRequest request
    ) {
        return ApiResponse.ok(commandService.verify(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<SupplierAccreditationDocumentResponse> reject(
        @PathVariable UUID id,
		@RequestHeader("Idempotency-Key") String idempotencyKey,
        @Valid @RequestBody SupplierAccreditationDocumentActionRequest request
    ) {
        return ApiResponse.ok(commandService.reject(id, request, idempotencyKey));
    }
}
