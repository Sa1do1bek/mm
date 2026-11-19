package com.enterprise.mm.warehouse.packaging;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.ChangeStateRequest;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.ConfirmRequest;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.CreateReturnRequest;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.DispatchRequest;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.LotView;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.ReturnView;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.VersionRequest;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;
import java.util.Set;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.LotFilters;
import com.enterprise.mm.warehouse.packaging.ReturnablePackagingDtos.ReturnFilters;
import com.enterprise.mm.error.BadRequestException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequiredArgsConstructor
@RequestMapping("/api/warehouse/returnable-packaging")
public class ReturnablePackagingController {
    private final ReturnablePackagingService service;

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<PageResponse<LotView>> register(@RequestParam(required = false) String search,
        @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID contentMaterialId, @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String ownership, @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) String state, @RequestParam(required = false) LocalDate dueFrom,
        @RequestParam(required = false) LocalDate dueTo, @RequestParam(defaultValue = "false") boolean onlyOnHand,
        @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        if (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))
            throw new BadRequestException("dueFrom must not follow dueTo");
        Sort sort = PaginationUtils.sort(sortBy, sortDir,
            Set.of("createdAt", "code", "dueDate", "receivedQuantity", "emptyQuantity", "dispatchedQuantity"),
            Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.ok(PageResponse.from(service.register(new LotFilters(search, supplierId, materialId,
            contentMaterialId, warehouseId, ownership, sourceType, state, dueFrom, dueTo, onlyOnHand),
            PaginationUtils.pageRequest(page, size, sort))));
    }

    @GetMapping("/returns")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<PageResponse<ReturnablePackagingDtos.ReturnRegisterView>> returnRegister(
        @RequestParam(required = false) String search, @RequestParam(required = false) UUID supplierId,
        @RequestParam(required = false) UUID materialId, @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String ownership, @RequestParam(required = false) String status,
        @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortDir,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir,
            Set.of("createdAt", "code", "status", "quantity", "confirmedQuantity", "recalledQuantity", "dispatchedAt"),
            Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by(Sort.Direction.DESC, "id"));
        return ApiResponse.ok(PageResponse.from(service.returnRegister(
            new ReturnFilters(search, supplierId, materialId, warehouseId, ownership, status),
            PaginationUtils.pageRequest(page, size, sort))));
    }

    @GetMapping("/export")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ResponseEntity<byte[]> exportLots(@RequestParam(required = false) String search,
        @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID contentMaterialId, @RequestParam(required = false) UUID warehouseId,
        @RequestParam(required = false) String ownership, @RequestParam(required = false) String sourceType,
        @RequestParam(required = false) String state, @RequestParam(required = false) LocalDate dueFrom,
        @RequestParam(required = false) LocalDate dueTo, @RequestParam(defaultValue = "false") boolean onlyOnHand,
        @RequestParam(required = false) String sortBy, @RequestParam(required = false) String sortDir) {
        if (dueFrom != null && dueTo != null && dueFrom.isAfter(dueTo))
            throw new BadRequestException("dueFrom must not follow dueTo");
        Sort sort = PaginationUtils.sort(sortBy, sortDir,
            Set.of("createdAt", "code", "dueDate", "receivedQuantity", "emptyQuantity", "dispatchedQuantity"),
            Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by(Sort.Direction.DESC, "id"));
        String csv = service.exportLots(new LotFilters(search, supplierId, materialId, contentMaterialId,
            warehouseId, ownership, sourceType, state, dueFrom, dueTo, onlyOnHand), sort);
        return csv(csv, "returnable-packaging.csv");
    }

    @GetMapping("/returns/export")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ResponseEntity<byte[]> exportReturns(@RequestParam(required = false) String search,
        @RequestParam(required = false) UUID supplierId, @RequestParam(required = false) UUID materialId,
        @RequestParam(required = false) UUID warehouseId, @RequestParam(required = false) String ownership,
        @RequestParam(required = false) String status, @RequestParam(required = false) String sortBy,
        @RequestParam(required = false) String sortDir) {
        Sort sort = PaginationUtils.sort(sortBy, sortDir,
            Set.of("createdAt", "code", "status", "quantity", "confirmedQuantity", "recalledQuantity", "dispatchedAt"),
            Sort.by(Sort.Direction.DESC, "createdAt")).and(Sort.by(Sort.Direction.DESC, "id"));
        return csv(service.exportReturns(new ReturnFilters(search, supplierId, materialId, warehouseId,
            ownership, status), sort), "returnable-packaging-returns.csv");
    }

    private static ResponseEntity<byte[]> csv(String content, String filename) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
            .body(("\uFEFF" + content).getBytes(StandardCharsets.UTF_8));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<LotView> get(@PathVariable UUID id) { return ApiResponse.ok(service.get(id)); }

    @PostMapping("/{id}/state")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR')")
    public ApiResponse<LotView> changeState(@PathVariable UUID id, @RequestBody ChangeStateRequest request) {
        return ApiResponse.ok(service.changeState(id, request));
    }

    @PostMapping("/returns")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
    public ApiResponse<ReturnView> createReturn(@RequestBody CreateReturnRequest request) {
        return ApiResponse.created(service.createReturn(request));
    }

    @GetMapping("/{id}/returns")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<List<ReturnView>> returns(@PathVariable UUID id) { return ApiResponse.ok(service.returns(id)); }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
    public ApiResponse<List<ReturnablePackagingDtos.EventView>> events(@PathVariable UUID id) {
        return ApiResponse.ok(service.events(id));
    }

    @PostMapping("/returns/{id}/approve")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<ReturnView> approve(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.approve(id, request));
    }

    @PostMapping("/returns/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','WAREHOUSE_OPERATOR')")
    public ApiResponse<ReturnView> dispatch(@PathVariable UUID id, @RequestBody DispatchRequest request) {
        return ApiResponse.ok(service.dispatch(id, request));
    }

    @PostMapping("/returns/{id}/confirm")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<ReturnView> confirm(@PathVariable UUID id, @RequestBody ConfirmRequest request) {
        return ApiResponse.ok(service.confirm(id, request));
    }

    @PostMapping("/returns/{id}/cancel")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<ReturnView> cancel(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return ApiResponse.ok(service.cancel(id, request));
    }

    @PostMapping("/returns/{id}/recall")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD')")
    public ApiResponse<ReturnView> recall(@PathVariable UUID id, @RequestBody ReturnablePackagingDtos.RecallRequest request) {
        return ApiResponse.ok(service.recall(id, request));
    }
}
