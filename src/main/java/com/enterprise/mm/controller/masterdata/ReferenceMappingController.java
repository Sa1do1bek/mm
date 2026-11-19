package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiAnalyzeRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiAnalyzeResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiApplyRequest;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingAiApplyResponse;
import com.enterprise.mm.payload.ReferenceMappingAiDtos.ReferenceMappingBulkResult;
import com.enterprise.mm.payload.ReferenceMappingResponse;
import com.enterprise.mm.payload.ReferenceMappingUpdateRequest;
import com.enterprise.mm.payload.ReferenceMappingCreateRequest;
import com.enterprise.mm.payload.ReferenceMappingSearchRequest;
import com.enterprise.mm.service.ReferenceMappingAiService;
import com.enterprise.mm.service.ReferenceMappingService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/reference-mappings")
@RequiredArgsConstructor
public class ReferenceMappingController {
    private static final String MUTATION_ROLES = "hasAnyRole('ADMIN','MM_MANAGER')";

    private final ReferenceMappingService service;
    private final ReferenceMappingAiService aiService;

    @GetMapping
    public ApiResponse<PageResponse<ReferenceMappingResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) java.util.UUID referenceId,
        @RequestParam(required = false) String referenceEntityType,
        @RequestParam(required = false) String externalSystem,
        @RequestParam(required = false) String accountingArea,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ReferenceMappingSearchRequest request = new ReferenceMappingSearchRequest(search, status, dateFrom, dateTo, includeDeleted, referenceId, referenceEntityType, externalSystem, accountingArea);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ReferenceMappingResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @PreAuthorize(MUTATION_ROLES)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ReferenceMappingResponse> create(@Valid @RequestBody ReferenceMappingCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingResponse> update(@PathVariable UUID id, @Valid @RequestBody ReferenceMappingUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/generate-defaults")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingBulkResult> generateDefaults(
        @RequestParam(defaultValue = "ONE_C") String externalSystem,
        @RequestParam(defaultValue = "MANAGEMENT") String accountingArea
    ) {
        return ApiResponse.ok(service.generateDefaultMappings(externalSystem, accountingArea));
    }

    @PostMapping("/resolve")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingResponse> resolve(
        @RequestParam String referenceEntityType,
        @RequestParam(required = false) UUID referenceId,
        @RequestParam(required = false) String referenceCode,
        @RequestParam String externalSystem,
        @RequestParam(defaultValue = "MANAGEMENT") String accountingArea
    ) {
        return ApiResponse.ok(service.resolve(referenceEntityType, referenceId, referenceCode, externalSystem, accountingArea).orElse(null));
    }

    @PostMapping("/ai/analyze")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingAiAnalyzeResponse> analyzeWithAi(@Valid @RequestBody ReferenceMappingAiAnalyzeRequest request) {
        return ApiResponse.ok(aiService.analyzeWithAi(request));
    }

    @PostMapping("/ai/apply")
    @PreAuthorize(MUTATION_ROLES)
    public ApiResponse<ReferenceMappingAiApplyResponse> applyAiSuggestions(@Valid @RequestBody ReferenceMappingAiApplyRequest request) {
        return ApiResponse.ok(aiService.applyAiSuggestions(request));
    }
}
