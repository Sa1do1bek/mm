package com.enterprise.mm.controller;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.CountryCodeProjection;
import com.enterprise.mm.security.CountryCodeService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/countryCode")
@RequiredArgsConstructor
public class CountryCodeController {

    private final CountryCodeService countryCodeService;

    @GetMapping("/getAllCountry")
    public ApiResponse<Page<CountryCodeProjection>> getAll(
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false, defaultValue = "false" ) boolean all
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        if (all) {
            return ApiResponse.ok(countryCodeService.getAll(search, page, size));
        }
        return ApiResponse.ok(countryCodeService.getAllAndDeletedFalse(search, page, size));
    }

}
