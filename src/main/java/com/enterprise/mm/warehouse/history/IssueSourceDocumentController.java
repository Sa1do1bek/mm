package com.enterprise.mm.warehouse.history;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/issue-documents/1c")
@RequiredArgsConstructor
public class IssueSourceDocumentController {
    private final IssueSourceDocumentService sources;
    private final IssueSourcePreparationService preparation;

    @GetMapping
    public ApiResponse<PageResponse<IssueSourceDocumentService.Document>> documents(@RequestParam UUID warehouseId,
        @RequestParam(defaultValue="review") String bucket, @RequestParam(required=false) String search,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.ok(sources.documents(warehouseId, bucket, search, page, size));
    }
    @GetMapping("/{id}")
    public ApiResponse<IssueSourceDocumentService.Detail> detail(@PathVariable UUID id, @RequestParam UUID warehouseId) {
        return ApiResponse.ok(sources.detail(id, warehouseId));
    }
    @PostMapping("/{id}/prepare")
    public ApiResponse<IssueSourcePreparationService.Prepared> prepare(@PathVariable UUID id,
        @RequestHeader("Idempotency-Key") String key, @Valid @RequestBody IssueSourcePreparationService.Request request) {
        return ApiResponse.ok(preparation.prepare(id, request, key));
    }
}
