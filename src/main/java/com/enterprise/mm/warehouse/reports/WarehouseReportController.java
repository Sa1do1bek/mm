package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.payload.ApiResponse;
import com.enterprise.mm.enums.MaterialSubtype;
import com.enterprise.mm.enums.MaterialType;
import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

@RestController
@RequestMapping("/api/warehouse-reports")
public class WarehouseReportController {
    private final WarehouseReportService service;
    private final WarehouseReportExport exporter;
    private final TransferMovementQuery transfers;

    public WarehouseReportController(WarehouseReportService service, WarehouseReportExport exporter, TransferMovementQuery transfers) {
        this.service = service;
        this.exporter = exporter;
        this.transfers = transfers;
    }

    @GetMapping("/transfers")
    public ApiResponse<java.util.Map<String, Object>> transfers(
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(transfers.between(from, to));
    }

    @GetMapping("/options")
    public ApiResponse<WarehouseReportService.Options> options() { return ApiResponse.ok(service.options()); }

    @GetMapping("/references")
    public ApiResponse<com.enterprise.mm.payload.PageResponse<java.util.Map<String,Object>>> references(
        @RequestParam String kind, @RequestParam(required=false) UUID warehouseId,
        @RequestParam(required=false) MaterialSubtype materialSubtype, @RequestParam(required=false) MaterialType materialType,
        @RequestParam(required=false) String accountCode, @RequestParam(required=false) String sourceType, @RequestParam(required=false) String search,
        @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="50") int size) {
        return ApiResponse.ok(service.references(kind,new Filter(Type.STOCK_BALANCES,LocalDate.now(),LocalDate.now(),warehouseId,null,null,search,page,size,materialSubtype,materialType,accountCode,sourceType)));
    }

    @GetMapping("/material-card")
    public ApiResponse<MaterialCard> materialCard(
        @RequestParam UUID materialId,
        @RequestParam(required=false) UUID warehouseId,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) String accountCode,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="50") int size
    ) {
        return ApiResponse.ok(service.materialCard(materialId, warehouseId, from, to, accountCode, page, size));
    }

    @GetMapping
    public ApiResponse<WarehouseReport> report(
        @RequestParam Type type,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) UUID warehouseId,
        @RequestParam(required=false) UUID materialId,
        @RequestParam(required=false) UUID materialGroupId,
        @RequestParam(required=false) MaterialSubtype materialSubtype,
        @RequestParam(required=false) MaterialType materialType,
        @RequestParam(required=false) String accountCode,
        @RequestParam(required=false) String sourceType,
        @RequestParam(required=false) UUID ownerPartyId,
        @RequestParam(required=false) String search,
        @RequestParam(defaultValue="0") int page,
        @RequestParam(defaultValue="50") int size,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime snapshotTimestamp
    ) {
        return ApiResponse.ok(service.generate(new Filter(type,from,to,warehouseId,materialId,materialGroupId,search,page,size,materialSubtype,materialType,accountCode,sourceType,ownerPartyId,snapshotTimestamp),false));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(
        @RequestParam Type type,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate to,
        @RequestParam(required=false) UUID warehouseId,
        @RequestParam(required=false) UUID materialId,
        @RequestParam(required=false) UUID materialGroupId,
        @RequestParam(required=false) MaterialSubtype materialSubtype,
        @RequestParam(required=false) MaterialType materialType,
        @RequestParam(required=false) String accountCode,
        @RequestParam(required=false) String sourceType,
        @RequestParam(required=false) UUID ownerPartyId,
        @RequestParam(required=false) String search,
        @RequestParam(defaultValue="xlsx") String format,
        @RequestParam(defaultValue="ru") String language,
        @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) java.time.LocalDateTime snapshotTimestamp
    ) {
        if (!format.equals("xlsx") && !format.equals("csv"))
            throw new com.enterprise.mm.error.BusinessException("Supported export formats: xlsx, csv");
        WarehouseReport report=service.generate(new Filter(type,from,to,warehouseId,materialId,materialGroupId,search,0,50,materialSubtype,materialType,accountCode,sourceType,ownerPartyId,snapshotTimestamp),true);
        byte[] bytes=format.equals("csv") ? exporter.csv(report,language) : exporter.xlsx(report,language);
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"warehouse-"+type.name().toLowerCase(java.util.Locale.ROOT)+"-"+to+"."+format+"\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store")
            .header(HttpHeaders.CONTENT_TYPE,format.equals("csv") ? "text/csv; charset=UTF-8" : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
            .body(bytes);
    }
}
