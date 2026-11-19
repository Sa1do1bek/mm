package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Getter
@Setter
@Entity
@Table(name = "rfq_published_versions")
public class RfqPublishedVersion extends BusinessEntity {
    @Column(name = "rfq_id", nullable = false)
    private UUID rfqId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "rfq_id", insertable = false, updatable = false)
    private RFQ rfq;

    @Column(name = "version_number", nullable = false)
    private Integer versionNumber;

    @Column(name = "snapshot_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String snapshotJson;

    @Column(name = "package_language", nullable = false, length = 5)
    private String packageLanguage;

    @Column(name = "template_version", nullable = false, length = 40)
    private String templateVersion;

    @Column(name = "pdf_attachment_id")
    private UUID pdfAttachmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pdf_attachment_id", insertable = false, updatable = false)
    private Attachment pdfAttachment;

    @Column(name = "excel_attachment_id")
    private UUID excelAttachmentId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "excel_attachment_id", insertable = false, updatable = false)
    private Attachment excelAttachment;

    @Column(name = "published_at", nullable = false)
    private LocalDateTime publishedAt;

    @Column(name = "published_by", nullable = false, length = 120)
    private String publishedBy;

    @Column(name = "amendment_reason", columnDefinition = "text")
    private String amendmentReason;
}
