package com.enterprise.mm.controller;
import lombok.RequiredArgsConstructor;
import java.util.List;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.StockBalanceResponse;
import com.enterprise.mm.payload.StockBalanceUpdateRequest;
import com.enterprise.mm.payload.StockBalanceCreateRequest;
import com.enterprise.mm.payload.WarehouseMaterialBalanceResponse;
import com.enterprise.mm.payload.StockBalanceSearchRequest;
import com.enterprise.mm.service.StockBalanceService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
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
@RequestMapping("/api/stock/balances")
@RequiredArgsConstructor
public class StockBalanceController {
    private final StockBalanceService service;

    private final com.enterprise.mm.service.StockBalanceExcelExport excelExport;

    @GetMapping
    public ApiResponse<PageResponse<StockBalanceResponse>> search(
        @org.springframework.web.bind.annotation.ModelAttribute com.enterprise.mm.payload.StockBalanceHttpFilter request,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.search(request.toRequest(),
            PaginationUtils.pageRequest(page, size, Sort.unsorted()))));
    }

    @GetMapping("/export")
    public org.springframework.http.ResponseEntity<org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody> export(
        @org.springframework.web.bind.annotation.ModelAttribute com.enterprise.mm.payload.StockBalanceHttpFilter request,
        @RequestParam(required = false) java.util.List<String> columns,
        @RequestParam(required = false) String language
    ) {
        var criteria = request.toRequest();
        excelExport.validate(criteria, columns, language);
        var context = com.enterprise.mm.service.StockBalanceExcelExport.RequestContext.capture();
        return org.springframework.http.ResponseEntity.ok()
            .header("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .header("Content-Disposition", "attachment; filename=stock-balances.xlsx")
            .header("Cache-Control", "no-store")
            .body(output -> context.run(() -> excelExport.write(criteria, columns, language, output)));
    }

    @GetMapping("/{id}")
    public ApiResponse<StockBalanceResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/materials")
    public ApiResponse<List<WarehouseMaterialBalanceResponse>> getWarehouseMaterials(
        @RequestParam UUID warehouseId,
        @RequestParam List<UUID> materialIds
    ) {
        return ApiResponse.ok(service.getWarehouseMaterialBalances(warehouseId, materialIds));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockBalanceResponse> create(@Valid @RequestBody StockBalanceCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<StockBalanceResponse> update(@PathVariable UUID id, @Valid @RequestBody StockBalanceUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<StockBalanceResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

