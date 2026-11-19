package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.IssueAccountingReviewService;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse/issue-account-reviews")
@PreAuthorize("hasAnyRole('ADMIN','FINANCE_CONTROLLER','ACCOUNTANT')")
public class IssueAccountingReviewController {
    private final IssueAccountingReviewService service;
    public IssueAccountingReviewController(IssueAccountingReviewService service) { this.service = service; }

    public record ResolveRequest(String stockAccountCode, String counterAccountCode, String evidence) {}

    @GetMapping
    public ApiResponse<IssueAccountingReviewService.Page> list(
            @RequestParam(defaultValue = "PENDING_REVIEW") String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "50") int size) {
        return ApiResponse.ok(service.list(status, page, size));
    }

    @PostMapping("/{id}/resolve")
    public ApiResponse<Void> resolve(@PathVariable UUID id, @RequestBody ResolveRequest request) {
        service.resolve(id, request.stockAccountCode(), request.counterAccountCode(), request.evidence());
        return ApiResponse.ok(null);
    }
}
