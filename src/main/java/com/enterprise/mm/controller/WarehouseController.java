package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.time.LocalDate;
import java.util.UUID;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.BalanceRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.LocationRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.MovementRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.NonMovingRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.ReservationRow;
import com.enterprise.mm.payload.WarehouseCommandViewDtos.WarehouseCommandViewResponse;
import com.enterprise.mm.payload.WarehouseResponse;
import com.enterprise.mm.payload.WarehouseUpdateRequest;
import com.enterprise.mm.payload.WarehouseCreateRequest;
import com.enterprise.mm.payload.WarehouseSearchRequest;
import com.enterprise.mm.service.WarehouseCommandViewService;
import com.enterprise.mm.service.WarehouseService;
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
@RequestMapping("/api/warehouses")
@RequiredArgsConstructor
public class WarehouseController {
    private final WarehouseService service;
    private final WarehouseCommandViewService commandViewService;

    @GetMapping
    public ApiResponse<PageResponse<WarehouseResponse>> search(
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
        @RequestParam(required = false) String address,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        WarehouseSearchRequest request = new WarehouseSearchRequest(
            search, status, dateFrom, dateTo, includeDeleted, id, code, name, description, deleted, createdBy, updatedBy, version, address
        );
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<WarehouseResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/{id}/command-view")
    public ApiResponse<WarehouseCommandViewResponse> commandViewSummary(@PathVariable UUID id) {
        return ApiResponse.ok(commandViewService.summary(id));
    }

    @GetMapping("/{warehouseId}/command-view/balances")
    public ApiResponse<PageResponse<BalanceRow>> commandViewBalances(
        @PathVariable UUID warehouseId,
        @org.springframework.web.bind.annotation.ModelAttribute com.enterprise.mm.payload.StockBalanceHttpFilter request,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        return ApiResponse.ok(PageResponse.from(commandViewService.balances(warehouseId,request.toRequest(),request.toOptions(),
            PaginationUtils.pageRequest(page,size,Sort.unsorted()))));
    }

    @GetMapping("/{id}/command-view/locations")
    public ApiResponse<PageResponse<LocationRow>> commandViewLocations(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(commandViewService.locations(id, pageable)));
    }

    @GetMapping("/{id}/command-view/movements")
    public ApiResponse<PageResponse<MovementRow>> commandViewMovements(
        @PathVariable UUID id,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(
            commandViewService.movements(id, dateFrom, dateTo, pageable)));
    }

    @GetMapping("/{id}/command-view/reservations")
    public ApiResponse<PageResponse<ReservationRow>> commandViewReservations(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(commandViewService.reservations(id, pageable)));
    }

    @GetMapping("/{id}/command-view/non-moving")
    public ApiResponse<PageResponse<NonMovingRow>> commandViewNonMoving(
        @PathVariable UUID id,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(page, size, Sort.unsorted());
        return ApiResponse.ok(PageResponse.from(commandViewService.nonMoving(id, pageable)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<WarehouseResponse> create(@Valid @RequestBody WarehouseCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<WarehouseResponse> update(@PathVariable UUID id, @Valid @RequestBody WarehouseUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<WarehouseResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

}

