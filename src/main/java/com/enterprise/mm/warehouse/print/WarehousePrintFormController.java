package com.enterprise.mm.warehouse.print;

import com.enterprise.mm.payload.ApiResponse;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/warehouse-documents")
public class WarehousePrintFormController {

    private final WarehousePrintFormService printFormService;

    public WarehousePrintFormController(WarehousePrintFormService printFormService) {
        this.printFormService = printFormService;
    }

    @GetMapping("/{documentType}/{id}/print")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER','OPERATOR')")
    public ResponseEntity<?> printDocument(
        @PathVariable WarehousePrintDocumentType documentType,
        @PathVariable UUID id,
        @RequestParam(defaultValue = "html") String format,
        @RequestParam(defaultValue = "ru") String lang
    ) {
        if ("xlsx".equalsIgnoreCase(format) || "excel".equalsIgnoreCase(format)) {
            byte[] bytes = printFormService.printExcel(documentType, id, lang);
            WarehousePrintFormData data = printFormService.getPrintFormData(documentType, id, lang);
            String fileName = (data.formCode() != null ? data.formCode() : documentType.name()) + "_" + (data.documentNumber() != null ? data.documentNumber() : id.toString()) + ".xlsx";

            return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + fileName + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(bytes);
        }

        if ("data".equalsIgnoreCase(format) || "json".equalsIgnoreCase(format)) {
            WarehousePrintFormData data = printFormService.getPrintFormData(documentType, id, lang);
            return ResponseEntity.ok(ApiResponse.ok(data));
        }

        String html = printFormService.printHtml(documentType, id, lang);
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/html; charset=UTF-8"))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
            .body(html);
    }

    @GetMapping("/{documentType}/{id}/evidence")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER','OPERATOR')")
    public ApiResponse<WarehouseDocumentEvidenceBundle> getEvidenceBundle(
        @PathVariable WarehousePrintDocumentType documentType,
        @PathVariable UUID id
    ) {
        return ApiResponse.ok(printFormService.getEvidenceBundle(documentType, id));
    }

    @PostMapping("/{documentType}/{id}/evidence/sign")
    @PreAuthorize("hasAnyRole('ADMIN','MM_MANAGER','WAREHOUSE_MANAGER','WAREHOUSE_OPERATOR','WAREHOUSE_HEAD','WAREHOUSE_KEEPER','FINANCE_CONTROLLER','ACCOUNTANT','AUDITOR','VIEWER','DEPARTMENT_HEAD','DEPARTMENT_WORKER','OPERATOR')")
    public ApiResponse<WarehouseDocumentEvidenceBundle> signEvidence(
        @PathVariable WarehousePrintDocumentType documentType,
        @PathVariable UUID id,
        @RequestBody WarehouseDocumentEvidenceBundle.SignEvidenceRequest request
    ) {
        return ApiResponse.ok(printFormService.signDocumentEvidence(documentType, id, request));
    }
}
