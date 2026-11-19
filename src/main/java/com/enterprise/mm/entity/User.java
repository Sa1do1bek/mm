package com.enterprise.mm.entity;

import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.enums.RoleName;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "users", uniqueConstraints = @UniqueConstraint(name = "uk_users_tenant_company_username", columnNames = {"tenant_id", "company_id", "username"}))
@AttributeOverride(name = "companyId", column = @Column(name = "company_id", nullable = true))
public class User extends BusinessEntity {
    @Column(nullable = false, length = 120)
    private String username;
    @Column(nullable = false, length = 500)
    private String passwordHash;
    @Column(name = "token_version", nullable = false)
    private long tokenVersion;
    private String fullName;
    @Column(length = 240)
    private String email;
    @ElementCollection
    @CollectionTable(name = "user_additional_emails", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "email", nullable = false, length = 240)
    private Set<String> additionalEmails = new HashSet<>();

    public void reconcilePrimaryEmail() {
        if (email == null || email.isBlank()) {
            String next = additionalEmails.stream().min(String.CASE_INSENSITIVE_ORDER).orElse(null);
            if (next != null) additionalEmails.remove(next);
            email = next;
        } else {
            additionalEmails.removeIf(value -> value.equalsIgnoreCase(email));
        }
    }
    @Column(nullable = false)
    private boolean enabled = true;
    @Column(nullable = false)
    private boolean mfaEnabled;
    @Column(length = 20)
    private String mfaMethod = "TOTP";
    @Column(length = 500)
    private String mfaSecret;
    @Column(length = 80)
    private String mfaPhoneNumber;
    @Column(length = 128)
    private String mfaSmsCodeHash;
    private LocalDateTime mfaSmsExpiresAt;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "company_id", insertable = false, updatable = false)
    private Company company;


    @ManyToOne(fetch = FetchType.LAZY)
    private Department department;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "employee_id")
    private Employee employee;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "role", length = 80)
    @Enumerated(EnumType.STRING)
    private Set<RoleName> roles = new HashSet<>();

    /**
     * Marks a company-less super administrator (companyId == null with the ADMIN role).
     * Kept in sync on load so the flag survives across persist/update cycles and keeps
     * {@link com.enterprise.mm.common.BusinessEntity} from defaulting companyId to the current company.
     */
    @Transient
    private boolean superAdmin;

    @PostLoad
    private void markSuperAdmin() {
        this.superAdmin = getCompanyId() == null && roles != null && roles.contains(RoleName.ADMIN);
    }

    @PrePersist
    @PreUpdate
    private void syncRelations() {
        if (superAdmin) {
            setCompanyId(null);
        }
    }
}
