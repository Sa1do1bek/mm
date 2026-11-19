package com.enterprise.mm.warehouse.transfergroup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.RoleDataScopeService.DataScope;
import com.enterprise.mm.service.TransferDocumentService;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class TransferGroupReadServiceTest {
    final String tenant = "group-read";
    final UUID company = UUID.randomUUID();
    final UUID source = UUID.randomUUID();
    WarehouseTransferGroupRepository groups = mock(WarehouseTransferGroupRepository.class);
    WarehouseTransferGroupItemRepository items = mock(WarehouseTransferGroupItemRepository.class);
    TransferDocumentLineRepository lines = mock(TransferDocumentLineRepository.class);
    WarehouseRepository warehouses = mock(WarehouseRepository.class);
    RoleDataScopeService scopes = mock(RoleDataScopeService.class);
    WarehouseTransferGroupService service = new WarehouseTransferGroupService(groups, items,
        mock(TransferDocumentRepository.class), lines, mock(TransferDocumentService.class), scopes, warehouses);

    @BeforeEach void setup() {
        TenantContext.setTenant(tenant);
        CompanyContext.setCompany(company);
        when(scopes.currentScope()).thenReturn(DataScope.unrestricted());
        when(groups.findOptions(anyString(), any(), nullable(UUID.class), nullable(UUID.class),
            nullable(String.class), any())).thenAnswer(inv -> Page.empty(inv.getArgument(5)));
    }

    @AfterEach void cleanup() {
        TenantContext.clear();
        CompanyContext.clear();
        LocaleContextHolder.resetLocaleContext();
    }

    @Test void normalizesSearchAndEscapesWildcardsAndEscapeCharacter() {
        service.options("  Ab!_%  ", null, 0, 20);
        verify(groups).findOptions(eq(tenant), eq(company), isNull(), isNull(), eq("%ab!!!_!%%"), any());
        verifyNoInteractions(items, lines);
    }

    @Test void blankSearchIsUnfilteredAndSortHasDescendingIdTieBreaker() {
        service.options("   ", null, 2, 20);
        var pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(groups).findOptions(eq(tenant), eq(company), isNull(), isNull(), isNull(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getSort().toString()).isEqualTo("createdAt: DESC,id: DESC");
    }

    @ParameterizedTest @CsvSource({"-1,20", "0,0", "0,101"})
    void rejectsInvalidPaginationBeforeQuery(int page, int size) {
        assertThatThrownBy(() -> service.options(null, null, page, size)).isInstanceOf(BadRequestException.class);
        verifyNoInteractions(groups);
    }

    @Test void validatesTrimmedSearchLengthIncludingUnicode() {
        assertThatThrownBy(() -> service.options("x".repeat(201), null, 0, 20))
            .isInstanceOf(BadRequestException.class);
        service.options(" " + "x".repeat(200) + " ", null, 0, 20);
        service.options("😀".repeat(200), null, 0, 100);
        verify(groups, times(2)).findOptions(anyString(), any(), isNull(), isNull(), anyString(), any());
    }

    @Test void sourceWarehouseScopeIsPassedIntoDatabaseQuery() {
        when(scopes.currentScope()).thenReturn(warehouseScope(source));
        service.options(null, null, 0, 20);
        verify(groups).findOptions(eq(tenant), eq(company), eq(source), isNull(), isNull(), any());
    }

    @Test void warehouseActorWithoutAssignmentIsDeniedRatherThanMadeUnrestricted() {
        when(scopes.currentScope()).thenReturn(DataScope.restricted(false, true));
        assertThatThrownBy(() -> service.options(null, null, 0, 20)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(groups);
    }

    @Test void requestedSourceMustExistInCurrentTenantCompany() {
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenant, company, source))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.options(null, source, 0, 20)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(groups);
    }

    @Test void unauthorizedSourceIsDeniedBeforeQueryOrWarehouseLookup() {
        doThrow(new NotFoundException("outside scope")).when(scopes).assertWarehouseAccess(source);
        assertThatThrownBy(() -> service.options(null, source, 0, 20)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(groups, warehouses);
    }

    @Test void requestedSourceNarrowsScopeAndDoesNotReplaceIt() {
        when(scopes.currentScope()).thenReturn(warehouseScope(source));
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenant, company, source))
            .thenReturn(Optional.of(new Warehouse()));
        service.options(null, source, 0, 20);
        verify(groups).findOptions(eq(tenant), eq(company), eq(source), eq(source), isNull(), any());
    }

    @Test void localizesOptionWithoutReadingChildrenOrProgress() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("uz"));
        UUID id = UUID.randomUUID();
        doReturn(new PageImpl<>(List.of(new TransferGroupOptionResponse(id, "WTG-42",
            "ru: Реактивы\nen: Reagents\nuz: Reagentlar"))))
            .when(groups).findOptions(anyString(), any(), isNull(), isNull(), isNull(), any());
        assertThat(service.options(null, null, 0, 20).getContent())
            .containsExactly(new TransferGroupOptionResponse(id, "WTG-42", "Reagentlar"));
        verifyNoInteractions(items, lines);
    }

    @Test void legacyBlankLabelsHaveStableReadOnlyFallbacks() {
        LocaleContextHolder.setLocale(Locale.forLanguageTag("uz"));
        UUID id = UUID.randomUUID();
        doReturn(new PageImpl<>(List.of(new TransferGroupOptionResponse(id, " ", null))))
            .when(groups).findOptions(anyString(), any(), isNull(), isNull(), isNull(), any());
        assertThat(service.options(null, null, 0, 20).getContent())
            .containsExactly(new TransferGroupOptionResponse(id, "WTG-" + id, "Ko‘chirish guruhi"));
        verify(groups, never()).save(any());
    }

    @Test void nonexistentGroupProgressReturns404BeforeReadingItems() {
        assertThatThrownBy(() -> service.getProgress(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(items, lines);
    }

    @Test void deniedSourceCannotReadDetailOrWholeGroupProgressEvenWhenItIsADestination() {
        var group = group();
        doThrow(new NotFoundException("outside scope")).when(scopes).assertWarehouseAccess(source);
        assertThatThrownBy(() -> service.getGroup(group.getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.getProgress(group.getId())).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(items, lines);
    }

    @Test void authorizedEmptyGroupStillReturnsZeroProgressAndDetail() {
        var group = group();
        when(items.findByTenantIdAndCompanyIdAndGroupIdAndIsDeletedFalse(tenant, company, group.getId()))
            .thenReturn(List.of());
        assertThat(service.getProgress(group.getId())).isEqualTo(TransferGroupAggregateProgress.zero());
        assertThat(service.getGroup(group.getId()).id()).isEqualTo(group.getId());
        verify(scopes, times(2)).assertWarehouseAccess(source);
    }

    private WarehouseTransferGroup group() {
        var group = new WarehouseTransferGroup();
        group.setId(UUID.randomUUID());
        group.setSourceWarehouseId(source);
        when(groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(tenant, company, group.getId()))
            .thenReturn(Optional.of(group));
        return group;
    }

    private DataScope warehouseScope(UUID id) {
        return new DataScope(false, false, true, Set.of(), Set.of(), Set.of(), null, id);
    }
}
