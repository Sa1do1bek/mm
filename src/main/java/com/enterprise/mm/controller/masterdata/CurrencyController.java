package com.enterprise.mm.controller.masterdata;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CurrencyResponse;
import com.enterprise.mm.payload.CurrencyUpdateRequest;
import com.enterprise.mm.payload.CurrencyCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.CurrencySearchRequest;
import com.enterprise.mm.service.CurrencyService;
import jakarta.validation.Valid;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;

@RestController
@RequestMapping("/api/currencies")
@RequiredArgsConstructor
public class CurrencyController {
    private final CurrencyService service;

    @GetMapping
    public ApiResponse<PageResponse<CurrencyResponse>> search(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) List<String> status,
            @RequestParam(required = false) LocalDate dateFrom,
            @RequestParam(required = false) LocalDate dateTo,
            @RequestParam(defaultValue = "false") boolean includeDeleted,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        Set<String> allowedSortFields = Set.of(
                "name",
                "createdAt"
        );
        List<Sort.Order> orders = new ArrayList<>();
        if (sort != null && !sort.isBlank()) {

            String[] sortParts = sort.split(",");

            String field = sortParts[0].trim();

            if (!allowedSortFields.contains(field)) {
                throw new IllegalArgumentException(
                        "Invalid sort field: " + field
                );
            }
            Sort.Direction direction = sortParts.length > 1
                    ? Sort.Direction.fromString(sortParts[1].trim())
                    : Sort.Direction.ASC;

            orders.add(new Sort.Order(direction, field));
        }
        Sort sorting = orders.isEmpty()
                ? Sort.by(Sort.Direction.DESC, "createdAt")
                : Sort.by(orders);
        Pageable pageable = PaginationUtils.pageRequest(
                page,
                size,
                sorting
        );

        CurrencySearchRequest request =
                new CurrencySearchRequest(
                        search,
                        status,
                        dateFrom,
                        dateTo,
                        includeDeleted
                );

        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<CurrencyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CurrencyResponse> create(@Valid @RequestBody CurrencyCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<CurrencyResponse> update(@PathVariable UUID id, @Valid @RequestBody CurrencyUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<CurrencyResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }
}

