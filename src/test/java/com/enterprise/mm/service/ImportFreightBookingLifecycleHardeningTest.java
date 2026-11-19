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
import com.enterprise.mm.entity.ImportFreightBooking;
import com.enterprise.mm.enums.ImportFreightStatus;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.mapper.ImportFreightBookingMapper;
import com.enterprise.mm.payload.ImportFreightBookingUpdateRequest;
import com.enterprise.mm.repository.ImportFreightBookingRepository;
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
class ImportFreightBookingLifecycleHardeningTest {
    @Mock private ImportFreightBookingRepository repository;
    @Mock private ImportFreightBookingMapper mapper;
    @Mock private SupplyLogisticsGuard guard;
    @Mock private LandedCostComponentService landedCostComponentService;
    @Mock private AuditService auditService;
    @InjectMocks private ImportFreightBookingService service;

    @AfterEach
    void clearContext() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @ParameterizedTest
    @EnumSource(value = ImportFreightStatus.class, names = {"IN_TRANSIT", "DELIVERED", "POSTED", "CANCELLED"})
    void updateRejectsNonEditableBookingBeforeMapperCanMutate(ImportFreightStatus persistedStatus) {
        TenantContext.setTenant("default");
        CompanyContext.setCompany(CompanyContext.DEFAULT_COMPANY_ID);
        UUID id = UUID.randomUUID();
        ImportFreightBooking entity = new ImportFreightBooking();
        entity.setId(id);
        entity.setTenantId("default");
        entity.setCompanyId(CompanyContext.DEFAULT_COMPANY_ID);
        entity.setStatus(persistedStatus.name());
        entity.setName("Original freight booking");
        entity.setImportDelivery(new ImportDelivery());

        ImportFreightBookingUpdateRequest update = new ImportFreightBookingUpdateRequest();
        update.setStatus(ImportFreightStatus.DRAFT.name());
        update.setName("Mutated freight booking");

        when(repository.findByIdAndIsDeletedFalse(id)).thenReturn(Optional.of(entity));
        lenient().doAnswer(invocation -> {
            ImportFreightBooking target = invocation.getArgument(0);
            ImportFreightBookingUpdateRequest request = invocation.getArgument(1);
            target.setStatus(request.getStatus());
            target.setName(request.getName());
            return null;
        }).when(mapper).updateEntity(entity, update);
        lenient().when(repository.save(any(ImportFreightBooking.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        Throwable thrown = catchThrowable(() -> service.update(id, update));

        assertAll(
            () -> assertThat(thrown)
                .isInstanceOf(BusinessException.class)
                .hasMessage("Only DRAFT or BOOKED freight booking can be edited"),
            () -> assertThat(entity.getStatus()).isEqualTo(persistedStatus.name()),
            () -> assertThat(entity.getName()).isEqualTo("Original freight booking"),
            () -> verifyNoInteractions(mapper),
            () -> verify(repository, never()).save(any(ImportFreightBooking.class))
        );
    }
}
