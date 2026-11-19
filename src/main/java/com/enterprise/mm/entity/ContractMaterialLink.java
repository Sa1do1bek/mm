package com.enterprise.mm.entity;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UuidGenerator;
import org.hibernate.type.SqlTypes;

/** Append-only migration evidence. No business CRUD or soft-delete lifecycle. */
@Entity
@Table(name = "contract_material_links", uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "company_id", "specification_id", "material_id"}))
@Immutable
@Getter
@Setter
public class ContractMaterialLink {
    @Id @GeneratedValue @UuidGenerator private UUID id;
    @Column(nullable = false, updatable = false, length = 80) private String tenantId;
    @Column(nullable = false, updatable = false) private UUID companyId;
    @Column(nullable = false, updatable = false) private UUID specificationId;
    @Column(nullable = false, updatable = false) private UUID materialId;
    @Column(nullable = false, updatable = false) private UUID contractId;
    @Column(nullable = false, updatable = false) private UUID contractMaterialGroupId;
    @Column(updatable = false) private UUID stockMaterialGroupId;
    @Column(nullable = false, updatable = false) private UUID contractUnitOfMeasureId;
    @Column(nullable = false, updatable = false) private UUID stockUnitOfMeasureId;
    @Column(nullable = false, updatable = false, precision = 10, scale = 3) private BigDecimal contractQuantityPerStockQuantity;
    @Column(nullable = false, updatable = false, length = 64) private String sourceManifestSha256;
    @Column(nullable = false, updatable = false, length = 64) private String preparedManifestSha256;
    @Column(nullable = false, updatable = false, length = 40) private String sourceEntityType;
    @Column(nullable = false, updatable = false, length = 32) private String sourceId;
    @Column(nullable = false, updatable = false, length = 32) private String materialSourceId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb") private JsonNode contractUnitSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb") private JsonNode stockUnitSnapshot;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb") private JsonNode evidence;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, updatable = false, columnDefinition = "jsonb") private JsonNode sourcePayload;
    @Column(nullable = false, updatable = false) private LocalDateTime createdAt;
}
