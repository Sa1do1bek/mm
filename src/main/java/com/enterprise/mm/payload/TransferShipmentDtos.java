package com.enterprise.mm.payload;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class TransferShipmentDtos {
    private TransferShipmentDtos() {}
    public record Shipment(UUID id, LocalDate dispatchedDate, List<Allocation> allocations, String actor, String actorDisplayName, java.time.LocalDateTime createdAt, List<TransferPickingDtos.Decision> pickingDecisions) {
        public Shipment(UUID id, LocalDate date, List<Allocation> allocations, String actor, String actorDisplayName, java.time.LocalDateTime createdAt) { this(id,date,allocations,actor,actorDisplayName,createdAt,List.of()); }
        public Shipment(UUID id, LocalDate date, List<Allocation> allocations) { this(id,date,allocations,null,null,null); }
    }
    public record Clearance(UUID id, String kind, BigDecimal quantity, BigDecimal bookValue, LocalDate postingDate, UUID movementId, UUID destinationStockBatchId, String actor, String actorDisplayName, java.time.LocalDateTime createdAt) {}
    public record Allocation(UUID id, UUID transferLineId, UUID sourceStockBatchId, String batchNumber,
        String serialNumber, LocalDate expiryDate, BigDecimal dispatchedQuantity, BigDecimal unresolvedQuantity,
        BigDecimal remainingBookValue, String unitCode, int quantityScale, List<Clearance> clearances, String unitShortName) {
        public Allocation(UUID id, UUID transferLineId, UUID sourceStockBatchId, String batchNumber,
        String serialNumber, LocalDate expiryDate, BigDecimal dispatchedQuantity, BigDecimal unresolvedQuantity,
        BigDecimal remainingBookValue, String unitCode, int quantityScale, List<Clearance> clearances) {
            this(id, transferLineId, sourceStockBatchId, batchNumber, serialNumber, expiryDate, dispatchedQuantity, unresolvedQuantity, remainingBookValue, unitCode, quantityScale, clearances, null);
        }
        public Allocation withUnitShortName(String value) {
            return new Allocation(id, transferLineId, sourceStockBatchId, batchNumber, serialNumber, expiryDate, dispatchedQuantity, unresolvedQuantity, remainingBookValue, unitCode, quantityScale, clearances, value);
        }

        public Allocation(UUID id, UUID lineId, UUID batchId, String batch, String serial, LocalDate expiry, BigDecimal dispatched, BigDecimal unresolved, BigDecimal value, String unitCode, int scale) {
            this(id,lineId,batchId,batch,serial,expiry,dispatched,unresolved,value,unitCode,scale,List.of());
        }
    }
    public record Action(Long expectedVersion, String reason) {}
}
