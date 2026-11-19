package com.enterprise.mm.integration.faktura;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.error.BusinessException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/faktura")
@RequiredArgsConstructor
public class FakturaController {
    private final FakturaClient client;
    private final FakturaImportService importer;
    private final FakturaDocumentQuery query;
    private final FakturaReceiptEvidenceService evidence;

    @PostMapping("/test-connection")
    public ApiResponse<ConnectionResult> testConnection() {
        String inn = client.verifiedCompanyInn();
        return ApiResponse.ok(new ConnectionResult(true, inn));
    }

    @PostMapping("/import")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<FakturaImportService.ImportResult> importRange(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(importer.startImportRange(from, to));
    }

    @GetMapping("/runs")
    public ApiResponse<List<FakturaImportService.ImportResult>> runs() {
        return ApiResponse.ok(importer.recentRuns());
    }

    @GetMapping("/runs/{runId}/failures")
    public ApiResponse<FakturaImportService.FailurePage> failures(@PathVariable UUID runId,
        @RequestParam(required = false) Integer type,
        @RequestParam(required = false) String search,
        @RequestParam(required = false) String stage,
        @RequestParam(required = false) String reason,
        @RequestParam(defaultValue = "createdAt") String sortBy,
        @RequestParam(defaultValue = "asc") String sortDir,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(importer.failures(runId, type, search, stage, reason, sortBy, sortDir, page, size));
    }

    @GetMapping("/review")
    public ApiResponse<List<FakturaDocumentQuery.Document>> review() {
        return ApiResponse.ok(query.reviewQueue());
    }

    @GetMapping("/documents")
    public ApiResponse<FakturaDocumentQuery.DocumentPage> documents(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) Integer type,
        @RequestParam(required = false) String matchState,
        @RequestParam(required = false) String number,
        @RequestParam(required = false) String owner,
        @RequestParam(required = false) String contractor,
        @RequestParam(required = false) String contractNumber,
        @RequestParam(required = false) UUID contractId,
        @RequestParam(required = false) String documentGroup,
        @RequestParam(required = false) Integer providerStatus,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate dateTo,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate seenFrom,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate seenTo,
        @RequestParam(defaultValue = "lastSeenAt") String sortBy,
        @RequestParam(defaultValue = "desc") String sortDir,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.ok(query.browse(new FakturaDocumentQuery.BrowseFilters(search, type, matchState,
            number, owner, contractor, contractNumber, providerStatus, dateFrom, dateTo, seenFrom, seenTo,
            sortBy, sortDir, contractId, documentGroup), page, size));
    }

    @GetMapping("/documents/{id}")
    public ApiResponse<FakturaDocumentQuery.DocumentDetails> document(@PathVariable UUID id) {
        return ApiResponse.ok(query.details(id));
    }

    @PostMapping("/review/{sourceId}/link")
    public ApiResponse<Void> link(@PathVariable UUID sourceId, @RequestBody LinkRequest request) {
        if (request == null || request.contractId() == null) throw new BusinessException("Contract is required");
        query.linkToContract(sourceId, request.contractId());
        return ApiResponse.message("Faktura document linked to contract");
    }

    @GetMapping("/contracts/{contractId}/candidates")
    public ApiResponse<FakturaDocumentQuery.Candidates> candidates(
        @PathVariable UUID contractId,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate receiptDate) {
        return ApiResponse.ok(query.candidates(contractId, receiptDate));
    }

    @GetMapping("/receipts/{kind}/{receiptId}/evidence")
    public ApiResponse<List<FakturaReceiptEvidenceService.Evidence>> evidence(
        @PathVariable FakturaReceiptEvidenceService.ReceiptKind kind, @PathVariable UUID receiptId) {
        return ApiResponse.ok(evidence.evidence(kind, receiptId));
    }

    public record ConnectionResult(boolean connected, String companyInn) {}
    public record LinkRequest(UUID contractId) {}
}
