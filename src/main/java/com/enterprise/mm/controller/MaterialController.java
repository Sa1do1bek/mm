package com.enterprise.mm.controller;

import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.payload.*;
import com.google.protobuf.Api;
import lombok.RequiredArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.enterprise.mm.payload.AiAnalysisJobDtos.AiAnalysisJobResponse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.MaterialCommandViewDtos.*;
import com.enterprise.mm.service.MaterialService;
import com.enterprise.mm.service.AiAnalysisJobService;
import com.enterprise.mm.service.MaterialCommandViewService;
import jakarta.validation.Valid;
import java.util.UUID;

import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;

@RestController
@RequestMapping("/api/materials")
@RequiredArgsConstructor
public class MaterialController {
    private static final String WAREHOUSE_READ = "hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER',"
        + "'WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT',"
        + "'AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String DEMAND_SUPPLY_READ = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER',"
        + "'PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER',"
        + "'FINANCE_CONTROLLER','BUDGET_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD',"
        + "'DEPARTMENT_WORKER')";
    private static final String FINANCE_READ = "hasAnyRole('ADMIN','MM_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT',"
        + "'EXECUTIVE','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String SUPPLIER_READ = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER',"
        + "'PROCUREMENT_SPECIALIST','SUPPLIER_MANAGER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String QUALITY_READ = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER',"
        + "'PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','SUPPLIER_MANAGER',"
        + "'FINANCE_CONTROLLER','BUDGET_CONTROLLER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String STOCK_HEALTH_READ = "hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER',"
        + "'WAREHOUSE_HEAD','PROCUREMENT_MANAGER','FINANCE_CONTROLLER','ACCOUNTANT','EXECUTIVE','AUDITOR',"
        + "'VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";
    private static final String RELATIONSHIP_READ = "hasAnyRole('ADMIN','MM_MANAGER','PROCUREMENT_MANAGER',"
        + "'PROCUREMENT_SPECIALIST','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER',"
        + "'BUDGET_CONTROLLER','SUPPLIER_MANAGER','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER')";

    private final MaterialService service;
    private final MaterialCommandViewService commandViewService;
    private final AiAnalysisJobService aiAnalysisJobService;

    public record MaterialDuplicateSearchRequest(
        String name,
        UUID departmentId,
        UUID materialGroupId,
        Integer limit,
        Boolean includeInactive
    ) {
    }

