package com.enterprise.mm.controller;

import lombok.RequiredArgsConstructor;
import java.util.List;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.BankResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.repository.BankRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Справочник банков Узбекистана (reference data).
 * Данные хранятся в таблице banks (миграция V2__bank_reference.sql).
 */
@RestController
@RequestMapping("/api/banks")
@RequiredArgsConstructor
public class BankReferenceController {

    private final BankRepository bankRepository;

    @GetMapping
    public ApiResponse<PageResponse<BankResponse>> list() {
        List<BankResponse> banks = bankRepository.findAllByOrderBySortOrderAsc().stream()
            .map(bank -> new BankResponse(bank.getId(), bank.getName(), bank.getMfo(), bank.getSwift(), bank.getBankType(), bank.getCity()))
            .toList();
        return ApiResponse.ok(new PageResponse<>(banks, 0, banks.size(), banks.size(), 1));
    }
}
