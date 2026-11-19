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

@Getter
@Setter
@Entity
@Table(name = "supplier_portal_accounts")
public class SupplierPortalAccount extends BusinessEntity {
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id", insertable = false, updatable = false)
    private Supplier supplier;

    @Column(name = "login_email", nullable = false, length = 240)
    private String loginEmail;

    @Column(name = "contact_person", length = 240)
    private String contactPerson;

    @Column(name = "portal_role", nullable = false, length = 50)
    private String portalRole = "SUPPLIER_ADMIN";

    @Column(length = 80)
    private String phone;

    @Column(name = "invite_token_hash", length = 64)
    @Deprecated(forRemoval = false)
    private String inviteTokenHash;

    @Column(name = "invite_token_expires_at")
    @Deprecated(forRemoval = false)
    private LocalDateTime inviteTokenExpiresAt;

    @Column(name = "portal_token_hash", length = 64)
    private String portalTokenHash;

    @Column(name = "portal_token_expires_at")
    private LocalDateTime portalTokenExpiresAt;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_submission_at")
    private LocalDateTime lastSubmissionAt;

    @Column(name = "allowed_ip_range", length = 240)
    private String allowedIpRange;

    @Column(name = "mfa_required", nullable = false)
    private boolean mfaRequired = true;

    @Column(name = "terms_accepted_at")
    private LocalDateTime termsAcceptedAt;
}
