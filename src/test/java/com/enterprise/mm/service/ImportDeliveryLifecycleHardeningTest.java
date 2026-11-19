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
import com.enterprise.mm.enums.ImportDeliveryStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ImportDeliveryMapper;
import com.enterprise.mm.payload.ImportDeliveryUpdateRequest;
import com.enterprise.mm.repository.CustomsDeclarationRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.LandedCostComponentRepository;
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
class ImportDeliveryLifecycleHardeningTest {
    @Mock private ImportDeliveryRepository repository;
    @Mock private ImportDeliveryMapper mapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private LandedCostComponentRepository landedCostComponentRepository;
    @Mock private CustomsDeclarationRepository customsDeclarationRepository;
    @Mock private AuditService auditService;
    @InjectMocks private ImportDeliveryService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = ImportDeliveryStatus.class, names = {"CLOSED", "CANCELLED"})
    void updateRejectsTerminalDeliveryBeforeMapperCanMutate(ImportDeliveryStatus persistedStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        ImportDelivery entity = new ImportDelivery();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(persistedStatus.name());
        entity.setName("Original delivery");

        ImportDeliveryUpdateRequest update = new ImportDeliveryUpdateRequest();
        update.setStatus(ImportDeliveryStatus.PLANNED.name());
        update.setName("Mutated delivery");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            ImportDelivery target = invocation.getArgument(0);
            ImportDeliveryUpdateRequest request = invocation.getArgument(1);
            target.setStatus(request.getStatus());
            target.setName(request.getName());
            return null;
        }).when(mapper).updateEntity(entity, update);
        lenient().when(repository.save(any(ImportDelivery.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Throwable thrown = catchThrowable(() -> service.update(id, update));

        assertAll(
            () -> assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Closed or cancelled import delivery cannot be edited"),
            () -> assertThat(entity.getStatus()).isEqualTo(persistedStatus.name()),
            () -> assertThat(entity.getName()).isEqualTo("Original delivery"),
            () -> verifyNoInteractions(mapper),
            () -> verify(repository, never()).save(any(ImportDelivery.class))
        );
    }
}
