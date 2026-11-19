package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.service.OpeningStockService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/opening-stock") @RequiredArgsConstructor
public class OpeningStockController {
    private final OpeningStockService service;
    @PostMapping
    public ApiResponse<OpeningStockService.Result> post(@RequestBody OpeningStockService.Command command) {
        return ApiResponse.ok(service.post(command));
    }
}
