package com.enterprise.mm.controller;
import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationArchivedDocumentDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationCancelRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDocumentListDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDocumentStatusDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationDownloadResult;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationHealthDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.IntegrationProviderRequest;
import com.enterprise.mm.payload.IntegrationEventResponse;
import com.enterprise.mm.payload.IntegrationSettingResponse;
import com.enterprise.mm.payload.IntegrationSettingUpdateRequest;
import com.enterprise.mm.service.IntegrationSettingService;
import com.enterprise.mm.integration.faktura.FakturaClient;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/settings/integrations")
@RequiredArgsConstructor
public class IntegrationSettingController {
    private final IntegrationSettingService service;
    private final FakturaClient fakturaClient;

    @GetMapping
    public ApiResponse<PageResponse<IntegrationSettingResponse>> list(
        @RequestParam(required = false) String search,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.ok(PageResponse.from(service.listSettings(search, dateFrom, dateTo)));
    }

    @GetMapping("/{system}")
    public ApiResponse<IntegrationSettingResponse> get(@PathVariable String system) {
        return ApiResponse.ok(service.getSetting(ExternalIntegrationSystem.from(system)));
    }

    @PutMapping("/{system}")
    public ApiResponse<IntegrationSettingResponse> save(@PathVariable String system, @Valid @RequestBody IntegrationSettingUpdateRequest request) {
        return ApiResponse.ok(service.saveSetting(ExternalIntegrationSystem.from(system), request));
    }

    @PostMapping("/{system}/test")
    public ApiResponse<IntegrationHealthDto> test(@PathVariable String system) {
        ExternalIntegrationSystem provider = ExternalIntegrationSystem.from(system);
        if (provider == ExternalIntegrationSystem.FAKTURA_UZ) {
            fakturaClient.verifiedCompanyInn();
            return ApiResponse.ok(new IntegrationHealthDto(provider.name(), true, true, false,
                "LIVE_OK", "Faktura account has access to this company INN", java.time.LocalDateTime.now()));
        }
        return ApiResponse.ok(service.health(provider));
    }

    @PostMapping("/{system}/ai/test")
    public ApiResponse<AiPromptResponse> testAi(@PathVariable String system, @RequestBody(required = false) AiPromptRequest request) {
        return ApiResponse.ok(service.testAiProvider(ExternalIntegrationSystem.from(system), request));
    }

    @PostMapping("/{system}/tax-invoices")
    public ApiResponse<IntegrationEventResponse> taxInvoice(
        @PathVariable String system,
        @RequestBody(required = false) IntegrationProviderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return enqueue(system, "TAX_INVOICE", request, idempotencyKey);
    }

    @PostMapping("/{system}/documents")
    public ApiResponse<IntegrationEventResponse> document(
        @PathVariable String system,
        @RequestBody(required = false) IntegrationProviderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return enqueue(system, "DOCUMENT", request, idempotencyKey);
    }

    @PostMapping("/{system}/supplier-status")
    public ApiResponse<IntegrationEventResponse> supplierStatus(
        @PathVariable String system,
        @RequestBody(required = false) IntegrationProviderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return enqueue(system, "SUPPLIER_STATUS", request, idempotencyKey);
    }

    @PostMapping("/{system}/actions/{action}")
    public ApiResponse<IntegrationEventResponse> enqueue(
        @PathVariable String system,
        @PathVariable String action,
        @RequestBody(required = false) IntegrationProviderRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.enqueueAction(ExternalIntegrationSystem.from(system), action, request, idempotencyKey));
    }

    @GetMapping("/{system}/documents/{externalId}/status")
    public ApiResponse<IntegrationDocumentStatusDto> documentStatus(@PathVariable String system, @PathVariable String externalId) {
        return ApiResponse.ok(service.documentStatus(ExternalIntegrationSystem.from(system), externalId));
    }

    @PostMapping("/{system}/documents/{externalId}/cancel")
    public ApiResponse<IntegrationEventResponse> cancelDocument(
        @PathVariable String system,
        @PathVariable String externalId,
        @RequestBody(required = false) IntegrationCancelRequest request,
        @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ApiResponse.ok(service.cancelDocument(ExternalIntegrationSystem.from(system), externalId, request, idempotencyKey));
    }

    @GetMapping("/{system}/documents/incoming")
    public ApiResponse<IntegrationDocumentListDto> incomingDocuments(
        @PathVariable String system,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.documents(ExternalIntegrationSystem.from(system), "INCOMING", dateFrom, dateTo, status));
    }

    @GetMapping("/{system}/documents/outgoing")
    public ApiResponse<IntegrationDocumentListDto> outgoingDocuments(
        @PathVariable String system,
        @RequestParam(required = false) LocalDate dateFrom,
        @RequestParam(required = false) LocalDate dateTo,
        @RequestParam(required = false) String status
    ) {
        return ApiResponse.ok(service.documents(ExternalIntegrationSystem.from(system), "OUTGOING", dateFrom, dateTo, status));
    }

    @GetMapping("/{system}/documents/{externalId}/download")
    public ResponseEntity<byte[]> downloadDocument(@PathVariable String system, @PathVariable String externalId) {
        IntegrationDownloadResult file = service.downloadDocument(ExternalIntegrationSystem.from(system), externalId);
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_TYPE, file.contentType())
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName().replace("\"", "") + "\"")
            .header("X-Archived-Attachment-Id", file.archivedAttachment().getId().toString())
            .body(file.content());
    }

    @PostMapping("/{system}/documents/{externalId}/archive-original")
    public ApiResponse<IntegrationArchivedDocumentDto> archiveOriginal(@PathVariable String system, @PathVariable String externalId) {
        return ApiResponse.ok(service.archiveOriginalDocument(ExternalIntegrationSystem.from(system), externalId));
    }

    @GetMapping("/{system}/documents/{externalId}/originals")
    public ApiResponse<PageResponse<AttachmentDto>> originals(@PathVariable String system, @PathVariable String externalId) {
        return ApiResponse.ok(PageResponse.from(service.originalDocuments(ExternalIntegrationSystem.from(system), externalId)));
    }

}
