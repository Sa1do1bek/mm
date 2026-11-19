package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.IntegrationEventRepository;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class TreasuryPaymentAcknowledgementPolicy {
    private static final List<String> SUCCESSFUL_STATUSES = List.of("PAID", "SUCCESS", "SUCCEEDED", "COMPLETED");

    private final IntegrationEventRepository integrationEventRepository;
    @org.springframework.beans.factory.annotation.Autowired
    private com.enterprise.mm.treasury.TreasuryNativeAccess treasuryNativeAccess;

    @Transactional(readOnly = true)
    public void requireSuccessfulAcknowledgement(UUID aggregateId, String externalReference) {
        if(treasuryNativeAccess!=null&&treasuryNativeAccess.nativeEnabled())
            throw new BusinessException("Legacy Treasury acknowledgements cannot authorize payment in native mode");
        if (aggregateId == null) {
            throw new BusinessException("Payment aggregate is required for Treasury acknowledgement");
        }
        String normalizedReference = externalReference == null ? null : externalReference.trim();
        if (normalizedReference == null || normalizedReference.isBlank()) {
            throw new BusinessException("External Treasury payment reference is required before marking paid");
        }
        long acknowledgements = integrationEventRepository.countSuccessfulTreasuryPaymentAcknowledgements(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            aggregateId,
            normalizedReference,
            SUCCESSFUL_STATUSES
        );
        if (acknowledgements == 0) {
            throw new BusinessException(
                "Successful Treasury payment acknowledgement is required before marking paid"
            );
        }
    }
}
