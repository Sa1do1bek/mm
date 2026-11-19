package com.enterprise.mm.controller;

import java.util.Set;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.mapper.StockValuationMapper;
import com.enterprise.mm.service.InventoryValuationPolicyService;
import com.enterprise.mm.service.StockValuationRecordService;
import com.enterprise.mm.service.StockValuationService;
import com.enterprise.mm.service.StockValuationCostSelectionService;
import com.enterprise.mm.service.StockValuationCostJobService;
import com.enterprise.mm.service.StockValuationHistoryService;
import com.enterprise.mm.service.StockValuationRunService;
import com.enterprise.mm.service.FifoLayerAllocationService;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.enums.StockValuationRunScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
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

@RestController
@RequestMapping("/api/valuation")
@Tag(name = "Valuation")
public class StockValuationController {
    private static final Set<String> SORT_FIELDS = Set.of(
        "code",
        "name",
        "status",
        "materialId",
        "warehouseId",
        "valuationMethod",
        "quantity",
        "unitCost",
        "totalValue",
        "carryingValue",
        "valuationBasis",
        "calculatedAt",
        "sourceType",
        "sourceId",
        "createdAt",
        "updatedAt"
    );
    private static final Set<String> HISTORY_SORT_FIELDS = Set.of(
        "materialId",
        "warehouseId",
        "valuationMethod",
        "quantity",
        "unitCost",
        "totalValue",
        "carryingValue",
        "valuationBasis",
        "sourceType",
        "sourceId",
        "calculatedAt",
        "createdAt"
    );
    private static final Set<String> RUN_SORT_FIELDS = Set.of(
        "code",
        "name",
        "status",
        "scopeType",
        "warehouseId",
        "valuationMethod",
        "createdAt",
        "updatedAt"
    );
    private final StockValuationRecordService service;


