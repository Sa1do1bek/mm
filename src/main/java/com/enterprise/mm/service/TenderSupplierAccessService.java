package com.enterprise.mm.service;

import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RfqSupplierInvitation;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.RfqSupplierInvitationRepository;
import com.enterprise.mm.service.rfq.RfqSupplierAccessPolicy;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class TenderSupplierAccessService {
    private final RfqSupplierInvitationRepository invitationRepository;
    private final Clock clock;
    private final SupplierEligibilityService supplierEligibilityService;

    @Autowired
    public TenderSupplierAccessService(
        RfqSupplierInvitationRepository invitationRepository,
        Clock clock,
        SupplierEligibilityService supplierEligibilityService
    ) {
        this.invitationRepository = invitationRepository;
        this.clock = clock;
        this.supplierEligibilityService = supplierEligibilityService;
    }

    public TenderSupplierAccessService(
        RfqSupplierInvitationRepository invitationRepository,
        Clock clock
    ) {
        this(invitationRepository, clock, null);
    }

    public boolean canViewTender(RFQ rfq, Supplier supplier) {
        try {
            validateViewTender(rfq, supplier);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    public boolean canSubmitBid(RFQ rfq, Supplier supplier) {
        try {
            validateBidSubmission(rfq, supplier);
            return true;
        } catch (BusinessException exception) {
            return false;
        }
    }

    public void validateViewTender(RFQ rfq, Supplier supplier) {
        requireOpenPublishedRfq(rfq);
        requireActiveSupplier(supplier);
        requireInvitationIfNeeded(rfq, supplier);
    }

    public void validateBidSubmission(RFQ rfq, Supplier supplier) {
        validateViewTender(rfq, supplier);
        if (supplierEligibilityService != null) {
            supplierEligibilityService.requireNewBusinessAllowed(supplier.getId(), null, "RFQ_PROPOSAL_SUBMISSION");
        }
    }

    public Optional<RfqSupplierInvitation> activeInvitation(RFQ rfq, Supplier supplier) {
        if (rfq == null || supplier == null || usesOpenSupplierAccess(rfq)) {
            return Optional.empty();
        }
        return invitationRepository.findActiveInvitation(
            rfq.getTenantId(), rfq.getCompanyId(), rfq.getId(), supplier.getId()
        );
    }

    public boolean isOpenTender(RFQ rfq) {
        return usesOpenSupplierAccess(rfq);
    }

    private void requireOpenPublishedRfq(RFQ rfq) {
        if (rfq == null || !"OPEN".equals(normalize(rfq.getStatus()))) {
            throw new BusinessException("RFQ is not open for supplier portal");
        }
        if (rfq.getDueDate() == null || rfq.getDueDate().isBefore(LocalDate.now(clock))) {
            throw new BusinessException("RFQ response deadline has passed");
        }
    }

    private void requireActiveSupplier(Supplier supplier) {
        if (supplier == null) {
            throw new BusinessException("Supplier not found");
        }
        if ("BLOCKED".equals(normalize(supplier.getStatus()))) {
            throw new BusinessException("Supplier is blocked and cannot access RFQ quotations");
        }
        if (!Set.of("ACTIVE", "LIMITED").contains(normalize(supplier.getStatus()))) {
            throw new BusinessException("Supplier is not active and cannot access RFQ quotations");
        }
    }

    private void requireInvitationIfNeeded(RFQ rfq, Supplier supplier) {
        if (usesOpenSupplierAccess(rfq)) {
            return;
        }
        boolean invited = activeInvitation(rfq, supplier)
            .filter(invitation -> Objects.equals(invitation.getSupplierId(), supplier.getId()))
            .isPresent();
        if (!invited) {
            throw new BusinessException("Active RFQ supplier invitation is required");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private boolean usesOpenSupplierAccess(RFQ rfq) {
        return RfqSupplierAccessPolicy.usesOpenSupplierAccess(rfq);
    }
}
