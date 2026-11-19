package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import jakarta.mail.internet.InternetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SupplierPortalEligibilityValidator {
    public static final String SUPPLIER_EMAIL_INVALID = "RFQ_SUPPLIER_EMAIL_INVALID";
    public static final String PORTAL_EMAIL_CONFLICT = "RFQ_SUPPLIER_PORTAL_EMAIL_CONFLICT";

    private static final Set<String> ELIGIBLE_ACCOUNT_STATUSES = Set.of("INVITED", "ACTIVE");

    private final SupplierPortalAccountRepository accountRepository;

    public List<EligibilityIssue> validate(Supplier supplier) {
        if (supplier == null) {
            return List.of();
        }
        List<EligibilityIssue> issues = new ArrayList<>();
        UUID supplierId = supplier.getId();
        String email = normalizeEmail(supplier.getEmail());
        if (!validEmailAddress(email)) {
            issues.add(new EligibilityIssue(SUPPLIER_EMAIL_INVALID, supplierId));
            return List.copyOf(issues);
        }

        List<SupplierPortalAccount> portalAccountsBySupplier = accountRepository
            .findByTenantIdAndCompanyIdAndSupplier_IdAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                supplierId
            );
        portalAccountsBySupplier.stream()
            .filter(account -> Objects.equals(normalizeEmail(account.getLoginEmail()), email))
            .filter(account -> !eligibleAccount(account, email))
            .findFirst()
            .ifPresent(account -> issues.add(new EligibilityIssue(PORTAL_EMAIL_CONFLICT, supplierId)));

        List<SupplierPortalAccount> portalAccountsByEmail = accountRepository
            .findByTenantIdAndCompanyIdAndLoginEmailIgnoreCaseAndIsDeletedFalseOrderByCreatedAtDesc(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                email
            );
        if (portalAccountsByEmail.stream().anyMatch(account -> !Objects.equals(account.getSupplierId(), supplierId))) {
            issues.add(new EligibilityIssue(PORTAL_EMAIL_CONFLICT, supplierId));
        }
        return List.copyOf(issues);
    }

    public boolean eligible(Supplier supplier) {
        return validate(supplier).isEmpty();
    }

    private boolean eligibleAccount(SupplierPortalAccount account, String email) {
        return ELIGIBLE_ACCOUNT_STATUSES.contains(account.getStatus())
            && Objects.equals(normalizeEmail(account.getLoginEmail()), email);
    }

    public boolean validEmailAddress(String email) {
        if (email == null) {
            return false;
        }
        try {
            InternetAddress address = new InternetAddress(email, true);
            address.validate();
            return email.equals(address.getAddress());
        } catch (Exception exception) {
            return false;
        }
    }

    private String normalizeEmail(String value) {
        String normalized = normalize(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record EligibilityIssue(String code, UUID supplierId) {
    }
}
