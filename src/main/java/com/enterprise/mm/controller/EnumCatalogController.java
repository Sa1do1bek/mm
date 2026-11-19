package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.EnumOptionResponse;
import com.enterprise.mm.service.EnumCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequiredArgsConstructor
@Tag(name = "Enums", description = "Localized enum catalogs for frontend reference data")
public class EnumCatalogController {
    private static final CacheControl ENUM_CACHE = CacheControl.maxAge(Duration.ofHours(1));

    private final EnumCatalogService service;

    @GetMapping("/api/enums")
    @Operation(summary = "List all frontend enum catalogs")
    public ResponseEntity<ApiResponse<Map<String, Map<String, List<EnumOptionResponse>>>>> allEnums() {
        return ResponseEntity.ok()
            .cacheControl(ENUM_CACHE)
            .body(ApiResponse.ok(service.allEnums()));
    }


    @GetMapping("/api/{moduleKey}/enums")
    @Operation(summary = "List localized enum catalogs for one module")
    public ResponseEntity<ApiResponse<Map<String, List<EnumOptionResponse>>>> moduleEnums(
        @PathVariable String moduleKey
    ) {
        try {
            return ResponseEntity.ok()
                .cacheControl(ENUM_CACHE)
                .body(ApiResponse.ok(service.moduleEnums(moduleKey)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }

    @GetMapping("/api/{moduleKey}/enums/{enumType}")
    @Operation(summary = "List one localized enum catalog")
    public ResponseEntity<ApiResponse<List<EnumOptionResponse>>> enumValues(
        @PathVariable String moduleKey,
        @PathVariable String enumType
    ) {
        try {
            return ResponseEntity.ok()
                .cacheControl(ENUM_CACHE)
                .body(ApiResponse.ok(service.enumValues(moduleKey, enumType)));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        }
    }
}
