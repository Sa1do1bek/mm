package com.enterprise.mm.payload;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import com.enterprise.mm.entity.TransferException.History;
public final class TransferExceptionDtos {
    private TransferExceptionDtos() {}
    public record Report(Long expectedVersion, UUID allocationId, BigDecimal quantity, String kind, String reason, List<UUID> attachmentIds) {}
    public record Review(Long expectedVersion, String decision, String reason) {}
    public record Post(Long expectedVersion, LocalDate postingDate) {}
    public record ReturnReceipt(Long expectedVersion, BigDecimal quantity, UUID storageLocationId, UUID storageBinId, LocalDate receivedDate) {}
    public record CaseResponse(UUID id, Long version, String status, String nextOwner, BigDecimal quantity, BigDecimal bookValue,
        List<UUID> relatedDocumentIds, UUID transferId, UUID allocationId, String kind, String decision, String reason,
        List<UUID> attachmentIds, long revision, UUID approvalInstanceId, UUID reverseTransferId, UUID quarantineLotId, List<History> history, List<String> allowedActions) {}
}
