package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.SupplierMagicLinkRequestReason;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Transient;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "supplier_magic_link_requests")
@AttributeOverride(name = "companyId", column = @Column(name = "company_id", nullable = true))
public class SupplierMagicLinkRequest extends BusinessEntity {
    @Transient
    private boolean unresolvedCompany;

    @Column(name = "normalized_email_hash", nullable = false, length = 64)
    private String normalizedEmailHash;

    @Column(name = "request_ip_hash", nullable = false, length = 64)
    private String requestIpHash;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(name = "matched_account", nullable = false)
    private boolean matchedAccount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 80)
    private SupplierMagicLinkRequestReason reasonCode;

    @PrePersist
    @PreUpdate
    void preserveUnresolvedCompany() {
        if (unresolvedCompany) {
            setCompanyId(null);
        }
    }
}
