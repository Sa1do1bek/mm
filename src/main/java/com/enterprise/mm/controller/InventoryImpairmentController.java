package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.StockValuationDocumentCreateRequest;
import com.enterprise.mm.payload.StockValuationDocumentResponse;
import com.enterprise.mm.service.StockValuationDocumentService;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/inventory-impairments", "/api/v1/inventory-impairments"})
@RequiredArgsConstructor
public class InventoryImpairmentController {
    private final StockValuationDocumentService valuationDocumentService;

    @PostMapping("/detect")
    public ApiResponse<StockValuationDocumentResponse> detect(@Valid @RequestBody StockValuationDocumentCreateRequest request) {
        request.setDocumentType("IMPAIRMENT_RESERVE");
        StockValuationDocumentResponse created = valuationDocumentService.create(request);
        return ApiResponse.created(valuationDocumentService.propose(created.getId()));
    }

    @PostMapping("/{id}/propose")
    public ApiResponse<StockValuationDocumentResponse> propose(@PathVariable UUID id) {
        return ApiResponse.ok(valuationDocumentService.propose(id));
    }

    @PostMapping("/{id}/review")
    public ApiResponse<StockValuationDocumentResponse> review(@PathVariable UUID id) {
        return ApiResponse.ok(valuationDocumentService.review(id));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<StockValuationDocumentResponse> approve(@PathVariable UUID id) {
        return ApiResponse.ok(valuationDocumentService.approve(id));
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<StockValuationDocumentResponse> reject(@PathVariable UUID id) {
        return ApiResponse.ok(valuationDocumentService.reject(id));
    }

    @PostMapping("/{id}/post")
    public ApiResponse<StockValuationDocumentResponse> post(@PathVariable UUID id) {
        return ApiResponse.ok(valuationDocumentService.post(id));
    }
}
