package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.warehouse.movements.MovementExportText;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.StockMovementCreateRequest;
import com.enterprise.mm.payload.StockMovementResponse;
import com.enterprise.mm.payload.StockMovementSearchRequest;
import com.enterprise.mm.payload.StockMovementUpdateRequest;
import com.enterprise.mm.service.StockMovementService;
import com.enterprise.mm.warehouse.movements.MovementFilterParameters;
import com.enterprise.mm.warehouse.movements.MovementQuerySupport;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.ModelAttribute;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
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
@RequestMapping("/api/stock/movements")
@RequiredArgsConstructor
public class StockMovementController {
    private final StockMovementService service;
    private final com.enterprise.mm.warehouse.history.ImportedStockMovementQuery imported;

    @GetMapping
    public ApiResponse<PageResponse<StockMovementResponse>> search(
        @ModelAttribute MovementFilterParameters filters,
        @RequestParam(required = false) String language,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam MultiValueMap<String, String> parameters
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            MovementQuerySupport.sort(parameters.get("sort"))
        );
        StockMovementSearchRequest request = filters.toRequest();
        return ApiResponse.ok(PageResponse.from(service.search(request, pageable, language)));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@ModelAttribute MovementFilterParameters filters,
        @RequestParam(required=false) String language,
        @RequestParam MultiValueMap<String,String> parameters) {
        StockMovementSearchRequest filter = filters.toRequest();
        String resolved = MovementExportText.language(language);
        byte[] bytes = service.exportMovements(filter,MovementQuerySupport.sort(parameters.get("sort")),resolved);
        String name = "movements-"+resolved+"-"+LocalDate.now()+".xlsx";
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(name,StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_LANGUAGE,resolved)
            .body(bytes);
    }

    @GetMapping("/{id}")
    public ApiResponse<StockMovementResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(imported.detail(id).orElseGet(()->service.get(id)));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<StockMovementResponse> create(@Valid @RequestBody StockMovementCreateRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<StockMovementResponse> update(@PathVariable UUID id, @Valid @RequestBody StockMovementUpdateRequest request) {
        imported.assertOperational(id);
        return ApiResponse.ok(service.update(id, request));
    }

    @PostMapping("/{id}/submit-approval")
    public ApiResponse<StockMovementResponse> submitForApproval(@PathVariable UUID id) {
        imported.assertOperational(id);
        return ApiResponse.ok(service.submitForApproval(id));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        imported.assertOperational(id);
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }

    @PostMapping("/{id}/restore")
    public ApiResponse<StockMovementResponse> restore(@PathVariable UUID id) {
        imported.assertOperational(id);
        return ApiResponse.ok(service.restore(id));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<StockMovementResponse> post(@PathVariable UUID id) {
        imported.assertOperational(id);
        return ApiResponse.ok(service.post(id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<StockMovementResponse> cancel(@PathVariable UUID id) {
        imported.assertOperational(id);
        return ApiResponse.ok(service.cancelPosted(id));
    }

}
