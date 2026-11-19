package com.enterprise.mm.payload;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransferDocumentResponse extends BaseRecordDto {
    @Schema(description = "Number of active document positions, including fully received positions")
    private long lineCount;
    @Schema(description = "Exact unresolved transit book value; null when financial access is denied or legacy evidence is unreconciled")
    private java.math.BigDecimal inTransitAmount;
    @Schema(description = "Book currency of inTransitAmount (UZS)", example = "UZS")
    private String bookCurrencyCode;
    private com.enterprise.mm.service.transfer.TransferProgressService.Progress progress;
    private List<com.enterprise.mm.service.transfer.TransferProgressService.UnitSummary> unitSummaries;
    private UUID returnOfTransferId;
    private UUID returnExceptionId;
    private UUID sourceWarehouseId;
    private String sourceWarehouseName;
    private UUID sourceLocationId;
    private UUID destinationWarehouseId;
    private String destinationWarehouseName;
    private UUID destinationLocationId;
    private LocalDate plannedDate;
    private LocalDate dispatchedDate;
    private LocalDate receivedDate;
    private UUID dispatchMovementDocumentId;
    private UUID receiveMovementDocumentId;
    private List<TransferDocumentLineResponse> lines;
    private List<String> allowedActions;
    private List<TransferShipmentDtos.Shipment> shipments;
    private boolean shipmentFlowEnabled;
    private String reconciliationStatus;

}
