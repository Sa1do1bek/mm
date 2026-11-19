package com.enterprise.mm.controller;

import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.InventoryAiDraftService;
import com.enterprise.mm.service.InventoryAiDraftService.DraftResponse;
import com.enterprise.mm.service.InventoryOrderAnalysisScopeService;
import com.enterprise.mm.error.NotFoundException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestPart;

@RestController
@RequestMapping("/api/inventory/ai-drafts")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class InventoryAiDraftController {
    private final InventoryAiDraftService drafts;
    private final AiAnalysisJobService jobs;
    private final InventoryOrderAnalysisScopeService orderScopes;
    @Value("${mm.ai.inventory-scoped-order-enabled:false}")
    private boolean scopedOrderEnabled;

    public record OrderAnalysisCapability(boolean enabled, InventoryOrderAnalysisScopeService.Scope scope, String reason) {}

    @GetMapping("/order-analysis-scope")
    public ApiResponse<OrderAnalysisCapability> orderAnalysisScope() {
        if (!scopedOrderEnabled) return ApiResponse.ok(new OrderAnalysisCapability(false, null, "DISABLED"));
        try {
            return ApiResponse.ok(new OrderAnalysisCapability(true, orderScopes.current(), null));
        } catch (AccessDeniedException missingAssignment) {
            return ApiResponse.ok(new OrderAnalysisCapability(false, null, "DEPARTMENT_ASSIGNMENT_REQUIRED"));
        }
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<DraftResponse> create() {
        return ApiResponse.created(drafts.create());
    }

    @PostMapping(value = "/{id}/files", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AttachmentDto> upload(@PathVariable UUID id, @RequestPart MultipartFile file) {
        return ApiResponse.created(drafts.upload(id, file));
    }

    @GetMapping("/{id}/files")
    public ApiResponse<List<AttachmentDto>> files(@PathVariable UUID id) {
        return ApiResponse.ok(drafts.files(id));
    }

    @GetMapping("/{id}/analysis-jobs/latest")
    public ApiResponse<AiAnalysisJobResponse> latestAnalysis(@PathVariable UUID id) {
        return ApiResponse.ok(jobs.latest(AiAnalysisType.INVENTORY_DOCUMENT_EXTRACTION,
            InventoryAiDraftService.ENTITY_TYPE, id));
    }

    @GetMapping("/{id}/analysis-jobs/{jobId}")
    public ApiResponse<AiAnalysisJobResponse> analysisJob(@PathVariable UUID id, @PathVariable UUID jobId) {
        AiAnalysisJobResponse job = jobs.getJob(jobId);
        if (!InventoryAiDraftService.ENTITY_TYPE.equals(job.entityType()) || !id.equals(job.entityId()))
            throw new NotFoundException("Inventory draft analysis job not found");
        return ApiResponse.ok(job);
    }

    @DeleteMapping("/{id}/files/{fileId}")
    public ApiResponse<Void> remove(@PathVariable UUID id, @PathVariable UUID fileId) {
        drafts.removeFile(id, fileId);
        return ApiResponse.message("deleted");
    }

    @PostMapping("/{id}/analysis-jobs")
    @PreAuthorize("hasRole('ADMIN') or (hasAnyRole('MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD') and !hasRole('DEPARTMENT_HEAD'))")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> analyze(@PathVariable UUID id,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        return ApiResponse.ok(jobs.startInventoryDraftJob(id, language));
    }

    @PostMapping("/{id}/order-analysis-jobs")
    @PreAuthorize("hasRole('ADMIN')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> analyzeOrder(@PathVariable UUID id,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        return ApiResponse.ok(jobs.startInventoryDraftJob(id, language, true));
    }

    @PostMapping("/{id}/scoped-order-analysis-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> analyzeScopedOrder(@PathVariable UUID id,
        @RequestHeader(value = "Accept-Language", required = false) String language) {
        if (!scopedOrderEnabled) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Scoped inventory order analysis is not enabled");
        return ApiResponse.ok(jobs.startScopedInventoryOrderJob(id, language));
    }
}
