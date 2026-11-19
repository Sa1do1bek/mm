package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ImportDelivery;
import com.enterprise.mm.entity.ImportInsurancePolicy;
import com.enterprise.mm.enums.ImportInsuranceStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ImportInsurancePolicyMapper;
import com.enterprise.mm.payload.ImportInsurancePolicyUpdateRequest;
import com.enterprise.mm.repository.ImportInsurancePolicyRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ImportInsurancePolicyLifecycleHardeningTest {
    @Mock private ImportInsurancePolicyRepository repository;
    @Mock private ImportInsurancePolicyMapper mapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private LandedCostComponentService landedCostComponentService;
    @Mock private AuditService auditService;
    @InjectMocks private ImportInsurancePolicyService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = ImportInsuranceStatus.class, names = {"ACTIVE", "CLAIMED", "CLOSED", "CANCELLED"})
    void updateRejectsNonDraftPolicyBeforeMapperCanMutate(ImportInsuranceStatus persistedStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        ImportInsurancePolicy entity = new ImportInsurancePolicy();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(persistedStatus.name());
        entity.setName("Original insurance policy");
        entity.setImportDelivery(new ImportDelivery());

        ImportInsurancePolicyUpdateRequest update = new ImportInsurancePolicyUpdateRequest();
        update.setStatus(ImportInsuranceStatus.DRAFT.name());
        update.setName("Mutated insurance policy");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            ImportInsurancePolicy target = invocation.getArgument(0);
            ImportInsurancePolicyUpdateRequest request = invocation.getArgument(1);
            target.setStatus(request.getStatus());
            target.setName(request.getName());
            return null;
        }).when(mapper).updateEntity(entity, update);
        lenient().when(repository.save(any(ImportInsurancePolicy.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Throwable thrown = catchThrowable(() -> service.update(id, update));

        assertAll(
            () -> assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only DRAFT insurance policy can be edited"),
            () -> assertThat(entity.getStatus()).isEqualTo(persistedStatus.name()),
            () -> assertThat(entity.getName()).isEqualTo("Original insurance policy"),
            () -> verifyNoInteractions(mapper),
            () -> verify(repository, never()).save(any(ImportInsurancePolicy.class))
        );
    }
}
