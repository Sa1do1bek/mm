package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationTemplateDto;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationValidationResponse;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationValidationRowDto;
import com.enterprise.mm.service.DataMigrationService;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/migration")
@RequiredArgsConstructor
public class DataMigrationController {
    private final DataMigrationService service;

    @GetMapping
    public ApiResponse<PageResponse<MigrationTemplateDto>> list(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.templates(search)));
    }

    @GetMapping("/templates")
    public ApiResponse<PageResponse<MigrationTemplateDto>> templates(@RequestParam(required = false) String search) {
        return ApiResponse.ok(PageResponse.from(service.templates(search)));
    }

    @GetMapping("/templates/{templateCode}.xlsx")
    public ResponseEntity<byte[]> template(@PathVariable String templateCode) {
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=" + templateCode + ".xlsx")
            .contentType(MediaType.parseMediaType(service.templateContentType()))
            .body(service.templateWorkbook(templateCode));
    }

    @PostMapping("/validate/{templateCode}")
    public ApiResponse<MigrationValidationResponse> validate(@PathVariable String templateCode, @RequestParam MultipartFile file) {
        return ApiResponse.ok(service.validateAndStage(templateCode, file));
    }

    @GetMapping("/runs/{runId}/rows")
    public ApiResponse<PageResponse<MigrationValidationRowDto>> rows(@PathVariable UUID runId) {
        return ApiResponse.ok(PageResponse.from(service.rows(runId)));
    }
}
