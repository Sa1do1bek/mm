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
import com.enterprise.mm.entity.LandedCostComponent;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.LandedCostComponentMapper;
import com.enterprise.mm.payload.LandedCostComponentResponse;
import com.enterprise.mm.payload.LandedCostComponentUpdateRequest;
import com.enterprise.mm.repository.LandedCostComponentRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LandedCostComponentLifecycleHardeningTest {
    @Mock private LandedCostComponentRepository repository;
    @Mock private LandedCostComponentMapper mapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private ImportDeliveryService importDeliveryService;
    @Mock private AuditService auditService;
    @InjectMocks private LandedCostComponentService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = "POSTED")
    void updateRejectsPostedComponentBeforeMapperCanMutate(String persistedStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        LandedCostComponent entity = new LandedCostComponent();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(persistedStatus);
        entity.setName("Original landed cost component");
        entity.setImportDeliveryId(UUID.randomUUID());

        LandedCostComponentUpdateRequest update = new LandedCostComponentUpdateRequest();
        update.setStatus("DRAFT");
        update.setName("Mutated landed cost component");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            LandedCostComponent target = invocation.getArgument(0);
            LandedCostComponentUpdateRequest request = invocation.getArgument(1);
            target.setStatus(request.getStatus());
            target.setName(request.getName());
            return null;
        }).when(mapper).updateEntity(entity, update);
        lenient().when(repository.save(any(LandedCostComponent.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        LandedCostComponentResponse response = new LandedCostComponentResponse();
        response.setImportDeliveryId(entity.getImportDeliveryId());
        lenient().when(mapper.toResponse(any(LandedCostComponent.class))).thenReturn(response);

        Throwable thrown = catchThrowable(() -> service.update(id, update));

        assertAll(
            () -> assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Posted landed cost component cannot be edited"),
            () -> assertThat(entity.getStatus()).isEqualTo(persistedStatus),
            () -> assertThat(entity.getName()).isEqualTo("Original landed cost component"),
            () -> verifyNoInteractions(mapper),
            () -> verify(repository, never()).save(any(LandedCostComponent.class))
        );
    }
}
