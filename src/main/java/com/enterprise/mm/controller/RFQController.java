package com.enterprise.mm.controller;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.payload.BusinessActionDtos.TenderBidOpeningRequest;
import com.enterprise.mm.payload.BusinessActionDtos.TenderEvaluationProtocolRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.RFQResponse;
import com.enterprise.mm.payload.RFQUpdateRequest;
import com.enterprise.mm.payload.RFQCreateRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.FinalizePriceDiscoveryRequest;
import com.enterprise.mm.payload.PriceDiscoveryDtos.FinalizePriceDiscoveryResponse;
import com.enterprise.mm.payload.PriceDiscoveryDtos.PriceDiscoveryPreview;
import com.enterprise.mm.payload.RfqAggregationDtos.AggregationPreviewFilter;
import com.enterprise.mm.payload.RfqAggregationDtos.CancelRfqResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqRequest;
import com.enterprise.mm.payload.RfqAggregationDtos.CreateAggregatedRfqResponse;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqAggregationPreviewGroup;
import com.enterprise.mm.payload.RfqAggregationDtos.RfqSourcesResponse;
import com.enterprise.mm.payload.RfqPublicationDtos.PublicationPreview;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqRequest;
import com.enterprise.mm.payload.RfqPublicationDtos.PublishRfqResponse;
import com.enterprise.mm.payload.RfqPublicationDtos.ResendDeliveryRequest;
import com.enterprise.mm.payload.RfqPublicationDtos.ResendFailedResponse;
import com.enterprise.mm.payload.RfqProposalDtos.ManualProposalRequest;
import com.enterprise.mm.payload.RfqProposalDtos.ProposalView;
import com.enterprise.mm.payload.RfqProposalDtos.RfqQuotationImportPreview;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqQuotationComparison;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceDetail;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceFilter;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceRow;
import com.enterprise.mm.payload.SupplierResponse;
import com.enterprise.mm.payload.TenderBidOpeningProtocolResponse;
import com.enterprise.mm.payload.TenderEvaluationProtocolResponse;
import com.enterprise.mm.enums.RfqAggregationMode;
import com.enterprise.mm.service.RfqAggregationQueryService;
import com.enterprise.mm.service.RfqAggregationService;
import com.enterprise.mm.service.RFQService;
import com.enterprise.mm.service.IdempotencyService;
import com.enterprise.mm.service.PriceDiscoveryService;
import com.enterprise.mm.service.SupplierQuotationRevisionService;
import com.enterprise.mm.service.RfqWorkspaceQueryService;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.TenderProcessService;
import com.enterprise.mm.service.rfq.RfqDeliveryService;
import com.enterprise.mm.service.rfq.RfqAmendmentService;
import com.enterprise.mm.service.rfq.RfqAmendmentService.AmendmentDraft;
import com.enterprise.mm.service.rfq.RfqAmendmentService.CancelAmendmentRequest;
import com.enterprise.mm.service.rfq.RfqAmendmentService.StartAmendmentRequest;
import com.enterprise.mm.service.rfq.RfqDeliveryService.DeliveryView;
import com.enterprise.mm.service.rfq.RfqPublicationService;
import com.enterprise.mm.service.rfq.RfqQuotationExcelImporter;
import com.enterprise.mm.service.rfq.RfqSupplierCandidateService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import com.enterprise.mm.payload.RFQSearchRequest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/rfq")
@RequiredArgsConstructor
public class RFQController {
    private final RFQService service;
    private final RfqAggregationQueryService aggregationQueryService;
    private final RfqAggregationService aggregationService;
    private final RfqPublicationService publicationService;
    private final RfqDeliveryService deliveryService;
    private final SupplierQuotationRevisionService quotationRevisionService;
    private final RfqQuotationExcelImporter quotationExcelImporter;
    private final IdempotencyService idempotencyService;
    private final RfqWorkspaceQueryService workspaceQueryService;
    private final AiAnalysisJobService aiAnalysisJobService;
    private final RfqAmendmentService amendmentService;
    private final PriceDiscoveryService priceDiscoveryService;
    private final RfqSupplierCandidateService supplierCandidateService;

