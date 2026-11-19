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
import com.enterprise.mm.entity.CustomsDeclaration;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.enums.CustomsDeclarationStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.CustomsDeclarationMapper;
import com.enterprise.mm.payload.CustomsDeclarationUpdateRequest;
import com.enterprise.mm.repository.CustomsDeclarationRepository;
import com.enterprise.mm.repository.ImportDeliveryRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
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
class CustomsDeclarationLifecycleHardeningTest {
    @Mock private CustomsDeclarationRepository repository;
    @Mock private CustomsDeclarationMapper mapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private ImportDeliveryRepository importDeliveryRepository;
    @Mock private ImportDeliveryService importDeliveryService;
    @Mock private TnvedCodeRepository tnvedCodeRepository;
    @Mock private IntegrationEventService integrationEventService;
    @Mock private AuditService auditService;
    @InjectMocks private CustomsDeclarationService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = CustomsDeclarationStatus.class, names = {"RELEASED", "CANCELLED"})
    void updateRejectsTerminalDeclarationBeforeMapperCanMutate(CustomsDeclarationStatus persistedStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        CustomsDeclaration entity = new CustomsDeclaration();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(persistedStatus.name());
        entity.setName("Original customs declaration");
        Supplier supplier = new Supplier();
        supplier.setId(UUID.randomUUID());
        entity.setSupplier(supplier);

        CustomsDeclarationUpdateRequest update = new CustomsDeclarationUpdateRequest();
        update.setStatus(CustomsDeclarationStatus.DRAFT.name());
        update.setName("Mutated customs declaration");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            CustomsDeclaration target = invocation.getArgument(0);
            CustomsDeclarationUpdateRequest request = invocation.getArgument(1);
            target.setStatus(request.getStatus());
            target.setName(request.getName());
            return null;
        }).when(mapper).updateEntity(entity, update);
        lenient().when(repository.save(any(CustomsDeclaration.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Throwable thrown = catchThrowable(() -> service.update(id, update));

        assertAll(
            () -> assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Released or cancelled customs declaration cannot be edited"),
            () -> assertThat(entity.getStatus()).isEqualTo(persistedStatus.name()),
            () -> assertThat(entity.getName()).isEqualTo("Original customs declaration"),
            () -> verifyNoInteractions(mapper),
            () -> verify(repository, never()).save(any(CustomsDeclaration.class))
        );
    }
}
