package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.MaterialGroup;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.ManualReceiptMaterialDtos;
import com.enterprise.mm.payload.MaterialCreateRequest;
import com.enterprise.mm.payload.MaterialResponse;
import com.enterprise.mm.repository.MaterialAttributeTemplateRepository;
import com.enterprise.mm.repository.MaterialGroupRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

@ExtendWith(MockitoExtension.class)
class ManualReceiptMaterialServiceTest {
    @Mock RoleDataScopeService scope;
    @Mock WarehouseRepository warehouses;
    @Mock MaterialGroupRepository groups;
    @Mock MaterialAttributeTemplateRepository templates;
    @Mock MaterialRepository materials;
    @Mock MXIKRepository mxiks;
    @Mock MaterialService materialService;
    @Mock IdempotencyService idempotency;
    @Mock PlatformTransactionManager transactionManager;

    UUID company = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    UUID groupId = UUID.randomUUID();
    UUID unitId = UUID.randomUUID();
    ManualReceiptMaterialService service;

    @BeforeEach
    void setup() {
        TenantContext.setTenant("receipt-material-test");
        CompanyContext.setCompany(company);
        service = new ManualReceiptMaterialService(scope, warehouses, groups, templates, materials, mxiks,
            materialService, idempotency, transactionManager);
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    void createsInventoryMaterialWithoutMxikUsingIdempotency() {
        accessibleWarehouse();
        transactionalCreate();
        MaterialGroup group = new MaterialGroup();
        group.setId(groupId);
        group.setStatus("ACTIVE");
        when(groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("receipt-material-test", company, groupId))
            .thenReturn(Optional.of(group));
        MaterialResponse saved = new MaterialResponse();
        saved.setId(UUID.randomUUID());
        when(materialService.create(any(MaterialCreateRequest.class))).thenReturn(saved);
        when(idempotency.executeRequired(eq("MANUAL_RECEIPT_MATERIAL_CREATE"), eq(warehouse), eq("retry-1"),
            any(), eq(MaterialResponse.class), any())).thenAnswer(invocation -> {
                Supplier<?> action = invocation.getArgument(5);
                return action.get();
            });

        assertThat(service.create(request(null, null), "retry-1")).isSameAs(saved);
        ArgumentCaptor<MaterialCreateRequest> payload = ArgumentCaptor.forClass(MaterialCreateRequest.class);
        verify(materialService).create(payload.capture());
        assertThat(payload.getValue().getMaterialType()).isEqualTo(MaterialType.TMB);
        assertThat(payload.getValue().getStatus()).isEqualTo(MaterialStatus.ACTIVE);
        assertThat(payload.getValue().getMxikId()).isNull();
        assertThat(payload.getValue().getMxikCode()).isNull();
        assertThat(payload.getValue().getCode()).isNull();
        verify(scope).assertWarehouseAccess(warehouse);
    }

    @Test
    void checksTheExactNormalizedCodeWithinCurrentCompany() {
        accessibleWarehouse();
        when(materials.existsActiveNormalizedCode("receipt-material-test", company, "ABC-7", null))
            .thenReturn(true);
        assertThat(service.codeAvailability(warehouse, " abc-7 "))
            .isEqualTo(new ManualReceiptMaterialDtos.CodeAvailability("ABC-7", "DUPLICATE"));
    }

    @Test
    void rejectsInconsistentMxikIdAndCodeBeforeCreating() {
        accessibleWarehouse();
        transactionalCreate();
        MaterialGroup group = new MaterialGroup();
        group.setId(groupId);
        group.setStatus("ACTIVE");
        when(groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("receipt-material-test", company, groupId))
            .thenReturn(Optional.of(group));
        MXIK mxik = new MXIK();
        mxik.setKod("12345");
        when(mxiks.findAnyById(42L)).thenReturn(Optional.of(mxik));
        when(idempotency.executeRequired(eq("MANUAL_RECEIPT_MATERIAL_CREATE"), eq(warehouse), eq("retry-2"),
            any(), eq(MaterialResponse.class), any())).thenAnswer(invocation -> {
                Supplier<?> action = invocation.getArgument(5);
                return action.get();
            });

        assertThatThrownBy(() -> service.create(request(42L, "99999"), "retry-2"))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("do not match");
        verify(materialService, never()).create(any());
    }

    @Test
    void rejectsWarehouseOutsideCurrentCompany() {
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("receipt-material-test", company, warehouse))
            .thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.context(warehouse, null)).isInstanceOf(NotFoundException.class);
        verify(materials, never()).existsActiveNormalizedCode(any(), any(), any(), any());
    }

    @Test
    void retriesGeneratedCodeCollisionInANewTransactionWithTheSameIdempotencyKey() {
        accessibleWarehouse();
        transactionalCreate();
        activeGroup();
        MaterialResponse saved = new MaterialResponse();
        saved.setId(UUID.randomUUID());
        when(materialService.create(any(MaterialCreateRequest.class))).thenReturn(saved);
        executeIdempotently("retry-collision");
        org.mockito.Mockito.doThrow(new DataIntegrityViolationException(
                "duplicate key violates unique constraint uk_materials_active_normalized_company_code"))
            .doNothing().when(transactionManager).commit(any());

        assertThat(service.create(request(null, null), "retry-collision")).isSameAs(saved);
        verify(materialService, times(2)).create(any(MaterialCreateRequest.class));
        verify(transactionManager, times(2)).getTransaction(any());
        verify(idempotency, times(2)).executeRequired(eq("MANUAL_RECEIPT_MATERIAL_CREATE"), eq(warehouse),
            eq("retry-collision"), any(), eq(MaterialResponse.class), any());
    }

    @Test
    void retriesGeneratedCodeWhenAConcurrentSaveIsFoundDuringValidation() {
        accessibleWarehouse();
        transactionalCreate();
        activeGroup();
        MaterialResponse saved = new MaterialResponse();
        when(materialService.create(any(MaterialCreateRequest.class)))
            .thenThrow(new BusinessException("Duplicate material code", "material.code.duplicate"))
            .thenReturn(saved);
        executeIdempotently("retry-validation");

        assertThat(service.create(request(null, null), "retry-validation")).isSameAs(saved);
        verify(materialService, times(2)).create(any(MaterialCreateRequest.class));
        verify(transactionManager, times(2)).getTransaction(any());
    }

    @Test
    void doesNotRetryOtherUniqueConstraints() {
        accessibleWarehouse();
        transactionalCreate();
        activeGroup();
        when(materialService.create(any(MaterialCreateRequest.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key violates unique constraint uk_materials_barcode"));
        executeIdempotently("retry-other");

        assertThatThrownBy(() -> service.create(request(null, null), "retry-other"))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(materialService).create(any(MaterialCreateRequest.class));
    }

    @Test
    void stopsAfterFiveGeneratedCodeCollisions() {
        accessibleWarehouse();
        transactionalCreate();
        activeGroup();
        when(materialService.create(any(MaterialCreateRequest.class)))
            .thenThrow(new DataIntegrityViolationException("uk_materials_active_normalized_company_code"));
        executeIdempotently("retry-exhausted");

        assertThatThrownBy(() -> service.create(request(null, null), "retry-exhausted"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Unable to generate a unique material code");
        verify(materialService, times(5)).create(any(MaterialCreateRequest.class));
        verify(transactionManager, times(5)).getTransaction(any());
    }

    @Test
    void doesNotRetryManuallyEnteredCodeCollisions() {
        accessibleWarehouse();
        transactionalCreate();
        activeGroup();
        when(materialService.create(any(MaterialCreateRequest.class)))
            .thenThrow(new DataIntegrityViolationException("duplicate key violates unique constraint uk_materials_active_normalized_company_code"));
        executeIdempotently("retry-manual");
        var input = new ManualReceiptMaterialDtos.CreateRequest(warehouse, "Cable", groupId, unitId, "CABLE-1",
            null, null, null, null, null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> service.create(input, "retry-manual"))
            .isInstanceOf(DataIntegrityViolationException.class);
        verify(materialService).create(any(MaterialCreateRequest.class));
    }

    private void transactionalCreate() {
        when(transactionManager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
    }

    private void activeGroup() {
        MaterialGroup group = new MaterialGroup();
        group.setId(groupId);
        group.setStatus("ACTIVE");
        when(groups.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("receipt-material-test", company, groupId))
            .thenReturn(Optional.of(group));
    }

    private void executeIdempotently(String key) {
        when(idempotency.executeRequired(eq("MANUAL_RECEIPT_MATERIAL_CREATE"), eq(warehouse), eq(key),
            any(), eq(MaterialResponse.class), any())).thenAnswer(invocation -> {
                Supplier<?> action = invocation.getArgument(5);
                return action.get();
            });
    }

    private void accessibleWarehouse() {
        Warehouse record = new Warehouse();
        record.setId(warehouse);
        record.setCompanyId(company);
        when(warehouses.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse("receipt-material-test", company, warehouse))
            .thenReturn(Optional.of(record));
    }

    private ManualReceiptMaterialDtos.CreateRequest request(Long mxikId, String mxikCode) {
        return new ManualReceiptMaterialDtos.CreateRequest(warehouse, "Cable", groupId, unitId, null,
            mxikId, mxikCode, null, null, null, null, null, null, null, null, null, null);
    }
}
