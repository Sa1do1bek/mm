package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ImportInsurancePolicyResponse;
import com.enterprise.mm.payload.ImportInsurancePolicyUpdateRequest;
import com.enterprise.mm.payload.ImportInsurancePolicyCreateRequest;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.ImportInsurancePolicySearchRequest;
import com.enterprise.mm.service.ImportInsurancePolicyService;
import jakarta.validation.Valid;
import java.util.UUID;
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
@RequestMapping("/api/import-insurance-policies")
@RequiredArgsConstructor
public class ImportInsurancePolicyController {
    private final ImportInsurancePolicyService service;

    @GetMapping
    public ApiResponse<PageResponse<ImportInsurancePolicyResponse>> search(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) java.util.List<String> status,
        @RequestParam(required = false) java.time.LocalDate dateFrom,
        @RequestParam(required = false) java.time.LocalDate dateTo,
        @RequestParam(defaultValue = "false") boolean includeDeleted,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        ImportInsurancePolicySearchRequest request = new ImportInsurancePolicySearchRequest(search, status, dateFrom, dateTo, includeDeleted);
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable)));
    }

    @GetMapping("/{id}")
    public ApiResponse<ImportInsurancePolicyResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<ImportInsurancePolicyResponse> create(@Valid @RequestBody ImportInsurancePolicyCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<ImportInsurancePolicyResponse> update(@PathVariable UUID id, @Valid @RequestBody ImportInsurancePolicyUpdateRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<ImportInsurancePolicyResponse> restore(@PathVariable UUID id) {
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/activate")
    public ApiResponse<ImportInsurancePolicyResponse> activate(@PathVariable UUID id) {
        return ApiResponse.ok(service.activate(id));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<ImportInsurancePolicyResponse> claim(@PathVariable UUID id) {
        return ApiResponse.ok(service.registerClaim(id));
    }

    @PostMapping("/{id}/close")
    public ApiResponse<ImportInsurancePolicyResponse> close(@PathVariable UUID id) {
        return ApiResponse.ok(service.close(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<ImportInsurancePolicyResponse> cancel(@PathVariable UUID id) {
        return ApiResponse.ok(service.cancel(id));
    }

}

