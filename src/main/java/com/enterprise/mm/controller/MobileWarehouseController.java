package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileSignatureRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseOperationDto;
import com.enterprise.mm.payload.MobileWarehouseDtos.MobileWarehouseRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncRequest;
import com.enterprise.mm.payload.MobileWarehouseDtos.OfflineSyncResponse;
import com.enterprise.mm.service.MobileWarehouseService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mobile/warehouse")
@RequiredArgsConstructor
public class MobileWarehouseController {
    private final MobileWarehouseService service;

    @PostMapping("/receive")
    public ApiResponse<MobileWarehouseOperationDto> receive(@RequestBody MobileWarehouseRequest request) {
        return ApiResponse.ok(service.receive(request));
    }

    @PostMapping("/put-away")
    public ApiResponse<MobileWarehouseOperationDto> putAway(@RequestBody MobileWarehouseRequest request) {
        return ApiResponse.ok(service.putAway(request));
    }

    @PostMapping("/pick")
    public ApiResponse<MobileWarehouseOperationDto> pick(@RequestBody MobileWarehouseRequest request) {
        return ApiResponse.ok(service.pick(request));
    }

    @PostMapping("/ship")
    public ApiResponse<MobileWarehouseOperationDto> ship(@RequestBody MobileWarehouseRequest request) {
        return ApiResponse.ok(service.ship(request));
    }

    @PostMapping("/offline/sync")
    public ApiResponse<OfflineSyncResponse> syncOffline(@RequestBody OfflineSyncRequest request) {
        return ApiResponse.ok(service.syncOffline(request));
    }

    @PostMapping("/{id}/sign")
    public ApiResponse<MobileWarehouseOperationDto> sign(@PathVariable UUID id, @RequestBody MobileSignatureRequest request) {
        return ApiResponse.ok(service.sign(id, request));
    }
}
