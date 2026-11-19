package com.enterprise.mm.controller;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.TransferExceptionDtos.*;
import com.enterprise.mm.service.transfer.TransferExceptionService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/transfers") @RequiredArgsConstructor
@PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_HEAD','WAREHOUSE_OPERATOR','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER')")
public class TransferExceptionController {
    private final TransferExceptionService service;
    @GetMapping("/{id}/exceptions") public ApiResponse<List<CaseResponse>> list(@PathVariable UUID id){return ApiResponse.ok(service.list(id));}
    @GetMapping("/exceptions/{id}") public ApiResponse<CaseResponse> get(@PathVariable UUID id){return ApiResponse.ok(service.get(id));}
    @PostMapping("/{id}/exceptions") public ApiResponse<CaseResponse> report(@PathVariable UUID id,@RequestBody Report request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.report(id,request,key));}
    @PostMapping("/exceptions/{id}/review") public ApiResponse<CaseResponse> review(@PathVariable UUID id,@RequestBody Review request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.review(id,request,key));}
    @PostMapping("/exceptions/{id}/evidence") public ApiResponse<CaseResponse> evidence(@PathVariable UUID id,@RequestBody Report request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.evidence(id,request,key));}
    @PostMapping("/exceptions/{id}/submit") public ApiResponse<CaseResponse> submit(@PathVariable UUID id,@RequestBody Post request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.submit(id,request,key));}
    @PostMapping("/exceptions/{id}/post") public ApiResponse<CaseResponse> post(@PathVariable UUID id,@RequestBody Post request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.post(id,request,key));}
    @PostMapping("/exceptions/{id}/dispatch-return") public ApiResponse<CaseResponse> dispatchReturn(@PathVariable UUID id,@RequestBody Post request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.dispatchReturn(id,request,key));}
    @PostMapping("/exceptions/{id}/confirm-return") public ApiResponse<CaseResponse> confirmReturn(@PathVariable UUID id,@RequestBody ReturnReceipt request,@RequestHeader("Idempotency-Key") String key){return ApiResponse.ok(service.confirmReturn(id,request,key));}
}
