package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ManualUnmatchedIssueDtos;
import com.enterprise.mm.service.ManualUnmatchedIssueService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/workspaces/warehouse-execution/manual/stock-issue/unmatched")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER')")
public class ManualUnmatchedIssueController {
    private final ManualUnmatchedIssueService service;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<ManualUnmatchedIssueDtos.Response> record(
        @Valid @RequestPart("request") ManualUnmatchedIssueDtos.Create request,
        @RequestPart(value = "files", required = false) List<MultipartFile> files,
        @RequestHeader("Idempotency-Key") String key) {
        return ApiResponse.created(service.record(request, files, key));
    }

    @GetMapping
    public ApiResponse<List<ManualUnmatchedIssueDtos.Response>> list(@RequestParam UUID entryContextWarehouseId) {
        return ApiResponse.ok(service.list(entryContextWarehouseId));
    }

    @GetMapping("/{id}")
    public ApiResponse<ManualUnmatchedIssueDtos.Response> get(@PathVariable UUID id) {
        return ApiResponse.ok(service.get(id));
    }
}
