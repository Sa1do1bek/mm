package com.enterprise.mm.warehouse.installation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.warehouse.installation.InstallationCommands.AcceptInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizeEquipmentCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.DispatchInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationPositionStatus;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SourceAssetValidationTest {

    private FixedAssetRepository fixedAssetRepository;
    private DepartmentRepository departmentRepository;
    private EmployeeRepository employeeRepository;
    private SourceAssetValidationService validationService;

    private final String tenantId = "test-tenant";
    private final UUID companyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        TenantContext.setTenant(tenantId);
        CompanyContext.setCompany(companyId);

        fixedAssetRepository = mock(FixedAssetRepository.class);
        departmentRepository = mock(DepartmentRepository.class);
        employeeRepository = mock(EmployeeRepository.class);

        when(fixedAssetRepository.save(any(FixedAsset.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        validationService = new SourceAssetValidationService(
            fixedAssetRepository,
            departmentRepository,
            employeeRepository
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
        CompanyContext.clear();
    }

    @Test
    @DisplayName("P5 AT04: Scoped binding and duplicate inventory number rejection")
    void p5_at04_scopedBindingAndDuplicateInventoryNumberRejection() {
        UUID assetId1 = UUID.randomUUID();
        FixedAsset asset1 = new FixedAsset();
        asset1.setId(assetId1);
        asset1.setTenantId(tenantId);
        asset1.setCompanyId(companyId);
        asset1.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);

        when(fixedAssetRepository.findById(assetId1)).thenReturn(Optional.of(asset1));

        // 1. Existing asset with same inventory number
        FixedAsset otherAsset = new FixedAsset();
        otherAsset.setId(UUID.randomUUID());
        otherAsset.setInventoryNumber("INV-9999");
        when(fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(
            tenantId, companyId, "INV-9999"
        )).thenReturn(Optional.of(otherAsset));

        assertThatThrownBy(() -> validationService.validateAssetCardForBinding(
            assetId1, "INV-9999", "SN-001", null, null
        )).isInstanceOf(ConflictException.class)
          .hasMessageContaining("Inventory number already assigned to another active fixed asset");

        // 2. Reject cross-tenant scope
        FixedAsset foreignAsset = new FixedAsset();
        foreignAsset.setId(UUID.randomUUID());
        foreignAsset.setTenantId("foreign-tenant");
        foreignAsset.setCompanyId(companyId);
        when(fixedAssetRepository.findById(foreignAsset.getId())).thenReturn(Optional.of(foreignAsset));

        assertThatThrownBy(() -> validationService.validateAssetCardForBinding(
            foreignAsset.getId(), "INV-NEW", "SN-NEW", null, null
        )).isInstanceOf(BadRequestException.class)
          .hasMessageContaining("Asset belongs to another tenant scope");
    }

    @Test
    @DisplayName("P5 AT05: Department/MOL custody mapping and historical/disposed exclusions")
    void p5_at05_departmentCustodyMappingAndDisposedExclusion() {
        UUID disposedAssetId = UUID.randomUUID();
        FixedAsset disposedAsset = new FixedAsset();
        disposedAsset.setId(disposedAssetId);
        disposedAsset.setTenantId(tenantId);
        disposedAsset.setCompanyId(companyId);
        disposedAsset.setLifecycleStatus(FixedAssetLifecycleStatus.DISPOSED);

        when(fixedAssetRepository.findById(disposedAssetId)).thenReturn(Optional.of(disposedAsset));

        // 1. Disposed asset cannot be bound or operated on
        assertThatThrownBy(() -> validationService.bindAssetToDepartmentAndCustodian(
            disposedAssetId, UUID.randomUUID(), UUID.randomUUID(), "Shop Floor 1"
        )).isInstanceOf(BadRequestException.class)
          .hasMessageContaining("Cannot bind or operate on disposed or cancelled fixed asset");

        // 2. Disposed source card cannot create active operational asset
        assertThatThrownBy(() -> validationService.validateSourceCardExclusion("DISPOSED", true))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("An imported card with disposed/historical status cannot create an active asset");

        // 3. Successful department and MOL custody assignment
        UUID activeAssetId = UUID.randomUUID();
        FixedAsset activeAsset = new FixedAsset();
        activeAsset.setId(activeAssetId);
        activeAsset.setTenantId(tenantId);
        activeAsset.setCompanyId(companyId);
        activeAsset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);

        when(fixedAssetRepository.findById(activeAssetId)).thenReturn(Optional.of(activeAsset));

        UUID deptId = UUID.randomUUID();
        UUID custodianId = UUID.randomUUID();
        when(departmentRepository.existsById(deptId)).thenReturn(true);
        when(employeeRepository.existsById(custodianId)).thenReturn(true);

        FixedAsset bound = validationService.bindAssetToDepartmentAndCustodian(
            activeAssetId, deptId, custodianId, "Workshop #5"
        );

        assertThat(bound.getDepartmentId()).isEqualTo(deptId);
        assertThat(bound.getCustodianEmployeeId()).isEqualTo(custodianId);
        assertThat(bound.getOperatingLocation()).isEqualTo("Workshop #5");
        assertThat(bound.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
        assertThat(bound.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
    }

    @Test
    @DisplayName("E2E 04: End-to-end equipment dispatch, acceptance, capitalization and department binding")
    void e2e_04_fullAssetLifecycleFlow() {
        var positionRepository = mock(WarehouseInstallationPositionRepository.class);
        var postingService = mock(AccountAwareStockPostingService.class);
        var capitalizationRepository = mock(WarehouseInstallationCapitalizationRepository.class);

        when(positionRepository.save(any(WarehouseInstallationPosition.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        var installService = new InstallationEquipmentService(
            positionRepository, postingService, capitalizationRepository, fixedAssetRepository
        );

        UUID projectId = UUID.randomUUID();
        UUID materialId = UUID.randomUUID();
        UUID warehouseId = UUID.randomUUID();

        // Step 1: Dispatch equipment from warehouse to installation site
        when(positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialIdAndWarehouseId(
            tenantId, companyId.toString(), projectId, materialId, warehouseId
        )).thenReturn(Optional.empty());

        var pos = installService.dispatchEquipmentToProject(new DispatchInstallationCommand(
            "E2E-DISP-01", projectId, materialId, warehouseId,
            new BigDecimal("2.0000000"), "PCS", LocalDate.of(2026, 10, 20), "PO-REF-10"
        ));
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.DISPATCHED);

        // Step 2: Accept at project site
        when(positionRepository.findById(pos.getId())).thenReturn(Optional.of(pos));
        var accepted = installService.acceptEquipmentAtProject(new AcceptInstallationCommand(
            "E2E-ACC-01", pos.getId(), new BigDecimal("2.0000000"), LocalDate.of(2026, 10, 21)
        ));
        assertThat(accepted.getStatus()).isEqualTo(InstallationPositionStatus.ACCEPTED);

        // Step 3: Capitalize into FixedAsset card
        UUID assetId = UUID.randomUUID();
        FixedAsset asset = new FixedAsset();
        asset.setId(assetId);
        asset.setTenantId(tenantId);
        asset.setCompanyId(companyId);
        asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_WAREHOUSE);
        when(fixedAssetRepository.findById(assetId)).thenReturn(Optional.of(asset));

        var capResult = installService.capitalizeInstallationEquipment(new CapitalizeEquipmentCommand(
            "E2E-CAP-01", pos.getId(), new BigDecimal("2.0000000"),
            new BigDecimal("24000000.0000"), List.of(assetId),
            LocalDate.of(2026, 10, 22), "ACT-CAP-01"
        ));
        assertThat(capResult.capitalizedQuantity()).isEqualByComparingTo("2.0000000");
        assertThat(capResult.remainingQuantity()).isEqualByComparingTo("0");
        assertThat(pos.getStatus()).isEqualTo(InstallationPositionStatus.FULLY_CAPITALIZED);

        // Step 4: Bind to Department and Custodian MOL
        UUID deptId = UUID.randomUUID();
        UUID molId = UUID.randomUUID();
        when(departmentRepository.existsById(deptId)).thenReturn(true);
        when(employeeRepository.existsById(molId)).thenReturn(true);

        FixedAsset commissioned = validationService.bindAssetToDepartmentAndCustodian(
            assetId, deptId, molId, "Chemical Plant Section 3"
        );

        assertThat(commissioned.getLifecycleStatus()).isEqualTo(FixedAssetLifecycleStatus.IN_USE);
        assertThat(commissioned.getCustodyKind()).isEqualTo(FixedAssetCustodyKind.DEPARTMENT);
        assertThat(commissioned.getDepartmentId()).isEqualTo(deptId);
        assertThat(commissioned.getCustodianEmployeeId()).isEqualTo(molId);
        assertThat(commissioned.getAcquisitionValue()).isEqualByComparingTo("12000000.0000");
    }
}
