package com.enterprise.mm.controller;

import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalAsnView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCommitmentView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalCompletionView;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderDetail;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalPurchaseOrderSummary;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseDraftRequest;
import com.enterprise.mm.payload.SupplierPortalPurchaseOrderDtos.SupplierPortalResponseView;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.SupplierPortalPurchaseOrderExternalService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Public supplier-portal endpoints for the supplier PO collaboration domain. Split
 * into its own file rather than added to {@code SupplierPortalExternalController}
 * (already ~230 lines covering the RFQ portal domain) -- one controller per portal
 * domain area, same convention the RFQ vs. accreditation vs. submission portal
 * controllers already follow.
 */
@RestController
@RequestMapping("/api/public/supplier-portal/purchase-orders")
@RequiredArgsConstructor
public class SupplierPortalPurchaseOrderExternalController {
    private static final String TOKEN_HEADER = "X-Supplier-Portal-Token";
    private final SupplierPortalPurchaseOrderExternalService service;

    @GetMapping
    public ApiResponse<PageResponse<SupplierPortalPurchaseOrderSummary>> list(
        @RequestHeader(TOKEN_HEADER) String token,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(service.list(token, pageable)));
    }

    @GetMapping("/{purchaseOrderId}")
    public ApiResponse<SupplierPortalPurchaseOrderDetail> detail(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId
    ) {
        return ApiResponse.ok(service.detail(token, purchaseOrderId));
    }

    @GetMapping("/{purchaseOrderId}/artifacts/{attachmentId}/download")
    public ResponseEntity<InputStreamResource> downloadArtifact(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID attachmentId
    ) {
        AttachmentService.AttachmentDownload download = service.downloadArtifact(token, purchaseOrderId, attachmentId);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(download.metadata().getContentType()))
            .contentLength(download.metadata().getSizeBytes())
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename(download.metadata().getOriginalFileName())
                .build()
                .toString())
            .body(download.resource());
    }

    @PostMapping("/{purchaseOrderId}/responses/drafts")
    public ApiResponse<SupplierPortalResponseView> createResponseDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestBody SupplierPortalResponseDraftRequest request
    ) {
        return ApiResponse.created(service.createResponseDraft(token, purchaseOrderId, request));
    }

    @PutMapping("/{purchaseOrderId}/responses/drafts/{responseId}")
    public ApiResponse<SupplierPortalResponseView> updateResponseDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID responseId,
        @Valid @RequestBody SupplierPortalResponseDraftRequest request
    ) {
        return ApiResponse.ok(service.updateResponseDraft(token, purchaseOrderId, responseId, request));
    }

    @PostMapping("/{purchaseOrderId}/responses/{responseId}/submit")
    public ApiResponse<SupplierPortalResponseView> submitResponse(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID responseId
    ) {
        return ApiResponse.ok(service.submitResponse(token, purchaseOrderId, responseId));
    }

    @PostMapping("/{purchaseOrderId}/responses/{responseId}/replace")
    public ApiResponse<SupplierPortalResponseView> replaceResponse(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID responseId,
        @Valid @RequestBody SupplierPortalResponseDraftRequest request
    ) {
        return ApiResponse.created(service.replaceResponse(token, purchaseOrderId, responseId, request));
    }

    @PostMapping("/{purchaseOrderId}/responses/{responseId}/withdraw")
    public ApiResponse<SupplierPortalResponseView> withdrawResponse(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID responseId
    ) {
        return ApiResponse.ok(service.withdrawResponse(token, purchaseOrderId, responseId));
    }

    @GetMapping("/{purchaseOrderId}/commitments")
    public ApiResponse<PageResponse<SupplierPortalCommitmentView>> commitments(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId
    ) {
        return ApiResponse.ok(PageResponse.from(service.commitments(token, purchaseOrderId)));
    }

    @PostMapping("/{purchaseOrderId}/asns/drafts")
    public ApiResponse<SupplierPortalAsnView> createAsnDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestBody SupplierPortalAsnDraftRequest request
    ) {
        return ApiResponse.created(service.createAsnDraft(token, purchaseOrderId, request));
    }

    @PutMapping("/{purchaseOrderId}/asns/drafts/{asnId}")
    public ApiResponse<SupplierPortalAsnView> updateAsnDraft(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID asnId,
        @Valid @RequestBody SupplierPortalAsnDraftRequest request
    ) {
        return ApiResponse.ok(service.updateAsnDraft(token, purchaseOrderId, asnId, request));
    }

    @PostMapping("/{purchaseOrderId}/asns/{asnId}/submit")
    public ApiResponse<SupplierPortalAsnView> submitAsn(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @PathVariable UUID asnId,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.submitAsn(token, purchaseOrderId, asnId, idempotencyKey));
    }

    @PostMapping("/{purchaseOrderId}/service-completions")
    public ApiResponse<SupplierPortalCompletionView> submitServiceCompletion(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestBody SupplierPortalCompletionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.submitServiceCompletion(token, purchaseOrderId, request, java.util.List.of(), idempotencyKey));
    }

    @PostMapping(value = "/{purchaseOrderId}/service-completions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SupplierPortalCompletionView> submitServiceCompletionMultipart(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestPart("payload") SupplierPortalCompletionRequest request,
        @RequestPart(value = "files", required = false) java.util.List<MultipartFile> files,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.submitServiceCompletion(token, purchaseOrderId, request, files, idempotencyKey));
    }

    @PostMapping("/{purchaseOrderId}/work-completions")
    public ApiResponse<SupplierPortalCompletionView> submitWorkCompletion(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestBody SupplierPortalCompletionRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.submitWorkCompletion(token, purchaseOrderId, request, java.util.List.of(), idempotencyKey));
    }

    @PostMapping(value = "/{purchaseOrderId}/work-completions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<SupplierPortalCompletionView> submitWorkCompletionMultipart(
        @RequestHeader(TOKEN_HEADER) String token,
        @PathVariable UUID purchaseOrderId,
        @Valid @RequestPart("payload") SupplierPortalCompletionRequest request,
        @RequestPart(value = "files", required = false) java.util.List<MultipartFile> files,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.created(service.submitWorkCompletion(token, purchaseOrderId, request, files, idempotencyKey));
    }
}
