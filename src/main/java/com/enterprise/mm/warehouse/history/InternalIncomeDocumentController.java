package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/income-documents/1c")
@RequiredArgsConstructor
public class InternalIncomeDocumentController {
    private final InternalIncomeDocumentService service;

    @GetMapping
    public ApiResponse<PageResponse<InternalIncomeDocumentService.Document>> documents(
        @RequestParam UUID warehouseId, @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(service.documents(warehouseId, search, page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<InternalIncomeDocumentService.Detail> detail(@PathVariable UUID id, @RequestParam UUID warehouseId) {
        return ApiResponse.ok(service.detail(id, warehouseId));
    }
}