    @GetMapping("/workspace")
    public ApiResponse<PageResponse<RfqWorkspaceRow>> workspace(
        @RequestParam(required = false) List<String> status,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dueDateTo,
        @RequestParam(required = false) Boolean overdue,
        @RequestParam(required = false) String responsibleBuyer,
        @RequestParam(required = false) UUID purchaseRequestId,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) String responseProgress,
        @RequestParam(required = false) List<String> deliveryStatus,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdAtTo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishedAtFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime publishedAtTo,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        RfqWorkspaceFilter filter = new RfqWorkspaceFilter(
            status,
            dueDateFrom,
            dueDateTo,
            overdue,
            responsibleBuyer,
            purchaseRequestId,
            materialId,
            materialGroupId,
            supplierId,
            responseProgress,
            deliveryStatus,
            createdAtFrom,
            createdAtTo,
            publishedAtFrom,
            publishedAtTo,
            search
        );
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(workspaceQueryService.search(filter, pageable)));
    }

    @GetMapping("/{id}/workspace")
    public ApiResponse<RfqWorkspaceDetail> workspaceDetail(@PathVariable UUID id) {
        return ApiResponse.ok(workspaceQueryService.detail(id));
    }

    @GetMapping("/{id}/quotation-comparison")
    public ApiResponse<RfqQuotationComparison> quotationComparison(@PathVariable UUID id) {
        return ApiResponse.ok(workspaceQueryService.comparison(id));
    }

    @PostMapping("/{id}/ai/quotation-comparison/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> quotationComparisonAiJob(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        workspaceQueryService.comparison(id);
        return ApiResponse.ok(aiAnalysisJobService.startRfqQuotationComparisonJob(id, language));
    }

    @GetMapping("/supplier-candidates")
    public ApiResponse<PageResponse<SupplierResponse>> supplierCandidates(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.Set<UUID> materialGroupIds,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.by("name").ascending());
        return ApiResponse.ok(PageResponse.from(supplierCandidateService.search(search, materialGroupIds, pageable)));
    }

    @GetMapping
    public ApiResponse<PageResponse<RFQResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime createdAtTo,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime updatedAtTo,
        @RequestParam(required = false) String createdBy,
        @RequestParam(required = false) String updatedBy,
        @RequestParam(required = false) java.util.UUID purchaseRequestId,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateFrom,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE) java.time.LocalDate dueDateTo,
        @RequestParam(required = false) java.util.List<String> tenderProcedureType,
        @RequestParam(required = false) java.util.List<String> selectionRule,
        @RequestParam(required = false) Integer minQuotationsRequired,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        RFQSearchRequest request = new RFQSearchRequest(search, status, dateFrom, dateTo, includeDeleted, code, name, createdAtFrom, createdAtTo, updatedAtFrom, updatedAtTo, createdBy, updatedBy, purchaseRequestId, dueDateFrom, dueDateTo, tenderProcedureType, selectionRule, minQuotationsRequired);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<RFQResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/aggregation-preview")
    public ApiResponse<PageResponse<RfqAggregationPreviewGroup>> aggregationPreview(
        @RequestParam(required = false) RfqAggregationMode groupBy,
        @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) UUID departmentId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate requiredDateTo,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        LocalDate effectiveRequiredDateFrom = requiredDateFrom == null ? dateFrom : requiredDateFrom;
        LocalDate effectiveRequiredDateTo = requiredDateTo == null ? dateTo : requiredDateTo;
        AggregationPreviewFilter filter = new AggregationPreviewFilter(
            groupBy,
            materialId,
            materialGroupId,
            departmentId,
            effectiveRequiredDateFrom,
            effectiveRequiredDateTo,
            search);
        return ApiResponse.ok(PageResponse.from(aggregationQueryService.preview(filter, pageable)));
    }

    @GetMapping("/{id}/sources")
    public ApiResponse<RfqSourcesResponse> sources(@PathVariable UUID id) {
        return ApiResponse.ok(aggregationQueryService.sources(id));
    }

    @PostMapping("/from-pr-items")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreateAggregatedRfqResponse> createFromPrItems(
        @Valid @RequestBody CreateAggregatedRfqRequest request
    ) {
        return ApiResponse.created(aggregationService.create(request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<CancelRfqResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(aggregationService.cancel(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RFQResponse> create(@Valid @RequestBody RFQCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @GetMapping("/{id}/publication-preview")
    public ApiResponse<PublicationPreview> publicationPreview(@PathVariable UUID id) {
        return ApiResponse.ok(publicationService.preview(id));
    }

    @GetMapping("/{id}/price-discovery-preview")
    public ApiResponse<PriceDiscoveryPreview> priceDiscoveryPreview(@PathVariable UUID id) {
        return ApiResponse.ok(priceDiscoveryService.preview(id));
    }

    @PostMapping("/{id}/finalize-price-discovery")
    public ApiResponse<FinalizePriceDiscoveryResponse> finalizePriceDiscovery(
        @PathVariable UUID id,
        @Valid @RequestBody FinalizePriceDiscoveryRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(priceDiscoveryService.finalizePriceDiscovery(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/publish")
    public ApiResponse<PublishRfqResponse> publish(
        @PathVariable UUID id,
        @Valid @RequestBody PublishRfqRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(publicationService.publish(id, request, idempotencyKey));
    }

    @PostMapping("/{id}/amendments")
    public ApiResponse<AmendmentDraft> startAmendment(
        @PathVariable UUID id,
        @RequestBody StartAmendmentRequest request
    ) {
        return ApiResponse.ok(amendmentService.start(id, request));
    }

    @PostMapping("/{id}/amendments/cancel")
    public ApiResponse<AmendmentDraft> cancelAmendment(
        @PathVariable UUID id,
        @RequestBody CancelAmendmentRequest request
    ) {
        return ApiResponse.ok(amendmentService.cancel(id, request));
    }

    @PostMapping("/{id}/deliveries/{supplierId}/resend")
    public ApiResponse<DeliveryView> resend(
        @PathVariable UUID id,
        @PathVariable UUID supplierId,
        @Valid @RequestBody ResendDeliveryRequest request
    ) {
        return ApiResponse.ok(deliveryService.resend(
            id,
            supplierId,
            new RfqDeliveryService.ResendCommand(
                request.expectedVersion(),
                request.correctedRecipientEmail(),
                request.reason()
            )
        ));
    }

    @PostMapping("/{id}/deliveries/resend-failed")
    public ApiResponse<ResendFailedResponse> resendFailed(@PathVariable UUID id) {
        return ApiResponse.ok(new ResendFailedResponse(deliveryService.resendFailed(id)));
    }

    @PostMapping(value = "/{id}/quotations/manual", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ProposalView> manualProposal(
        @PathVariable UUID id,
        @Valid @RequestPart("request") ManualProposalRequest request,
        @RequestPart("originalFile") MultipartFile originalFile,
        @RequestPart(value = "supportingFiles", required = false) List<MultipartFile> supportingFiles,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        List<MultipartFile> supporting = supportingFiles == null ? List.of() : supportingFiles;
        ManualProposalIdempotencyPayload payload = new ManualProposalIdempotencyPayload(
            request,
            fileIdentity(originalFile),
            supporting.stream().map(this::fileIdentity).toList()
        );
        ProposalView response = idempotencyService.execute(
            "RFQ_MANUAL_PROPOSAL",
            id,
            idempotencyKey,
            payload,
            ProposalView.class,
            () -> quotationRevisionService.recordManualProposal(id, request, originalFile, supporting)
        );
        return ApiResponse.created(response);
    }

    @PostMapping(value = "/{id}/quotations/import-preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<RfqQuotationImportPreview> quotationImportPreview(
        @PathVariable UUID id,
        @RequestPart("file") MultipartFile file
    ) {
        return ApiResponse.ok(quotationExcelImporter.preview(id, file));
    }

    @PutMapping("/{id}")
    public ApiResponse<RFQResponse> update(@PathVariable UUID id, @Valid @RequestBody RFQUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<RFQResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private final TenderProcessService tenderProcessService;

    @PostMapping("/{id}/open-bids")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<TenderBidOpeningProtocolResponse> openBids(@PathVariable UUID id, @RequestBody(required = false) TenderBidOpeningRequest request) {
        return ApiResponse.ok(tenderProcessService.openBids(id, request));
    }

    @PostMapping("/{id}/run-conflict-checks")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<List<UUID>> runConflictChecks(@PathVariable UUID id) {
        return ApiResponse.ok(tenderProcessService.runConflictChecks(id));
    }

    @PostMapping("/{id}/create-evaluation-protocol")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER','PROCUREMENT_SPECIALIST')")
    public ApiResponse<TenderEvaluationProtocolResponse> createEvaluationProtocol(@PathVariable UUID id, @RequestBody TenderEvaluationProtocolRequest request) {
        return ApiResponse.ok(tenderProcessService.createEvaluationProtocol(id, request));
    }

    private FileIdentity fileIdentity(MultipartFile file) {
        return file == null
            ? null
            : new FileIdentity(file.getOriginalFilename(), file.getContentType(), file.getSize());
    }

    private record FileIdentity(String fileName, String contentType, long sizeBytes) {
    }

    private record ManualProposalIdempotencyPayload(
        ManualProposalRequest request,
        FileIdentity originalFile,
        List<FileIdentity> supportingFiles
    ) {
    }

}
