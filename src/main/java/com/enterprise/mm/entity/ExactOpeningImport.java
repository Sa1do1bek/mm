package com.enterprise.mm.entity;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Entity @Table(name="exact_opening_imports", uniqueConstraints=@UniqueConstraint(name="uk_exact_opening_company", columnNames={"tenant_id","company_id"}))
@Getter @Setter
public class ExactOpeningImport {
    @Id private UUID id;
    @Column(name="tenant_id", nullable=false, length=80) private String tenantId;
    @Column(name="company_id", nullable=false) private UUID companyId;
    @Column(name="source_manifest_sha256", nullable=false, length=64) private String sourceManifestSha256;
    @Column(name="prepared_manifest_sha256", nullable=false, length=64) private String preparedManifestSha256;
    @Column(name="source_organization_id", nullable=false, length=32) private String sourceOrganizationId;
    @Column(name="source_evidence", nullable=false, columnDefinition="text") private String sourceEvidence;
    @Column(name="posting_date", nullable=false) private LocalDate postingDate;
    @Column(name="expected_rows", nullable=false) private int expectedRows;
    @Column(name="expected_quantity", nullable=false, precision=22, scale=7) private BigDecimal expectedQuantity;
    @Column(name="expected_value", nullable=false, precision=19, scale=4) private BigDecimal expectedValue;
    @Column(name="accepted_rows", nullable=false) private int acceptedRows;
    @Column(name="accepted_quantity", nullable=false, precision=22, scale=7) private BigDecimal acceptedQuantity=BigDecimal.ZERO;
    @Column(name="accepted_value", nullable=false, precision=19, scale=4) private BigDecimal acceptedValue=BigDecimal.ZERO;
    @Column(nullable=false, length=20) private String status="IMPORTING";
    @Column(name="created_at", nullable=false) private LocalDateTime createdAt=LocalDateTime.now();
    @Column(name="completed_at") private LocalDateTime completedAt;
}
