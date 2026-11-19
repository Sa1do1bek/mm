package com.enterprise.mm.warehouse.installation;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.EmployeeRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SourceAssetValidationService {

    private final FixedAssetRepository fixedAssetRepository;
    private final DepartmentRepository departmentRepository;
    private final EmployeeRepository employeeRepository;

    public SourceAssetValidationService(
        FixedAssetRepository fixedAssetRepository,
        DepartmentRepository departmentRepository,
        EmployeeRepository employeeRepository
    ) {
        this.fixedAssetRepository = fixedAssetRepository;
        this.departmentRepository = departmentRepository;
        this.employeeRepository = employeeRepository;
    }

    @Autowired
    public SourceAssetValidationService(
        FixedAssetRepository fixedAssetRepository,
        @Autowired(required = false) DepartmentRepository departmentRepository
    ) {
        this(fixedAssetRepository, departmentRepository, null);
    }

    public SourceAssetValidationService(FixedAssetRepository fixedAssetRepository) {
        this(fixedAssetRepository, null, null);
    }

    @Transactional(readOnly = true)
    public void validateAssetCardForBinding(
        UUID assetId,
        String inventoryNumber,
        String serialNumber,
        UUID departmentId,
        UUID custodianEmployeeId
    ) {
        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        if (assetId != null) {
            FixedAsset asset = fixedAssetRepository.findById(assetId)
                .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + assetId));

            if (asset.getTenantId() != null && !tenantId.equals(asset.getTenantId())) {
                throw new BadRequestException("Asset belongs to another tenant scope: " + asset.getTenantId());
            }
            if (asset.getCompanyId() != null && !companyId.equals(asset.getCompanyId())) {
                throw new BadRequestException("Asset belongs to another company scope: " + asset.getCompanyId());
            }

            enforceDisposedExclusion(asset);
        }

        // Duplicate inventory number check within same tenant & company
        if (inventoryNumber != null && !inventoryNumber.isBlank()) {
            var existingOpt = fixedAssetRepository.findByTenantIdAndCompanyIdAndInventoryNumberAndIsDeletedFalse(
                tenantId, companyId, inventoryNumber
            );
            if (existingOpt.isPresent()) {
                FixedAsset existing = existingOpt.get();
                if (assetId == null || !existing.getId().equals(assetId)) {
                    throw new ConflictException(
                        "Inventory number already assigned to another active fixed asset: " + inventoryNumber
                    );
                }
            }
        }

        // Validate department reference if present
        if (departmentId != null && departmentRepository != null) {
            if (!departmentRepository.existsById(departmentId)) {
                throw new NotFoundException("Department not found: " + departmentId);
            }
        }

        // Validate custodian employee if present
        if (custodianEmployeeId != null && employeeRepository != null) {
            if (!employeeRepository.existsById(custodianEmployeeId)) {
                throw new NotFoundException("Custodian employee not found: " + custodianEmployeeId);
            }
        }
    }

    @Transactional
    public FixedAsset bindAssetToDepartmentAndCustodian(
        UUID assetId,
        UUID departmentId,
        UUID custodianEmployeeId,
        String operatingLocation
    ) {
        if (assetId == null) {
            throw new BadRequestException("Asset ID is required for department binding");
        }

        FixedAsset asset = fixedAssetRepository.findById(assetId)
            .orElseThrow(() -> new NotFoundException("Fixed asset not found: " + assetId));

        enforceDisposedExclusion(asset);

        // Validate department and custodian
        if (departmentId != null && departmentRepository != null) {
            if (!departmentRepository.existsById(departmentId)) {
                throw new NotFoundException("Department not found: " + departmentId);
            }
        }
        if (custodianEmployeeId != null && employeeRepository != null) {
            if (!employeeRepository.existsById(custodianEmployeeId)) {
                throw new NotFoundException("Custodian employee not found: " + custodianEmployeeId);
            }
        }

        asset.setDepartmentId(departmentId);
        asset.setCustodianEmployeeId(custodianEmployeeId);
        asset.setOperatingLocation(operatingLocation);
        asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);

        if (asset.getLifecycleStatus() == FixedAssetLifecycleStatus.IN_WAREHOUSE
            || asset.getLifecycleStatus() == FixedAssetLifecycleStatus.ISSUED_PENDING_COMMISSIONING) {
            asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
        }

        return fixedAssetRepository.save(asset);
    }

    public void enforceDisposedExclusion(FixedAsset asset) {
        if (asset == null) {
            return;
        }
        if (asset.getLifecycleStatus() == FixedAssetLifecycleStatus.DISPOSED
            || asset.getLifecycleStatus() == FixedAssetLifecycleStatus.CANCELLED) {
            throw new BadRequestException(
                "Cannot bind or operate on disposed or cancelled fixed asset: " + asset.getId()
                + " (status: " + asset.getLifecycleStatus() + ")"
            );
        }
    }

    public void validateSourceCardExclusion(String sourceState, boolean isDisposed) {
        if (isDisposed || "DISPOSED".equalsIgnoreCase(sourceState) || "CANCELLED".equalsIgnoreCase(sourceState)) {
            throw new BadRequestException(
                "An imported card with disposed/historical status cannot create an active asset through a read-only source view"
            );
        }
    }
}
