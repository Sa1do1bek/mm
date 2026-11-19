package com.enterprise.mm.erp;

import com.enterprise.mm.payload.ApiResponse;
import java.util.UUID;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/unit-marks")
public class UnitMarkController {
    private final ErpExchangeService service;

    public UnitMarkController(ErpExchangeService service) {
        this.service = service;
    }

    @PostMapping
    public ApiResponse<java.util.Map<String, Object>> register(@RequestBody MarkRequest request) {
        return ApiResponse.ok(service.registerUnitMark(request.materialId(), request.goodsReceiptId(), request.markCode()));
    }

    public record MarkRequest(UUID materialId, UUID goodsReceiptId, String markCode) {
    }
}
