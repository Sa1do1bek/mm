package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.UnitOfMeasure;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.mapper.UnitOfMeasureMapper;
import com.enterprise.mm.payload.UnitOfMeasureCreateRequest;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UnitOfMeasureScopeTest {
    @Mock private UnitOfMeasureRepository repository;
    @Mock private AuditService auditService;
    @Mock private UnitTypeLabelResolver unitTypeLabelResolver;

    private UnitOfMeasureService service;
    private UUID companyId;

    @BeforeEach
    void setUp() {
        TenantContext.setTenant("uom-scope-test");
        companyId = UUID.randomUUID();
        CompanyContext.setCompany(companyId);
        service = new UnitOfMeasureService(repository, new UnitOfMeasureMapper(unitTypeLabelResolver), auditService);
        lenient().when(repository.save(any())).thenAnswer(invocation -> {
            UnitOfMeasure entity = invocation.getArgument(0);
            if (entity.getId() == null) entity.setId(UUID.randomUUID());
            return entity;
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        com.enterprise.mm.security.AccessScope.clearRequestedCompanyScope();
    }

    @Test
    void createWritesCurrentTenantAndCompanyAndUsesScopedUniqueness() {
        UnitOfMeasureCreateRequest request = new UnitOfMeasureCreateRequest();
        request.setCode("kg");
        request.setName("Kilogram");

        var created = service.create(request, Locale.ENGLISH);

        assertThat(created.getTenantId()).isEqualTo(TenantContext.currentTenant());
        assertThat(created.getCompanyId()).isEqualTo(companyId);
        verify(repository).existsByScopedLookup(TenantContext.currentTenant(), companyId, "kg");
    }

    @Test
    void restoreRejectsOtherTenantBeforeMutation() {
        UnitOfMeasure otherTenant = new UnitOfMeasure();
        otherTenant.setId(UUID.randomUUID());
        otherTenant.setTenantId("other-tenant");
        otherTenant.setCompanyId(companyId);
        otherTenant.setDeleted(true);
        when(repository.findAnyById(otherTenant.getId())).thenReturn(Optional.of(otherTenant));

        assertThatThrownBy(() -> service.restore(otherTenant.getId(), Locale.ENGLISH))
            .isInstanceOf(NotFoundException.class);
        verify(repository, never()).save(otherTenant);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void searchRequiresBothTenantAndCompanyEvenForGlobalAdmin(boolean globalAdmin) {
        var authentication = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
            "admin", null, java.util.List.of());
        authentication.setDetails(new com.enterprise.mm.security.AccessScope.RequestScope(globalAdmin));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(authentication);
        when(repository.search(
            TenantContext.currentTenant(),
            companyId,
            false,
            "kg",
            null,
            null,
            PageRequest.of(0, 20)
        )).thenReturn(new PageImpl<>(java.util.List.of()));

        service.search(
            new com.enterprise.mm.payload.UnitOfMeasureSearchRequest("kg", null, null, false),
            PageRequest.of(0, 20),
            Locale.ENGLISH
        );

        verify(repository).search(
            TenantContext.currentTenant(),
            companyId,
            false,
            "kg",
            null,
            null,
            PageRequest.of(0, 20)
        );
    }

    @Test
    void createExposesConfiguredAndEffectivePrecisionAndRejectsInvalidScale() {
        var request = new UnitOfMeasureCreateRequest(); request.setCode("EA"); request.setName("Piece");
        request.setStockQuantityScale(7);
        var created = service.create(request, Locale.ENGLISH);
        assertThat(created.getStockQuantityScale()).isEqualTo(7);
        assertThat(created.getQuantityScale()).isEqualTo(7);
        request.setStockQuantityScale(8);
        assertThatThrownBy(()->service.create(request,Locale.ENGLISH)).hasMessageContaining("between 0 and 7");
    }

    @Test
    void usedStockPrecisionCannotBeReducedAndOmittedOverrideIsPreserved() {
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setTenantId(TenantContext.currentTenant());
        unit.setCompanyId(companyId); unit.setCode("EA"); unit.setName("Piece"); unit.setStockQuantityScale(7);
        when(repository.findByIdAndIsDeletedFalse(unit.getId())).thenReturn(Optional.of(unit));
        var update = new com.enterprise.mm.payload.UnitOfMeasureUpdateRequest(); update.setCode("EA"); update.setName("Piece");
        assertThat(service.update(unit.getId(),update,Locale.ENGLISH).getStockQuantityScale()).isEqualTo(7);
        when(repository.hasStockUsage(unit.getTenantId(),unit.getId())).thenReturn(true);
        update.setStockQuantityScale(4);
        assertThatThrownBy(()->service.update(unit.getId(),update,Locale.ENGLISH)).hasMessageContaining("cannot be reduced");
    }

    @Test
    void anUnusedUnitMayReduceItsPrecision() {
        var unit = new UnitOfMeasure(); unit.setId(UUID.randomUUID()); unit.setTenantId(TenantContext.currentTenant());
        unit.setCompanyId(companyId); unit.setCode("KG"); unit.setName("Kilogram"); unit.setStockQuantityScale(7);
        when(repository.findByIdAndIsDeletedFalse(unit.getId())).thenReturn(Optional.of(unit));
        var update = new com.enterprise.mm.payload.UnitOfMeasureUpdateRequest(); update.setCode("KG"); update.setName("Kilogram"); update.setStockQuantityScale(4);
        assertThat(service.update(unit.getId(),update,Locale.ENGLISH).getQuantityScale()).isEqualTo(4);
        verify(repository).hasStockUsage(unit.getTenantId(),unit.getId());
    }



}
