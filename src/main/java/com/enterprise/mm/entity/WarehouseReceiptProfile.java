package com.enterprise.mm.entity;

import com.enterprise.mm.payload.WarehouseReceiptProfileDtos.Effect;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;

@Getter @Setter @Entity
@Table(name = "warehouse_receipt_profiles")
public class WarehouseReceiptProfile {
    @Id @Column(name = "warehouse_id") private UUID warehouseId;
    @Column(name = "tenant_id", nullable = false, length = 80) private String tenantId;
    @Column(name = "company_id", nullable = false) private UUID companyId;
    @Version @Column(nullable = false) private Long version;
    @Column(length = 2000) private String purpose;
    @Column(name = "created_at", nullable = false) private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private LocalDateTime updatedAt;
    @Column(name = "created_by", length = 120) private String createdBy;
    @Column(name = "updated_by", length = 120) private String updatedBy;
    @ElementCollection
    @CollectionTable(name = "warehouse_receipt_profile_rules", joinColumns = @JoinColumn(name = "warehouse_id"),
        uniqueConstraints = @UniqueConstraint(columnNames = {"warehouse_id", "material_group_id"}))
    private List<GroupRule> rules = new ArrayList<>();

    @Getter @Setter @Embeddable
    public static class GroupRule {
        @Column(name = "material_group_id", nullable = false) private UUID materialGroupId;
        @Enumerated(EnumType.STRING) @Column(nullable = false, length = 10) private Effect effect;
        @Column(name = "include_descendants", nullable = false) private boolean includeDescendants;
        public GroupRule() {}
        public GroupRule(UUID groupId, Effect effect, boolean descendants) {
            this.materialGroupId = groupId; this.effect = effect; this.includeDescendants = descendants;
        }
    }
}
