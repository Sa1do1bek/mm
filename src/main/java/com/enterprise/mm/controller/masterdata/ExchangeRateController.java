package com.enterprise.mm.controller.masterdata;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.ExchangeRateRequest;
import com.enterprise.mm.payload.ExchangeRateResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.service.ExchangeRateService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import com.enterprise.mm.common.PaginationUtils;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/exchange-rates")
@RequiredArgsConstructor
public class ExchangeRateController {
    private final ExchangeRateService exchangeRateService;

    @GetMapping
    public ApiResponse<PageResponse<ExchangeRateResponse>> history(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) UUID currencyId,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String query,
        @RequestParam(required = false) String search
    ) {
        Pageable pageable = PaginationUtils.pageRequest(
            page,
            size,
            Sort.by("createdAt").descending()
        );
        String effectiveQuery = query == null || query.isBlank() ? search : query;
        return ApiResponse.ok(PageResponse.from(exchangeRateService.history(pageable, currencyId, dateFrom, dateTo, effectiveQuery)));
    }

    @GetMapping("/lookup")
    public ApiResponse<BigDecimal> lookup(
        @RequestParam(required = false) UUID currencyId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        return ApiResponse.ok(exchangeRateService.rateOn(currencyId, date));
    }

    @PostMapping
    public ApiResponse<ExchangeRateResponse> upsert(@Valid @RequestBody ExchangeRateRequest request) {
        return ApiResponse.ok(exchangeRateService.upsert(request));
    }
}

