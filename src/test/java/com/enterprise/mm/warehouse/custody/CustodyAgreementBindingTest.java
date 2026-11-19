package com.enterprise.mm.warehouse.custody;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PR 4B: Custody Agreement Binding & Scope Validation:
 * - Unknown owner/custodian without agreement is blocked with NotFoundException.
 * - Inactive or suspended agreements block custody operations.
 * - Expired agreements block custody operations.
 * - Active valid agreements permit operation.
 */
class CustodyAgreementBindingTest {
    private final WarehouseCustodyAgreementRepository agreementRepository = mock(WarehouseCustodyAgreementRepository.class);
    private final CustodyAgreementService service = new CustodyAgreementService(agreementRepository);

    private final String tenantId = "navoiy-azot";
    private final UUID companyId = UUID.randomUUID();
    private final UUID contractorOwnerId = UUID.randomUUID();
    private final UUID unknownOwnerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("PR 4B: Unknown owner without any custody agreement stays blocked")
    void unknownOwnerWithoutAgreementIsBlocked() {
        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, unknownOwnerId))
            .thenReturn(List.of());

        assertThatThrownBy(() -> service.validateAgreementForCustodyOperation(
            tenantId, companyId, unknownOwnerId, LocalDate.of(2026, 10, 14)
        ))
            .isInstanceOf(NotFoundException.class)
            .hasMessageContaining("No custody agreement found for owner");
    }

    @Test
    @DisplayName("PR 4B: Expired custody agreement blocks custody operation")
    void expiredAgreementBlocksCustodyOperation() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-2025-099");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("JV Energo-Gaz Prom");
        agreement.setValidFrom(LocalDate.of(2025, 1, 1));
        agreement.setValidThrough(LocalDate.of(2025, 12, 31)); // Expired before 2026
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        assertThatThrownBy(() -> service.validateAgreementForCustodyOperation(
            tenantId, companyId, contractorOwnerId, LocalDate.of(2026, 10, 14)
        ))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("expired");
    }

    @Test
    @DisplayName("PR 4B: Suspended custody agreement blocks custody operation")
    void suspendedAgreementBlocksCustodyOperation() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-2026-001");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("JV Energo-Gaz Prom");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(LocalDate.of(2026, 12, 31));
        agreement.setAgreementStatus(CustodyAgreementStatus.SUSPENDED);

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        assertThatThrownBy(() -> service.validateAgreementForCustodyOperation(
            tenantId, companyId, contractorOwnerId, LocalDate.of(2026, 10, 14)
        ))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("SUSPENDED");
    }

    @Test
    @DisplayName("PR 4B: Active valid custody agreement binds successfully and permits operation")
    void activeAgreementBindsSuccessfully() {
        var agreement = new WarehouseCustodyAgreement();
        agreement.setId(UUID.randomUUID());
        agreement.setTenantId(tenantId);
        agreement.setCompanyId(companyId);
        agreement.setAgreementNumber("CUST-2026-002");
        agreement.setOwnerId(contractorOwnerId);
        agreement.setCounterpartyName("JV Energo-Gaz Prom");
        agreement.setValidFrom(LocalDate.of(2026, 1, 1));
        agreement.setValidThrough(LocalDate.of(2026, 12, 31));
        agreement.setAgreementStatus(CustodyAgreementStatus.ACTIVE);
        agreement.setSettlementPolicy("STORAGE_FEE");

        when(agreementRepository.findByTenantIdAndCompanyIdAndOwnerId(tenantId, companyId, contractorOwnerId))
            .thenReturn(List.of(agreement));

        var bound = service.validateAgreementForCustodyOperation(
            tenantId, companyId, contractorOwnerId, LocalDate.of(2026, 10, 14)
        );

        assertThat(bound).isNotNull();
        assertThat(bound.getAgreementNumber()).isEqualTo("CUST-2026-002");
        assertThat(bound.getAgreementStatus()).isEqualTo(CustodyAgreementStatus.ACTIVE);
    }
}
