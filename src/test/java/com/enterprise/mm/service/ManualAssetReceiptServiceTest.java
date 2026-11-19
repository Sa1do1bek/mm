package com.enterprise.mm.service;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.*;
import com.enterprise.mm.payload.*;
import com.enterprise.mm.repository.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ManualAssetReceiptServiceTest {
    @Mock RoleDataScopeService scope;
    @Mock WarehouseRepository warehouses;
    @Mock MaterialCatalogGuard catalog;
    @Mock AssetManagementSettingsService settings;
    @Mock AssetAcceptanceRepository acceptances;
    @Mock AssetAcceptanceLineRepository lines;
    @Mock AssetAcceptanceUnitRepository units;
    @Mock AssetAcceptanceService workflow;
    @Mock IdempotencyService idempotency;
    @Mock ExactOpeningImportGuard openingGuard;
    @Mock ContractRepository contracts;
    @Mock ContractSpecificationRepository contractSpecifications;
    @InjectMocks ManualAssetReceiptService service;
    UUID company = UUID.randomUUID(), warehouse = UUID.randomUUID(), materialId = UUID.randomUUID();
    UUID contractId = UUID.randomUUID(), specificationId = UUID.randomUUID();
    AssetManagementSettings policy;
    @BeforeEach void setup() {
        TenantContext.setTenant("manual-assets"); CompanyContext.setCompany(company);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "fixedAssetsEnabled", true);
        Warehouse w = new Warehouse(); w.setCompanyId(company);
        lenient().when(warehouses.findByTenantIdAndIdAndIsDeletedFalse("manual-assets", warehouse)).thenReturn(Optional.of(w));
        Material material = new Material(); material.setId(materialId); material.setMaterialType(MaterialType.FIXED_ASSET); material.setName("Pump");
        lenient().when(catalog.requireActiveMaterial(materialId, "materialId")).thenReturn(material);
        Contract contract = new Contract(); contract.setId(contractId); contract.setTenantId("manual-assets"); contract.setCompanyId(company); contract.setStatus("ACTIVE");
        lenient().when(contracts.findByIdAndIsDeletedFalse(contractId)).thenReturn(Optional.of(contract));
        ContractSpecification specification = new ContractSpecification(); specification.setId(specificationId);
        specification.setContractId(contractId); specification.setTenantId("manual-assets"); specification.setCompanyId(company);
        specification.setStatus("ACTIVE"); specification.setLineType(ProcurementLineType.MATERIAL); specification.setMaterialId(materialId);
        lenient().when(contractSpecifications.findByIdAndIsDeletedFalse(specificationId)).thenReturn(Optional.of(specification));
        policy = new AssetManagementSettings();
        lenient().when(settings.requireConfigured()).thenReturn(policy);
        lenient().when(idempotency.executeRequired(anyString(), any(), anyString(), any(), eq(AssetAcceptanceResponse.class), any()))
            .thenAnswer(inv -> ((Supplier<?>) inv.getArgument(5)).get());
        lenient().when(acceptances.save(any())).thenAnswer(inv -> { AssetAcceptance a = inv.getArgument(0); a.setId(UUID.randomUUID()); return a; });
        lenient().when(lines.save(any())).thenAnswer(inv -> { AssetAcceptanceLine l = inv.getArgument(0); l.setId(UUID.randomUUID()); return l; });
    }
    @AfterEach void clear() { TenantContext.clear(); CompanyContext.clear(); }
    ManualWarehouseOperationRequest request(String quantity, List<String> serials) {
        return new ManualWarehouseOperationRequest(warehouse, materialId, new BigDecimal(quantity), new BigDecimal("1200"), null,
            LocalDate.now(), "Received from workshop", "ACT-12", "UZS", serials, null, contractId, specificationId);
    }
    @Test void draftAndGroupSpecificationsCannotBeUsedForEquipmentAcceptance() {
        ContractSpecification spec = contractSpecifications.findByIdAndIsDeletedFalse(specificationId).orElseThrow();
        spec.setStatus("DRAFT");
        assertThatThrownBy(() -> service.create(request("1", null), "draft")).isInstanceOf(BusinessException.class).hasMessageContaining("ACTIVE");
        spec.setStatus("ACTIVE"); spec.setMaterialGroupId(UUID.randomUUID()); spec.setMaterialId(null);
        assertThatThrownBy(() -> service.create(request("1", null), "group")).isInstanceOf(BusinessException.class);
        verifyNoInteractions(acceptances);
    }
    @Test void createsDraftEvidenceAndPhysicalUnitsWithoutProcurementOrPosting() {
        service.create(request("2", List.of("P-1", "P-2")), "key");
        verify(acceptances).save(argThat(a -> a.getPurchaseOrderId() == null && a.getWarehouseId().equals(warehouse)
            && a.getContractId().equals(contractId)
            && a.getApprovalStatus() == AssetAcceptanceApprovalStatus.DRAFT && a.getPostingStatus() == AssetAcceptancePostingStatus.NOT_POSTED));
        verify(lines).save(argThat(l -> l.getPurchaseOrderItemId() == null && l.getAcceptedValue().compareTo(new BigDecimal("2400")) == 0));
        verify(units, times(2)).save(argThat(u -> u.getPurchaseOrderItemId() == null && u.getQualityDecision() == AssetAcceptanceUnitQualityDecision.PENDING));
        verify(workflow).get(any());
        verify(workflow, never()).post(any(), any());
    }
    @Test void rejectsMissingContractBeforeSaving() {
        ManualWarehouseOperationRequest missing = new ManualWarehouseOperationRequest(warehouse, materialId,
            BigDecimal.ONE, BigDecimal.ONE, null, LocalDate.now(), null, null, "UZS", null, null);
        assertThatThrownBy(() -> service.create(missing, "key")).hasMessageContaining("Contract is required");
        verifyNoInteractions(acceptances);
    }
    @Test void requiresAssignedWarehouseBeforeIdempotentReplay() {
        doThrow(new NotFoundException("outside warehouse")).when(scope).assertWarehouseAccess(warehouse);
        assertThatThrownBy(() -> service.create(request("1", null), "key")).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(idempotency, acceptances);
    }
    @Test void rejectsFractionalEquipmentAndDuplicateSerialsWithoutSaving() {
        assertThatThrownBy(() -> service.create(request("1.5", null), "key")).hasMessageContaining("whole");
        assertThatThrownBy(() -> service.create(request("2", List.of("P-1", "p-1")), "key2")).hasMessageContaining("unique");
        verifyNoInteractions(acceptances);
    }
    @Test void respectsConfiguredSerialNumberRequirement() {
        policy.setRequireSerialNumber(true);
        assertThatThrownBy(() -> service.create(request("1", null), "key")).hasMessageContaining("serial number");
        verifyNoInteractions(acceptances);
    }
    @Test void providedSerialNumbersDoNotBypassAssetSettings() {
        when(settings.requireConfigured()).thenThrow(new BusinessException("Asset settings are not configured"));
        assertThatThrownBy(() -> service.create(request("1", List.of("P-1")), "key")).hasMessageContaining("not configured");
        verifyNoInteractions(acceptances);
    }
}
