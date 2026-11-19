package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.TenderCommissionRequest;
import com.enterprise.mm.payload.TenderCommissionResponse;
import com.enterprise.mm.service.TenderCommissionService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tender/commissions")
@RequiredArgsConstructor
public class TenderCommissionController {
    private final TenderCommissionService service;

    @GetMapping("/{id}")
    public ApiResponse<TenderCommissionResponse> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }

    @GetMapping("/rfq/{rfqId}")
    public ApiResponse<List<TenderCommissionResponse>> byRfq(@PathVariable UUID rfqId) {
        return ApiResponse.ok(service.byRfq(rfqId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<TenderCommissionResponse> create(@Valid @RequestBody TenderCommissionRequest request) {
        return ApiResponse.created(service.create(request));
    }

    @PutMapping("/{id}")
    public ApiResponse<TenderCommissionResponse> update(@PathVariable UUID id, @Valid @RequestBody TenderCommissionRequest request) {
        return ApiResponse.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable UUID id) {
        service.softDelete(id);
        return ApiResponse.message("soft-deleted");
    }
}
