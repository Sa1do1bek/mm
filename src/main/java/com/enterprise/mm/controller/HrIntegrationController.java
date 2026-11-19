package com.enterprise.mm.controller;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.RequestParam;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.HrDtos.DepartmentTreeNodeDto;
import com.enterprise.mm.payload.HrDtos.HrSyncRequest;
import com.enterprise.mm.payload.HrDtos.HrSyncResultDto;
import com.enterprise.mm.service.HrIntegrationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/hr")
@RequiredArgsConstructor
public class HrIntegrationController {
    private final HrIntegrationService service;

    @GetMapping("/org-tree")
    public ApiResponse<PageResponse<DepartmentTreeNodeDto>> orgTree(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.orgTree(search)));
    }

    @PostMapping("/sync")
    public ApiResponse<HrSyncResultDto> sync(@Valid @RequestBody HrSyncRequest request) {
        return ApiResponse.ok(service.sync(request));
    }

    @PostMapping("/request-sync")
    public ApiResponse<HrSyncResultDto> requestSync() {
        return ApiResponse.ok(service.requestSync());
    }
}