    @GetMapping
    public ApiResponse<PageResponse<StockValuationResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.util.UUID warehouseId,
        @RequestParam(required = false) java.util.List<java.util.UUID> materialIds,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir, SORT_FIELDS, Sort.by("createdAt").descending());
        Pageable pageable = PaginationUtils.pageRequest(page, size, sort);
        StockValuationRecordSearchRequest request = new StockValuationRecordSearchRequest(search, status, warehouseId, materialIds, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockValuationResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockValuationResponse> create(@Valid @RequestBody StockValuationCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<StockValuationResponse> update(@PathVariable UUID id, @Valid @RequestBody StockValuationUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<StockValuationResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    private final StockValuationService valuationService;
    private final StockValuationMapper mapper;
    private final InventoryValuationPolicyService policyService;
    private final StockValuationCostSelectionService stockValuationCostSelectionService;
    private final StockValuationCostJobService stockValuationCostJobService;
    private final StockValuationHistoryService stockValuationHistoryService;
    private final StockValuationRunService stockValuationRunService;
    private final FifoLayerAllocationService fifoLayerAllocationService;

    @org.springframework.beans.factory.annotation.Autowired
    public StockValuationController(
        StockValuationRecordService service,
        StockValuationService valuationService,
        StockValuationMapper mapper,
        InventoryValuationPolicyService policyService,
        StockValuationCostSelectionService stockValuationCostSelectionService,
        StockValuationCostJobService stockValuationCostJobService,
        StockValuationHistoryService stockValuationHistoryService,
        StockValuationRunService stockValuationRunService,
        FifoLayerAllocationService fifoLayerAllocationService
    ) {
        this.service = service;
        this.valuationService = valuationService;
        this.mapper = mapper;
        this.policyService = policyService;
        this.stockValuationCostSelectionService = stockValuationCostSelectionService;
        this.stockValuationCostJobService = stockValuationCostJobService;
        this.stockValuationHistoryService = stockValuationHistoryService;
        this.stockValuationRunService = stockValuationRunService;
        this.fifoLayerAllocationService = fifoLayerAllocationService;
    }

    public StockValuationController(
        StockValuationRecordService service,
        StockValuationService valuationService,
        StockValuationMapper mapper,
        InventoryValuationPolicyService policyService,
        StockValuationCostSelectionService stockValuationCostSelectionService,
        StockValuationCostJobService stockValuationCostJobService,
        StockValuationHistoryService stockValuationHistoryService,
        StockValuationRunService stockValuationRunService
    ) {
        this(
            service,
            valuationService,
            mapper,
            policyService,
            stockValuationCostSelectionService,
            stockValuationCostJobService,
            stockValuationHistoryService,
            stockValuationRunService,
            null
        );
    }

    @GetMapping("/{id}/fifo-layers")
    public ApiResponse<FifoLayerAllocationResponse> fifoLayers(@PathVariable UUID id) {
        return ApiResponse.ok(fifoLayerAllocationService.get(id));
    }

    @GetMapping("/cost")
    public ApiResponse<StockValuationCostResponse> cost(
        @RequestParam UUID materialId,
        @RequestParam(required = false) List<UUID> warehouseId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam BigDecimal quantity,
        @RequestParam(defaultValue = "LIFO") ValuationMethod valuationMethod,
        @RequestParam(required = false, defaultValue = "MULTI_WAREHOUSE") StockValuationRunScope scopeType
    ) {
        List<UUID> targetWarehouses = (scopeType == StockValuationRunScope.GLOBAL)
            ? java.util.Collections.emptyList()
            : (warehouseId != null ? warehouseId : java.util.Collections.emptyList());

        return ApiResponse.ok(new StockValuationCostResponse(
            valuationMethod,
            stockValuationCostSelectionService.calculateCost(valuationMethod, materialId, targetWarehouses, stockBatchId, quantity)
        ));
    }

    @GetMapping({"/cost/stocks", "/costs/stocks"})
    @Operation(summary = "Calculate stock costs synchronously (legacy compatibility endpoint)")
    public ApiResponse<StockValuationSelectedStocksResponse> stockCosts(
            @RequestParam(required = false) UUID warehouseId,
            @RequestParam(required = false) UUID materialId
    ) {
        return ApiResponse.ok(new StockValuationSelectedStocksResponse(
                stockValuationCostSelectionService.calculateStockCosts(warehouseId, materialId)));
    }

    @PostMapping("/costs/stocks/jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start an asynchronous stock valuation cost job")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "202", description = "Job queued")
    public ApiResponse<StockValuationCostJobResponse> startStockCostJob(
        @RequestBody(required = false) StockValuationCostJobStartRequest request
    ) {
        UUID warehouseId = request == null ? null : request.warehouseId();
        com.enterprise.mm.enums.ValuationMethod valuationMethod = request == null ? null : request.valuationMethod();
        return ApiResponse.ok(stockValuationCostJobService.startJob(warehouseId, valuationMethod));
    }

    @GetMapping("/costs/stocks/jobs/{jobId}")
    @Operation(summary = "Get asynchronous stock valuation cost job status")
    public ApiResponse<StockValuationCostJobResponse> getStockCostJob(@PathVariable UUID jobId) {
        return ApiResponse.ok(stockValuationCostJobService.getJob(jobId));
    }

    @GetMapping("/costs/stocks/jobs")
    @Operation(summary = "List asynchronous stock valuation cost jobs")
    public ApiResponse<PageResponse<StockValuationCostJobResponse>> listStockCostJobs(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(stockValuationCostJobService.listJobs(
            PaginationUtils.pageRequest(page, size, Sort.by("createdAt").descending()))));
    }

    @GetMapping("/costs/stocks/jobs/{jobId}/results")
    @Operation(summary = "List stock valuation cost job results")
    public ApiResponse<PageResponse<StockValuationCostJobResultResponse>> getStockCostJobResults(
        @PathVariable UUID jobId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(stockValuationCostJobService.getResults(
            jobId, PaginationUtils.pageRequest(page, size, Sort.by("sequenceNo").ascending()))));
    }

    @GetMapping("/history")
    @Operation(summary = "List stock valuation history snapshots")
    public ApiResponse<PageResponse<StockValuationHistoryResponse>> valuationHistory(
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) java.util.List<UUID> materialIds,
        @RequestParam(required = false) com.enterprise.mm.enums.ValuationMethod valuationMethod,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir, HISTORY_SORT_FIELDS, Sort.by("calculatedAt").descending());
        return ApiResponse.ok(PageResponse.from(stockValuationHistoryService.search(
            warehouseId,
            materialIds,
            valuationMethod,
            dateFrom,
            dateTo,
            PaginationUtils.pageRequest(page, size, sort)
        )));
    }

    @GetMapping("/runs")
    @Operation(summary = "List stock valuation runs")
    public ApiResponse<PageResponse<StockValuationRunResponse>> valuationRuns(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) StockValuationRunScope scopeType,
        @RequestParam(required = false) ValuationMethod valuationMethod,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir
    ) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir, RUN_SORT_FIELDS, Sort.by("createdAt").descending());
        return ApiResponse.ok(PageResponse.from(stockValuationRunService.search(
            search,
            status,
            warehouseId,
            scopeType,
            valuationMethod,
            dateFrom,
            dateTo,
            includeDeleted,
            PaginationUtils.pageRequest(page, size, sort)
        )));
    }

    @GetMapping("/runs/statistics")
    @Operation(summary = "Get stock valuation runs and global inventory statistics")
    public ApiResponse<StockValuationStatisticsResponse> getStatistics() {
        return ApiResponse.ok(stockValuationRunService.getStatistics());
    }

    @GetMapping("/runs/{id}")
    @Operation(summary = "Get stock valuation run")
    public ApiResponse<StockValuationRunResponse> valuationRun(@PathVariable UUID id) {
        return ApiResponse.ok(stockValuationRunService.get(id));
    }

    @PostMapping("/runs")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create stock valuation run")
    public ApiResponse<StockValuationRunResponse> createValuationRun(
        @Valid @RequestBody StockValuationRunCreateRequest request
    ) {
        return ApiResponse.created(stockValuationRunService.create(request));
    }

    @PutMapping("/runs/{id}")
    @Operation(summary = "Update draft stock valuation run")
    public ApiResponse<StockValuationRunResponse> updateValuationRun(
        @PathVariable UUID id,
        @Valid @RequestBody StockValuationRunUpdateRequest request
    ) {
        return ApiResponse.ok(stockValuationRunService.update(id, request));
    }

    @DeleteMapping("/runs/{id}")
    @Operation(summary = "Soft-delete stock valuation run")
    public ApiResponse<Void> deleteValuationRun(@PathVariable UUID id) {
        stockValuationRunService.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/runs/{id}/restore")
    @Operation(summary = "Restore stock valuation run")
    public ApiResponse<StockValuationRunResponse> restoreValuationRun(@PathVariable UUID id) {
        return ApiResponse.ok(stockValuationRunService.restore(id));
    }

    @PostMapping("/runs/{id}/start")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Start stock valuation run calculation")
    public ApiResponse<StockValuationRunResponse> startValuationRun(@PathVariable UUID id) {
        return ApiResponse.ok(stockValuationRunService.start(id));
    }

    @GetMapping("/runs/{id}/items")
    @Operation(summary = "List stock valuation run result items")
    public ApiResponse<PageResponse<StockValuationRunItemResponse>> valuationRunItems(
        @PathVariable UUID id,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(stockValuationRunService.items(
            id, search, warehouseId, status,
            PaginationUtils.pageRequest(page, size, Sort.by("sequenceNo").ascending()))));
    }

    @GetMapping("/fifo-cost")
    public ApiResponse<BigDecimal> fifo(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateFifoCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/actual-cost")
    public ApiResponse<BigDecimal> actualCost(
        @RequestParam UUID materialId,
        @RequestParam List<UUID> warehouseId,
        @RequestParam(required = false) UUID stockBatchId,
        @RequestParam BigDecimal quantity
    ) {
        return ApiResponse.ok(valuationService.calculateActualCost(materialId, warehouseId, stockBatchId, quantity));
    }

    @GetMapping("/lifo-cost")
    public ApiResponse<BigDecimal> lifo(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateLifoCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/weighted-average-cost")
    public ApiResponse<BigDecimal> weightedAverage(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateWeightedAverageCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/moving-average-cost")
    public ApiResponse<BigDecimal> movingAverage(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateMovingAverageCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/market-price-cost")
    public ApiResponse<BigDecimal> marketPrice(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateMarketPriceCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/lower-of-cost-or-market-cost")
    public ApiResponse<BigDecimal> lowerOfCostOrMarket(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateLowerOfCostOrMarketCost(materialId, warehouseId, quantity));
    }

    @GetMapping("/net-realizable-value-cost")
    public ApiResponse<BigDecimal> netRealizableValue(@RequestParam UUID materialId, @RequestParam List<UUID> warehouseId, @RequestParam BigDecimal quantity) {
        return ApiResponse.ok(valuationService.calculateNetRealizableValueCost(materialId, warehouseId, quantity));
    }

    @PostMapping("/{materialId}/{warehouseId}/recalculate/{method}")
    public ApiResponse<StockValuationResponse> recalculate(@PathVariable UUID materialId, @PathVariable UUID warehouseId, @PathVariable ValuationMethod method) {
        return ApiResponse.ok(mapper.toResponse(valuationService.recalculate(materialId, warehouseId, method)));
    }

}
