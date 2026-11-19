package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.MrpDtos.MrpRunResultDto;
import com.enterprise.mm.payload.MrpDtos.MrpShortageDto;
import com.enterprise.mm.service.MrpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/mrp")
@RequiredArgsConstructor
public class MrpController {
    private final MrpService mrpService;

    @GetMapping
    public ApiResponse<PageResponse<MrpShortageDto>> list(
        @RequestParam(required = false) String search,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(mrpService.calculateShortages(search)));
    }

    @GetMapping("/shortages")
    public ApiResponse<PageResponse<MrpShortageDto>> shortages(@RequestParam(required = false) String search) {
        return ApiResponse.ok(PageResponse.from(mrpService.calculateShortages(search)));
    }

    @PostMapping("/run")
    public ApiResponse<MrpRunResultDto> run() {
        return ApiResponse.ok(mrpService.runAndCreatePurchaseRequests());
    }
}
