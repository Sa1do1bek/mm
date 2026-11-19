package com.enterprise.mm.warehouse.installation;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.warehouse.installation.InstallationCommands.AcceptInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.DispatchInstallationCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationPositionStatus;
import com.enterprise.mm.warehouse.installation.InstallationCommands.InstallationProjectBalance;
import com.enterprise.mm.warehouse.installation.InstallationCommands.ReturnInstallationCommand;
import com.enterprise.mm.warehouse.posting.AccountAwareStockPostingService;
import com.enterprise.mm.warehouse.posting.PostingCommand;
import com.enterprise.mm.entity.FixedAsset;
import com.enterprise.mm.enums.FixedAssetCustodyKind;
import com.enterprise.mm.enums.FixedAssetLifecycleStatus;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizeEquipmentCommand;
import com.enterprise.mm.warehouse.installation.InstallationCommands.CapitalizationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstallationEquipmentService {

    private final WarehouseInstallationPositionRepository positionRepository;
    private final AccountAwareStockPostingService postingService;
    private final WarehouseInstallationCapitalizationRepository capitalizationRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public InstallationEquipmentService(
        WarehouseInstallationPositionRepository positionRepository,
        AccountAwareStockPostingService postingService,
        WarehouseInstallationCapitalizationRepository capitalizationRepository,
        FixedAssetRepository fixedAssetRepository
    ) {
        this.positionRepository = positionRepository;
        this.postingService = postingService;
        this.capitalizationRepository = capitalizationRepository;
        this.fixedAssetRepository = fixedAssetRepository;
    }

    @Autowired
    public InstallationEquipmentService(
        WarehouseInstallationPositionRepository positionRepository,
        AccountAwareStockPostingService postingService,
        @Autowired(required = false) WarehouseInstallationCapitalizationRepository capitalizationRepository
    ) {
        this(positionRepository, postingService, capitalizationRepository, null);
    }

    public InstallationEquipmentService(
        WarehouseInstallationPositionRepository positionRepository,
        AccountAwareStockPostingService postingService
    ) {
        this(positionRepository, postingService, null, null);
    }

    @Transactional
    public WarehouseInstallationPosition dispatchEquipmentToProject(DispatchInstallationCommand command) {
        if (command == null) {
            throw new BadRequestException("Dispatch command cannot be null");
        }
        if (command.projectId() == null) {
            throw new BadRequestException("Project ID is required");
        }
        if (command.materialId() == null) {
            throw new BadRequestException("Material ID is required");
        }
        if (command.warehouseId() == null) {
            throw new BadRequestException("Warehouse ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Dispatch quantity must be strictly positive");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        LocalDate date = command.dispatchDate() != null ? command.dispatchDate() : LocalDate.now();

        // 1. Post issue from Account 07 via shared posting coordinator
        var postingCmd = new PostingCommand(
            "WAREHOUSE", "ISSUE", "INSTALLATION", UUID.randomUUID(),
            command.commandId() != null ? command.commandId() : UUID.randomUUID().toString(),
            date, 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
        );

        var issueRequest = new AccountAwareStockPostingService.IssueRequest(
            postingCmd,
            command.materialId(),
            command.warehouseId(),
            null, null, null,
            null,
            "OWNED",
            "AVAILABLE",
            "07",
            command.baseUnitCode() != null ? command.baseUnitCode() : "PCS",
            command.quantity(),
            BigDecimal.ZERO,
            "UZS",
            command.sourceReference() != null ? command.sourceReference() : "INSTALLATION_DISPATCH"
        );

        postingService.postIssue(issueRequest);

        // 2. Create or update installation position
        var optPos = positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialIdAndWarehouseId(
            tenantId, companyId.toString(), command.projectId(), command.materialId(), command.warehouseId()
        );

        WarehouseInstallationPosition position;
        if (optPos.isPresent()) {
            position = optPos.get();
            position.setDispatchedQuantity(position.getDispatchedQuantity().add(command.quantity()));
            position.setDispatchedDate(date);
            position.setUpdatedAt(Instant.now());
            if (position.getStatus() == InstallationPositionStatus.RETURNED || position.getStatus() == InstallationPositionStatus.FULLY_CAPITALIZED) {
                position.setStatus(InstallationPositionStatus.DISPATCHED);
            }
        } else {
            position = new WarehouseInstallationPosition();
            position.setId(UUID.randomUUID());
            position.setTenantId(tenantId);
            position.setCompanyId(companyId.toString());
            position.setProjectId(command.projectId());
            position.setMaterialId(command.materialId());
            position.setWarehouseId(command.warehouseId());
            position.setAccountCode("07");
            position.setStatus(InstallationPositionStatus.DISPATCHED);
            position.setDispatchedQuantity(command.quantity());
            position.setAcceptedQuantity(BigDecimal.ZERO);
            position.setCapitalizedQuantity(BigDecimal.ZERO);
            position.setReturnedQuantity(BigDecimal.ZERO);
            position.setSourceReference(command.sourceReference());
            position.setDispatchedDate(date);
            position.setCreatedAt(Instant.now());
            position.setUpdatedAt(Instant.now());
        }

        return positionRepository.save(position);
    }

    @Transactional
    public WarehouseInstallationPosition acceptEquipmentAtProject(AcceptInstallationCommand command) {
        if (command == null || command.positionId() == null) {
            throw new BadRequestException("Position ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Accepted quantity must be strictly positive");
        }

        var position = positionRepository.findById(command.positionId())
            .orElseThrow(() -> new BadRequestException("Installation position not found: " + command.positionId()));

        BigDecimal maxAcceptable = position.getDispatchedQuantity().subtract(position.getReturnedQuantity());
        if (position.getAcceptedQuantity().add(command.quantity()).compareTo(maxAcceptable) > 0) {
            throw new BadRequestException("Accepted quantity cannot exceed available dispatched quantity. Max: " + maxAcceptable);
        }

        position.setAcceptedQuantity(position.getAcceptedQuantity().add(command.quantity()));
        position.setAcceptedDate(command.acceptanceDate() != null ? command.acceptanceDate() : LocalDate.now());
        if (position.getStatus() == InstallationPositionStatus.DISPATCHED) {
            position.setStatus(InstallationPositionStatus.ACCEPTED);
        }
        position.setUpdatedAt(Instant.now());

        return positionRepository.save(position);
    }

    @Transactional
    public WarehouseInstallationPosition returnUnusedEquipmentToWarehouse(ReturnInstallationCommand command) {
        if (command == null || command.positionId() == null) {
            throw new BadRequestException("Position ID is required");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Return quantity must be strictly positive");
        }

        var position = positionRepository.findById(command.positionId())
            .orElseThrow(() -> new BadRequestException("Installation position not found: " + command.positionId()));

        BigDecimal returnable = position.getRemainingQuantity();
        if (command.quantity().compareTo(returnable) > 0) {
            throw new BadRequestException(
                "Cannot return more than available at project. Returnable: " + returnable + ", requested: " + command.quantity()
            );
        }

        UUID targetWarehouseId = command.returnWarehouseId() != null ? command.returnWarehouseId() : position.getWarehouseId();
        LocalDate date = command.returnDate() != null ? command.returnDate() : LocalDate.now();

        // 1. Post stock receipt back to warehouse on Account 07
        var postingCmd = new PostingCommand(
            "WAREHOUSE", "RECEIPT", "INSTALLATION_RETURN", UUID.randomUUID(),
            command.commandId() != null ? command.commandId() : UUID.randomUUID().toString(),
            date, 1L, mapper.createObjectNode().put("qty", command.quantity().toPlainString())
        );

        BigDecimal effectiveUnitCost = command.unitCost() != null ? command.unitCost() : BigDecimal.ZERO;
        BigDecimal totalAmount = effectiveUnitCost.signum() > 0
            ? command.quantity().multiply(effectiveUnitCost).setScale(4, RoundingMode.HALF_UP)
            : null;

        var receiptRequest = new AccountAwareStockPostingService.ReceiptRequest(
            postingCmd,
            position.getMaterialId(),
            targetWarehouseId,
            null, null, null,
            null,
            "OWNED",
            "AVAILABLE",
            "07",
            "PCS",
            command.quantity(),
            effectiveUnitCost,
            "UZS",
            "RETURN_FROM_PROJECT:" + (command.reason() != null ? command.reason() : "UNUSED"),
            totalAmount
        );

        postingService.postReceipt(receiptRequest);

        // 2. Update position returned quantity and status
        position.setReturnedQuantity(position.getReturnedQuantity().add(command.quantity()));
        if (position.getRemainingQuantity().signum() == 0) {
            position.setStatus(position.getCapitalizedQuantity().signum() > 0
                ? InstallationPositionStatus.FULLY_CAPITALIZED
                : InstallationPositionStatus.RETURNED);
        }
        position.setUpdatedAt(Instant.now());

        return positionRepository.save(position);
    }

    @Transactional(readOnly = true)
    public InstallationProjectBalance getProjectInstallationBalance(UUID projectId, UUID materialId) {
        if (projectId == null) {
            throw new BadRequestException("Project ID is required");
        }

        String tenantId = TenantContext.currentTenant() != null ? TenantContext.currentTenant() : "default";
        UUID companyId = CompanyContext.currentCompany() != null
            ? CompanyContext.currentCompany()
            : UUID.fromString("00000000-0000-0000-0000-000000000001");

        List<WarehouseInstallationPosition> positions;
        if (materialId != null) {
            positions = positionRepository.findByTenantIdAndCompanyIdAndProjectIdAndMaterialId(
                tenantId, companyId.toString(), projectId, materialId
            );
        } else {
            positions = positionRepository.findByTenantIdAndCompanyIdAndProjectId(
                tenantId, companyId.toString(), projectId
            );
        }

        BigDecimal dispatched = BigDecimal.ZERO;
        BigDecimal accepted = BigDecimal.ZERO;
        BigDecimal capitalized = BigDecimal.ZERO;
        BigDecimal returned = BigDecimal.ZERO;

        for (var pos : positions) {
            dispatched = dispatched.add(pos.getDispatchedQuantity());
            accepted = accepted.add(pos.getAcceptedQuantity());
            capitalized = capitalized.add(pos.getCapitalizedQuantity());
            returned = returned.add(pos.getReturnedQuantity());
        }

        BigDecimal remaining = dispatched.subtract(capitalized).subtract(returned);

        return new InstallationProjectBalance(
            projectId, materialId, dispatched, accepted, capitalized, returned, remaining
        );
    }

    @Transactional
    public CapitalizationResult capitalizeInstallationEquipment(CapitalizeEquipmentCommand command) {
        if (command == null || command.positionId() == null) {
            throw new BadRequestException("Position ID is required for capitalization");
        }
        if (command.quantity() == null || command.quantity().signum() <= 0) {
            throw new BadRequestException("Capitalization quantity must be strictly positive");
        }

        var position = positionRepository.findById(command.positionId())
            .orElseThrow(() -> new BadRequestException("Installation position not found: " + command.positionId()));

        BigDecimal remaining = position.getRemainingQuantity();
        if (command.quantity().compareTo(remaining) > 0) {
            throw new BadRequestException(
                "Cannot capitalize more than available remaining quantity at project. Remaining: " + remaining
                + ", requested: " + command.quantity()
            );
        }

        LocalDate date = command.capitalizationDate() != null ? command.capitalizationDate() : LocalDate.now();
        List<UUID> assetIds = command.fixedAssetIds() != null ? command.fixedAssetIds() : new ArrayList<>();

        BigDecimal totalCost = command.totalCost() != null ? command.totalCost() : BigDecimal.ZERO;
        BigDecimal unitCost = BigDecimal.ZERO;
        if (totalCost.signum() > 0 && command.quantity().signum() > 0) {
            unitCost = totalCost.divide(command.quantity(), 4, RoundingMode.HALF_UP);
        }

        // 1. Commission / update associated FixedAsset cards
        if (fixedAssetRepository != null) {
            for (UUID assetId : assetIds) {
                var optAsset = fixedAssetRepository.findById(assetId);
                if (optAsset.isPresent()) {
                    FixedAsset asset = optAsset.get();
                    asset.setLifecycleStatus(FixedAssetLifecycleStatus.IN_USE);
                    asset.setCustodyKind(FixedAssetCustodyKind.DEPARTMENT);
                    if (unitCost.signum() > 0) {
                        asset.setAcquisitionValue(unitCost);
                        asset.setCurrentBookValue(unitCost);
                    }
                    asset.setFirstCommissionedOn(date);
                    asset.setFirstCommissionedAt(LocalDateTime.now());
                    fixedAssetRepository.save(asset);
                }
            }
        }

        // 2. Record capitalization audit entry
        if (capitalizationRepository != null) {
            if (!assetIds.isEmpty()) {
                for (UUID assetId : assetIds) {
                    var cap = new WarehouseInstallationCapitalization();
                    cap.setId(UUID.randomUUID());
                    cap.setTenantId(position.getTenantId());
                    cap.setCompanyId(position.getCompanyId());
                    cap.setPositionId(position.getId());
                    cap.setCapitalizationCommandId(command.commandId() != null ? command.commandId() : UUID.randomUUID().toString());
                    cap.setFixedAssetId(assetId);
                    cap.setCapitalizedQuantity(BigDecimal.ONE);
                    cap.setCapitalizedAmount(unitCost);
                    cap.setCapitalizationDate(date);
                    cap.setSourceReference(command.sourceReference());
                    cap.setCreatedAt(Instant.now());
                    capitalizationRepository.save(cap);
                }
            } else {
                var cap = new WarehouseInstallationCapitalization();
                cap.setId(UUID.randomUUID());
                cap.setTenantId(position.getTenantId());
                cap.setCompanyId(position.getCompanyId());
                cap.setPositionId(position.getId());
                cap.setCapitalizationCommandId(command.commandId() != null ? command.commandId() : UUID.randomUUID().toString());
                cap.setFixedAssetId(UUID.randomUUID());
                cap.setCapitalizedQuantity(command.quantity());
                cap.setCapitalizedAmount(totalCost);
                cap.setCapitalizationDate(date);
                cap.setSourceReference(command.sourceReference());
                cap.setCreatedAt(Instant.now());
                capitalizationRepository.save(cap);
            }
        }

        // 3. Update installation position
        position.setCapitalizedQuantity(position.getCapitalizedQuantity().add(command.quantity()));
        BigDecimal newRemaining = position.getRemainingQuantity();
        if (newRemaining.signum() == 0) {
            position.setStatus(InstallationPositionStatus.FULLY_CAPITALIZED);
        } else {
            position.setStatus(InstallationPositionStatus.PARTIALLY_CAPITALIZED);
        }
        position.setUpdatedAt(Instant.now());
        positionRepository.save(position);

        return new CapitalizationResult(
            command.commandId(),
            position.getId(),
            command.quantity(),
            totalCost,
            newRemaining,
            assetIds
        );
    }
}
