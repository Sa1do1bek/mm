package com.enterprise.mm.erp;

import com.enterprise.mm.payload.ApiResponse;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/erp-exchange")
public class ErpExchangeController {
    private final ErpExchangeService service;
    @org.springframework.beans.factory.annotation.Autowired private OneCQueryJobService jobs;
    @org.springframework.beans.factory.annotation.Autowired private OneCTransport transport;

    public ErpExchangeController(ErpExchangeService service) {
        this.service = service;
    }

    @GetMapping("/connection")
    public ApiResponse<ErpExchangeService.ConnectionSettings> connection() {
        return ApiResponse.ok(service.connection());
    }

    @PutMapping("/connection")
    public ApiResponse<ErpExchangeService.ConnectionSettings> saveConnection(@RequestBody ConnectionRequest request) {
        return ApiResponse.ok(service.saveConnection(request.enabled(), request.baseUrl(), request.username(),
            request.password(), request.scheduleCron()));
    }

    @GetMapping("/posting-config")
    public ApiResponse<ErpExchangeService.PostingSettings> postingConfig() {
        return ApiResponse.ok(service.postingConfig());
    }

    @PutMapping("/posting-config")
    public ApiResponse<ErpExchangeService.PostingSettings> savePostingConfig(@RequestBody PostingRequest request) {
        return ApiResponse.ok(service.savePostingConfig(request.arrivalMode(), request.accountCode(),
            request.subcontoCode(), request.warehouseCode()));
    }

    @GetMapping("/envelopes")
    public ApiResponse<List<java.util.Map<String, Object>>> envelopes() {
        return ApiResponse.ok(service.envelopes());
    }

    @PostMapping("/documents/{id}/confirm")
    public ApiResponse<java.util.Map<String, Object>> confirm(@PathVariable UUID id) {
        return ApiResponse.ok(service.confirm(id));
    }

    @PostMapping("/stock-facts/{id}/capture")
    @org.springframework.security.access.prepost.PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<java.util.Map<String, Object>> captureStockFact(@PathVariable UUID id) {
        return ApiResponse.ok(service.captureStockFact(id));
    }

    @GetMapping("/reconciliation")
    public ApiResponse<java.util.Map<String, Object>> reconciliation(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return ApiResponse.ok(transport!=null&&transport.pullEnabled()?jobs.create("BALANCE",null,date):service.reconciliation(date));
    }

    @PostMapping("/balances")
    public ApiResponse<java.util.Map<String, Object>> balances(@RequestBody BalanceRequest request) {
        return ApiResponse.ok(service.saveAbsoluteBalances(request.date(), request.lines() == null ? List.of()
            : request.lines().stream().map(line -> new ErpExchangeService.Line(line.materialId(), line.warehouseId(),
                line.quantity(), line.amount())).toList()));
    }

    public record ConnectionRequest(boolean enabled, String baseUrl, String username, String password, String scheduleCron) {
    }

    public record PostingRequest(String arrivalMode, String accountCode, String subcontoCode, String warehouseCode) {
    }

    public record BalanceRequest(LocalDate date, List<BalanceLine> lines) {
    }

    public record BalanceLine(UUID materialId, UUID warehouseId, BigDecimal quantity, BigDecimal amount) {
    }
}
