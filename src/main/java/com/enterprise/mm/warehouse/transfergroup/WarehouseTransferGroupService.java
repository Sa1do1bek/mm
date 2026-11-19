package com.enterprise.mm.warehouse.transfergroup;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.PaginationUtils;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.TransferDocument;
import com.enterprise.mm.entity.TransferDocumentLine;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.TransferCreateRequest;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.payload.TransferShipmentDtos;
import com.enterprise.mm.repository.TransferDocumentLineRepository;
import com.enterprise.mm.repository.TransferDocumentRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.enterprise.mm.service.RoleDataScopeService;
import com.enterprise.mm.service.TransferDocumentService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseTransferGroupService {

    private final WarehouseTransferGroupRepository groupRepository;
    private final WarehouseTransferGroupItemRepository itemRepository;
    private final TransferDocumentRepository transferRepository;
    private final TransferDocumentLineRepository transferLineRepository;
    private final TransferDocumentService transferDocumentService;
    private final RoleDataScopeService roleDataScopeService;
    private final WarehouseRepository warehouseRepository;

    public WarehouseTransferGroupService(
        WarehouseTransferGroupRepository groupRepository,
        WarehouseTransferGroupItemRepository itemRepository,
        TransferDocumentRepository transferRepository,
        TransferDocumentLineRepository transferLineRepository,
        TransferDocumentService transferDocumentService,
        RoleDataScopeService roleDataScopeService,
        WarehouseRepository warehouseRepository
    ) {
        this.groupRepository = groupRepository;
        this.itemRepository = itemRepository;
        this.transferRepository = transferRepository;
        this.transferLineRepository = transferLineRepository;
        this.transferDocumentService = transferDocumentService;
        this.roleDataScopeService = roleDataScopeService;
        this.warehouseRepository = warehouseRepository;
    }

    @Transactional(readOnly = true)
    public Page<TransferGroupOptionResponse> options(String search, UUID sourceWarehouseId, int page, int size) {
        var pageable = PaginationUtils.pageRequest(page, size,
            Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        String normalized = search == null ? "" : search.trim();
        if (normalized.codePointCount(0, normalized.length()) > 200) {
            throw new BadRequestException("Search must not exceed 200 characters", "transferGroup.search.tooLong", 200);
        }
        String pattern = normalized.isEmpty() ? null : "%" + normalized.toLowerCase(Locale.ROOT)
            .replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
        var scope = roleDataScopeService.currentScope();
        if (scope.warehouseScoped() && scope.warehouseId() == null) {
            throw new NotFoundException("Warehouse assignment required");
        }
        if (sourceWarehouseId != null) {
            roleDataScopeService.assertWarehouseAccess(sourceWarehouseId);
            warehouseRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), sourceWarehouseId
            ).orElseThrow(() -> new NotFoundException("Source warehouse not found"));
        }
        return groupRepository.findOptions(TenantContext.currentTenant(), CompanyContext.currentCompany(),
            scope.warehouseScoped() ? scope.warehouseId() : null, sourceWarehouseId, pattern, pageable)
            .map(this::selectorOption);
    }

    private TransferGroupOptionResponse selectorOption(TransferGroupOptionResponse option) {
        // Legacy display fallbacks are read-only; never rewrite business identifiers on a GET.
        String code = option.code() == null || option.code().isBlank() ? "WTG-" + option.id() : option.code();
        String name = LocalizedText.forCurrentLocale(option.name());
        if (name == null || name.isBlank()) {
            name = LocalizedText.forCurrentLocale("ru: Группа перемещений\nen: Transfer group\nuz: Ko‘chirish guruhi");
        }
        return new TransferGroupOptionResponse(option.id(), code, name);
    }

    private WarehouseTransferGroup requireReadableGroup(UUID groupId) {
        WarehouseTransferGroup group = groupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), groupId
        ).orElseThrow(() -> new NotFoundException("Transfer group not found: " + groupId));
        // Parent detail/progress disclose all destinations, so only the source owner may read them.
        roleDataScopeService.assertWarehouseAccess(group.getSourceWarehouseId());
        return group;
    }

    @Transactional
    public TransferGroupResponse createGroup(TransferGroupCreateRequest request) {
        if (request == null) {
            throw new BadRequestException("Group transfer request body is required");
        }
        if (request.sourceWarehouseId() == null) {
            throw new BadRequestException("Source warehouse is required");
        }
        if (!roleDataScopeService.canAccessWarehouse(request.sourceWarehouseId())) {
            throw new BusinessException("User does not have access to source warehouse " + request.sourceWarehouseId());
        }
        if (request.destinations() == null || request.destinations().isEmpty()) {
            throw new BadRequestException("At least one destination warehouse is required");
        }

        // Validate destinations
        for (var dest : request.destinations()) {
            if (dest.destinationWarehouseId() == null) {
                throw new BadRequestException("Destination warehouse is required for each branch");
            }
            if (Objects.equals(request.sourceWarehouseId(), dest.destinationWarehouseId())) {
                throw new BadRequestException("Source and destination warehouses must be different");
            }
            if (dest.items() == null || dest.items().isEmpty()) {
                throw new BadRequestException("Destination " + dest.destinationWarehouseId() + " must have at least one material line");
            }
        }

        String tenantId = TenantContext.currentTenant();
        UUID companyId = CompanyContext.currentCompany();

        WarehouseTransferGroup group = new WarehouseTransferGroup();
        group.setTenantId(tenantId);
        group.setCompanyId(companyId);
        group.setCode(request.code() == null || request.code().isBlank()
            ? "WTG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase()
            : request.code());
        group.setName(request.name() == null || request.name().isBlank() ? "Multi-destination transfer" : request.name());
        group.setDescription(request.description());
        group.setStatus("ACTIVE");
        group.setSourceWarehouseId(request.sourceWarehouseId());
        group.setPlannedDate(request.plannedDate());

        WarehouseTransferGroup savedGroup = groupRepository.save(group);

        List<WarehouseTransferGroupItem> groupItems = new ArrayList<>();

        for (var dest : request.destinations()) {
            // Create child TransferDocument
            TransferCreateRequest childReq = new TransferCreateRequest();
            childReq.setCode("TR-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase());
            childReq.setName("Child transfer to " + dest.destinationWarehouseId());
            childReq.setDescription("Child of group " + savedGroup.getCode());
            childReq.setSourceWarehouseId(request.sourceWarehouseId());
            childReq.setSourceLocationId(request.sourceLocationId());
            childReq.setDestinationWarehouseId(dest.destinationWarehouseId());
            childReq.setDestinationLocationId(dest.destinationLocationId());
            childReq.setPlannedDate(request.plannedDate());

            List<TransferCreateRequest.Line> childLines = new ArrayList<>();
            for (var item : dest.items()) {
                TransferCreateRequest.Line line = new TransferCreateRequest.Line();
                line.setMaterialId(item.materialId());
                line.setPlannedQuantity(item.plannedQuantity());
                line.setStockBatchId(item.stockBatchId());
                line.setStorageLocationId(item.storageLocationId());
                childLines.add(line);
            }
            childReq.setLines(childLines);

            TransferDocumentResponse childResponse = transferDocumentService.create(childReq);

            // Record group items mapping
            for (var childLineResp : childResponse.getLines()) {
                WarehouseTransferGroupItem groupItem = new WarehouseTransferGroupItem();
                groupItem.setTenantId(tenantId);
                groupItem.setCompanyId(companyId);
                groupItem.setGroupId(savedGroup.getId());
                groupItem.setDestinationWarehouseId(dest.destinationWarehouseId());
                groupItem.setMaterialId(childLineResp.getMaterialId());
                groupItem.setPlannedQuantity(childLineResp.getPlannedQuantity());
                groupItem.setChildTransferId(childResponse.getId());
                groupItem.setChildTransferLineId(childLineResp.getId());
                groupItem.setStatus("ACTIVE");
                groupItems.add(itemRepository.save(groupItem));
            }
        }

        TransferGroupAggregateProgress progress = calculateAggregateProgress(groupItems);
        return toResponse(savedGroup, groupItems, progress);
    }

    @Transactional(readOnly = true)
    public TransferGroupResponse getGroup(UUID groupId) {
        WarehouseTransferGroup group = requireReadableGroup(groupId);

        List<WarehouseTransferGroupItem> items = itemRepository
            .findByTenantIdAndCompanyIdAndGroupIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                groupId
            );

        TransferGroupAggregateProgress progress = calculateAggregateProgress(items);
        return toResponse(group, items, progress);
    }

    @Transactional(readOnly = true)
    public TransferGroupAggregateProgress getProgress(UUID groupId) {
        requireReadableGroup(groupId);
        List<WarehouseTransferGroupItem> items = itemRepository
            .findByTenantIdAndCompanyIdAndGroupIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                groupId
            );
        return calculateAggregateProgress(items);
    }

    @Transactional
    public TransferGroupResponse cancelGroupRemainder(UUID groupId, String reason, String idempotencyKey) {
        WarehouseTransferGroup group = groupRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            groupId
        ).orElseThrow(() -> new NotFoundException("Transfer group not found: " + groupId));

        List<WarehouseTransferGroupItem> items = itemRepository
            .findByTenantIdAndCompanyIdAndGroupIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                groupId
            );

        List<UUID> distinctChildTransferIds = items.stream()
            .map(WarehouseTransferGroupItem::getChildTransferId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        for (UUID childId : distinctChildTransferIds) {
            TransferDocument doc = transferRepository.findByIdAndIsDeletedFalse(childId)
                .orElse(null);
            if (doc != null) {
                List<TransferDocumentLine> lines = transferLineRepository
                    .findByTenantIdAndTransferDocumentIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), childId);
                boolean hasRemainder = lines.stream().anyMatch(l -> {
                    BigDecimal remaining = l.getPlannedQuantity()
                        .subtract(l.getDispatchedQuantity())
                        .subtract(l.getCancelledQuantity());
                    return remaining.signum() > 0;
                });
                if (hasRemainder) {
                    try {
                        transferDocumentService.cancelRemainder(
                            childId,
                            new TransferShipmentDtos.Action(doc.getVersion(), reason),
                            idempotencyKey + "-child-" + childId
                        );
                    } catch (Exception ignored) {
                        // If cannot cancel remainder (e.g. already cancelled or no remainder), proceed
                    }
                }
            }
        }

        TransferGroupAggregateProgress progress = calculateAggregateProgress(items);
        return toResponse(group, items, progress);
    }

    private TransferGroupAggregateProgress calculateAggregateProgress(List<WarehouseTransferGroupItem> items) {
        if (items.isEmpty()) {
            return TransferGroupAggregateProgress.zero();
        }

        BigDecimal planned = BigDecimal.ZERO;
        BigDecimal dispatched = BigDecimal.ZERO;
        BigDecimal inTransit = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal damaged = BigDecimal.ZERO;
        BigDecimal missing = BigDecimal.ZERO;
        BigDecimal cancelled = BigDecimal.ZERO;

        for (var item : items) {
            if (item.getChildTransferLineId() != null) {
                var lineOpt = transferLineRepository.findByIdAndIsDeletedFalse(item.getChildTransferLineId());
                if (lineOpt.isPresent()) {
                    var l = lineOpt.get();
                    planned = planned.add(nvl(l.getPlannedQuantity()));
                    dispatched = dispatched.add(nvl(l.getDispatchedQuantity()));
                    inTransit = inTransit.add(nvl(l.getInTransitQuantity()));
                    received = received.add(nvl(l.getReceivedQuantity()));
                    damaged = damaged.add(nvl(l.getDamagedQuantity()));
                    missing = missing.add(nvl(l.getMissingQuantity()));
                    cancelled = cancelled.add(nvl(l.getCancelledQuantity()));
                    continue;
                }
            }
            planned = planned.add(nvl(item.getPlannedQuantity()));
        }

        BigDecimal outstanding = planned.subtract(dispatched).subtract(cancelled).max(BigDecimal.ZERO);

        return new TransferGroupAggregateProgress(
            planned,
            dispatched,
            inTransit,
            received,
            damaged,
            missing,
            cancelled,
            outstanding
        );
    }

    private TransferGroupResponse toResponse(
        WarehouseTransferGroup group,
        List<WarehouseTransferGroupItem> items,
        TransferGroupAggregateProgress progress
    ) {
        List<UUID> childTransferIds = items.stream()
            .map(WarehouseTransferGroupItem::getChildTransferId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();

        List<TransferGroupResponse.ItemDetail> details = items.stream()
            .map(i -> new TransferGroupResponse.ItemDetail(
                i.getId(),
                i.getDestinationWarehouseId(),
                i.getMaterialId(),
                i.getPlannedQuantity(),
                i.getChildTransferId(),
                i.getChildTransferLineId(),
                i.getStatus()
            ))
            .toList();

        return new TransferGroupResponse(
            group.getId(),
            group.getCode(),
            group.getName(),
            group.getDescription(),
            group.getStatus(),
            group.getSourceWarehouseId(),
            group.getPlannedDate(),
            childTransferIds,
            details,
            progress,
            group.getCreatedAt()
        );
    }

    private static BigDecimal nvl(BigDecimal val) {
        return val == null ? BigDecimal.ZERO : val;
    }
}
