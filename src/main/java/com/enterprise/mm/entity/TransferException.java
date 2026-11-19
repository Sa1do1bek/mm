package com.enterprise.mm.entity;
import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
@Getter @Setter @Entity @Table(name="transfer_exceptions")
public class TransferException extends BusinessEntity {
    @Column(name="transfer_document_id") private UUID transferDocumentId;
    @Column(name="allocation_id") private UUID allocationId;
    @Column(name="kind", length=40) private String kind;
    @Column(name="quantity", precision = 22, scale = 7) private BigDecimal quantity;
    @Column(name="book_value", precision=19, scale=4) private BigDecimal bookValue;
    @Column(name="revision") private long revision = 1;
    @Column(name="approval_revision") private Long approvalRevision;
    @Column(name="decision", length=80) private String decision;
    @Column(name="reason", columnDefinition="text") private String reason;
    @Column(name="reviewed_by", length=120) private String reviewedBy;
    @Column(name="reviewed_at") private LocalDateTime reviewedAt;
    @Column(name="finance_approved_by", length=120) private String financeApprovedBy;
    @Column(name="approval_instance_id") private UUID approvalInstanceId;
    @Column(name="quarantine_clearance_id") private UUID quarantineClearanceId;
    @Column(name="quarantine_lot_id") private UUID quarantineLotId;
    @Column(name="reverse_transfer_id") private UUID reverseTransferId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="attachment_ids", columnDefinition="jsonb") private List<UUID> attachmentIds = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="related_document_ids", columnDefinition="jsonb") private List<UUID> relatedDocumentIds = new ArrayList<>();
    @JdbcTypeCode(SqlTypes.JSON) @Column(name="history", columnDefinition="jsonb") private List<History> history = new ArrayList<>();
    public record History(long revision, String action, String actor, LocalDateTime at, BigDecimal quantity, BigDecimal bookValue, String reason, List<UUID> attachmentIds, String actorDisplayName) {}
}