    @GetMapping
    public ApiResponse<PageResponse<MaterialResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(required = false) UUID id,
        @RequestParam(required = false) String code,
        @RequestParam(required = false) String name,
        @RequestParam(required = false) String description,
        @RequestParam(required = false) Boolean deleted,
        @RequestParam(required = false) String createdBy,
        @RequestParam(required = false) String updatedBy,
        @RequestParam(required = false) Long version,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(required = false) UUID unitOfMeasureId,
        @RequestParam(required = false) ValuationMethod valuationMethod,
        @RequestParam(required = false) java.math.BigDecimal minStock,
        @RequestParam(required = false) java.math.BigDecimal safetyStock,
        @RequestParam(required = false) java.math.BigDecimal reorderPoint,
        @RequestParam(required = false) String barcode,
        @RequestParam(required = false) String qrCode,
        @RequestParam(required = false) String rfidTag,
        @RequestParam(required = false) String mxikCode,
        @RequestParam(required = false) UUID tnvedCodeId,
        @RequestParam(required = false) String tnvedCode,
        @RequestParam(required = false) String article,
        @RequestParam(required = false) String manufacturer,
        @RequestParam(required = false) String countryOfOrigin,
        @RequestParam(required = false) Integer shelfLifeDays,
        @RequestParam(required = false) String hazardClass,
        @RequestParam(required = false) java.math.BigDecimal maxStock,
        @RequestParam(required = false) UUID replacementMaterialId,
        @RequestParam(required = false) String lifecycleReason,
        @RequestParam(required = false) @org.springframework.format.annotation.DateTimeFormat(iso = org.springframework.format.annotation.DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime lifecycleChangedAt,
        @RequestParam(required = false) String materialType,
        @RequestParam(required = false) String materialSubtype,
        @RequestParam(required = false) String type,
        @RequestParam(defaultValue = "false") boolean includeAdvice,
        @RequestParam(required = false) Integer advicePeriodDays,
        @RequestParam(required = false) UUID adviceWarehouseId,
        @RequestParam(required = false) List<String> stockStatus,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            parseSort(sort),
            "e"
        );
        MaterialSearchRequest request = new MaterialSearchRequest(
            search, status, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted, createdBy, updatedBy, version,
            materialGroupId, unitOfMeasureId, valuationMethod, minStock, safetyStock, reorderPoint, barcode, qrCode,
            rfidTag, mxikCode, tnvedCodeId, tnvedCode, article, manufacturer, countryOfOrigin, shelfLifeDays, hazardClass, maxStock,
            replacementMaterialId, lifecycleReason, lifecycleChangedAt, materialType, type, stockStatus, materialSubtype
        );
        return ApiResponse.ok(PageResponse.from(service.search(
            request,
            pageable,
            includeAdvice,
            advicePeriodDays,
            adviceWarehouseId
        )));
    }

    @GetMapping("/dashboard")
    public ApiResponse<MaterialDashboardResponse> getDashboard() {
        return ApiResponse.ok(service.getDashboard());
    }

    @GetMapping("/{id}")
    public ApiResponse<MaterialResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping("/ai/duplicate-search/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> draftDuplicateSearchAiJob(
        @RequestBody MaterialDuplicateSearchRequest request,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        return ApiResponse.ok(aiAnalysisJobService.startMaterialDedupNameSearchJob(
            request == null ? null : request.name(),
            request == null ? null : request.departmentId(),
            request == null ? null : request.materialGroupId(),
            request == null ? null : request.limit(),
            request == null ? null : request.includeInactive(),
            language
        ));
    }

    @PostMapping("/{id}/ai/duplicate-search/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<AiAnalysisJobResponse> duplicateSearchAiJob(
        @PathVariable UUID id,
        @RequestHeader(name = "Accept-Language", required = false) String language
    ) {
        MaterialResponse material = service.get(id);
        return ApiResponse.ok(aiAnalysisJobService.startMaterialDedupSearchJob(id, material.getName(), language));
    }

    @GetMapping("/{id}/command-view")
    public ApiResponse<MaterialCommandViewResponse> commandViewSummary(@PathVariable UUID id, Locale locale) {
        return ApiResponse.ok(commandViewService.summary(id, locale));
    }

    @GetMapping("/{id}/command-view/availability")
    @PreAuthorize(WAREHOUSE_READ)
    public ApiResponse<PageResponse<AvailabilityRow>> commandViewAvailability(
        @PathVariable UUID id,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) UUID storageLocationId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam(defaultValue = "false") boolean positiveOnly,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("balanceAt"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.availability(id,
            new AvailabilityFilter(warehouseId, storageLocationId, stockBatchId, positiveOnly), pageable)));
    }

    @GetMapping("/{id}/command-view/demand-supply")
    @PreAuthorize(DEMAND_SUPPLY_READ)
    public ApiResponse<PageResponse<DemandSupplyRow>> commandViewDemandSupply(
        @PathVariable UUID id,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("relevantDate"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.demandSupply(id,
            new DemandSupplyFilter(dateFrom, dateTo, warehouseId, status), pageable)));
    }

    @GetMapping("/{id}/command-view/movements")
    @PreAuthorize(WAREHOUSE_READ)
    public ApiResponse<PageResponse<MovementRow>> commandViewMovements(
        @PathVariable UUID id,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) com.enterprise.mm.enums.MovementType movementType,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("postedAt"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.movements(id,
            new MovementFilter(dateFrom, dateTo, warehouseId, movementType, stockBatchId), pageable)));
    }

    @GetMapping("/{id}/command-view/valuation")
    @PreAuthorize(FINANCE_READ)
    public ApiResponse<ValuationResponse> commandViewValuation(
        @PathVariable UUID id,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("calculatedAt"), Sort.Order.desc("id")));
        return ApiResponse.ok(commandViewService.valuation(id, new ValuationFilter(dateFrom, dateTo), pageable));
    }

    @GetMapping("/{id}/command-view/suppliers")
    @PreAuthorize(SUPPLIER_READ)
    public ApiResponse<PageResponse<SupplierRelationshipRow>> commandViewSuppliers(
        @PathVariable UUID id,
        @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("evidenceDate"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.suppliers(id,
            new SupplierFilter(sourceType, dateFrom, dateTo), pageable)));
    }

    @GetMapping("/{id}/command-view/quality-compatibility")
    @PreAuthorize(QUALITY_READ)
    public ApiResponse<PageResponse<QualityCompatibilityRow>> commandViewQualityCompatibility(
        @PathVariable UUID id,
        @RequestParam(required = false) QualityCompatibilityKind kind,
        @RequestParam(required = false) String status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("effectiveFrom"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.qualityCompatibility(id,
            new QualityCompatibilityFilter(kind, status), pageable)));
    }

    @GetMapping("/{id}/command-view/stock-health")
    @PreAuthorize(STOCK_HEALTH_READ)
    public ApiResponse<StockHealthResponse> commandViewStockHealth(@PathVariable UUID id) {
        return ApiResponse.ok(commandViewService.stockHealth(id));
    }

    @GetMapping("/{id}/command-view/relationships")
    @PreAuthorize(RELATIONSHIP_READ)
    public ApiResponse<PageResponse<RelationshipRow>> commandViewRelationships(
        @PathVariable UUID id,
        @RequestParam(required = false) RelationshipType type,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = commandViewPageable(page, size,
            Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return ApiResponse.ok(PageResponse.from(commandViewService.relationships(id,
            new RelationshipFilter(type, status, dateFrom, dateTo), pageable)));
    }

    @GetMapping("/{id}/valuation-method-history")
    public ApiResponse<List<MaterialValuationMethodHistoryResponse>> valuationMethodHistory(@PathVariable UUID id) {
        return ApiResponse.ok(service.valuationMethodHistory(id));
    }

    @GetMapping("/created-range")
    public ApiResponse<PageResponse<MaterialResponse>> createdRange(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
     ) {

        return ApiResponse.ok(PageResponse.from(service.findByCreatedDateRange(fromDate, toDate, PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending()))));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<MaterialResponse> create(@Valid @RequestBody MaterialCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<MaterialResponse> update(@PathVariable UUID id, @Valid @RequestBody MaterialUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<MaterialResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/mark-obsolete")
    public ApiResponse<MaterialResponse> markObsolete(@PathVariable UUID id, @RequestBody(required = false) MaterialLifecycleRequest request) {
        return ApiResponse.ok(service.markObsolete(id, request));
    }

    @PostMapping("/{id}/assign-replacement")
    public ApiResponse<MaterialResponse> assignReplacement(@PathVariable UUID id, @RequestBody(required = false) MaterialLifecycleRequest request) {
        return ApiResponse.ok(service.assignReplacement(id, request));
    }

    @PostMapping("/{id}/prohibit")
    public ApiResponse<MaterialResponse> prohibit(@PathVariable UUID id, @RequestBody(required = false) MaterialLifecycleRequest request) {
        return ApiResponse.ok(service.prohibit(id, request));
    }

    @PostMapping("/{id}/reactivate")
    public ApiResponse<MaterialResponse> reactivate(@PathVariable UUID id, @RequestBody(required = false) MaterialLifecycleRequest request) {
        return ApiResponse.ok(service.reactivate(id, request));
    }

    private Sort parseSort(String sort) {
        if (sort == null || sort.isBlank()) {
            return Sort.by("createdAt").descending();
        }
        String[] parts = sort.split(",", -1);
        String property = parts[0].trim();
        if (!Set.of("createdAt", "updatedAt", "code", "name", "status").contains(property)) {
            throw new BadRequestException("Invalid filter or sort parameter");
        }
        Sort.Direction direction = Sort.Direction.DESC;
        if (parts.length > 1 && !parts[1].isBlank()) {
            try {
                direction = Sort.Direction.fromString(parts[1].trim());
            } catch (IllegalArgumentException ex) {
                throw new BadRequestException("Invalid filter or sort parameter");
            }
        }
        return Sort.by(direction, property);
    }

    private Pageable commandViewPageable(int page, int size, Sort lockedSort) {
        return PaginationUtils.pageRequest(page, size, lockedSort);
    }

}
