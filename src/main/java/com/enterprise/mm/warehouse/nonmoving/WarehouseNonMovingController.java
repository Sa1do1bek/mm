package com.enterprise.mm.warehouse.nonmoving;

import com.enterprise.mm.payload.ApiResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.enterprise.mm.warehouse.nonmoving.WarehouseNonMovingDtos.*;

@RestController
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
public class WarehouseNonMovingController {
    private final WarehouseNonMovingService service;

    @GetMapping("/api/warehouse/non-moving/preferences/me")
    public ApiResponse<Preference> preference() {
        return ApiResponse.ok(service.preference());
    }

    @PutMapping("/api/warehouse/non-moving/preferences/me")
    public ApiResponse<Preference> save(@Valid @RequestBody PreferenceRequest request) {
        return ApiResponse.ok(service.savePreference(request));
    }

    @GetMapping("/api/warehouses/{id}/non-moving-candidates")
    public ApiResponse<Candidates> candidates(@PathVariable UUID id,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(defaultValue = "false") boolean includeUnknownHistory,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "25") int size,
        @RequestParam(defaultValue = "LAST_MOVEMENT_DATE") SortBy sortBy,
        @RequestParam(defaultValue = "ASC") org.springframework.data.domain.Sort.Direction direction,
        @RequestParam(required = false) String language) {
        return ApiResponse.ok(service.candidates(id,
            new Filter(search, materialGroupId, includeUnknownHistory, page, size, sortBy, direction), language));
    }

    @GetMapping("/api/warehouses/{id}/non-moving-candidates/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) UUID materialGroupId,
        @RequestParam(defaultValue = "false") boolean includeUnknownHistory,
        @RequestParam(defaultValue = "LAST_MOVEMENT_DATE") SortBy sortBy,
        @RequestParam(defaultValue = "ASC") org.springframework.data.domain.Sort.Direction direction,
        @RequestParam(required = false) String language) {
        String resolved = WarehouseNonMovingService.exportLanguage(language);
        byte[] bytes = service.export(id,
            new Filter(search, materialGroupId, includeUnknownHistory, 0, 200, sortBy, direction), resolved);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("non-moving-" + resolved + "-" + LocalDate.now() + ".xlsx", StandardCharsets.UTF_8).build().toString())
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            .header(HttpHeaders.CONTENT_LANGUAGE, resolved)
            .body(bytes);
    }

}
