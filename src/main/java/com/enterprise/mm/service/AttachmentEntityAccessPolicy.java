package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceAcceptanceAct;
import com.enterprise.mm.entity.ServiceOrder;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.StockMovementDocument;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.repository.AssetAcceptanceRepository;
import com.enterprise.mm.repository.AssetAcceptanceUnitRepository;
import com.enterprise.mm.repository.AssetHandoverRepository;
import com.enterprise.mm.repository.AssetOpeningImportRepository;
import com.enterprise.mm.repository.AssetVerificationRepository;
import com.enterprise.mm.repository.FixedAssetRepository;
import com.enterprise.mm.repository.ServiceAcceptanceActRepository;
import com.enterprise.mm.repository.ServiceClaimRepository;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.ServiceOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.StockMovementDocumentRepository;
import com.enterprise.mm.repository.InventoryAiDraftRepository;
import com.enterprise.mm.repository.InventoryOrderRepository;
import com.enterprise.mm.repository.InventoryOrderAllocationRepository;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.repository.SupplierQuotationRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import com.enterprise.mm.security.AccessScope;
import com.enterprise.mm.security.AuthenticatedTokenState;
import java.util.UUID;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AttachmentEntityAccessPolicy {
    private static final Set<String> SERVICE_ENTITY_TYPES = Set.of(
        "SERVICE_REQUEST",
        "SERVICE_EXECUTION",
        "SERVICE_ORDER",
        "SERVICE_ACCEPTANCE_ACT",
        "SERVICE_CLAIM",
        "WORK_COMPLETION_ACT"
    );
    private static final Set<String> ASSET_ENTITY_TYPES = Set.of(
        "ASSET_ACCEPTANCE",
        "ASSET_ACCEPTANCE_UNIT",
        "FIXED_ASSET",
        "ASSET_HANDOVER",
        "ASSET_REPAIR",
        "ASSET_SUPPLIER_RETURN",
        "ASSET_DISPOSAL",
        "ASSET_VERIFICATION",
        "ASSET_OPENING_IMPORT"
    );
    private static final Set<String> ASSET_ATTACHMENT_TYPES = Set.of(
        "DOCUMENT",
        "PHOTO",
        "ACCEPTANCE_EVIDENCE",
        "QUALITY_EVIDENCE",
        "COMMISSIONING_EVIDENCE",
        "HANDOVER_EVIDENCE",
        "REPAIR_EVIDENCE",
        "SUPPLIER_RETURN_EVIDENCE",
        "DISPOSAL_EVIDENCE",
        "VERIFICATION_EVIDENCE",
        "OPENING_IMPORT_EVIDENCE",
        "SOURCE_FILE"
    );
    private static final Set<String> INTERNAL_UPLOAD_ROLES = Set.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "FINANCE_CONTROLLER",
        "BUDGET_CONTROLLER",
        "DEPARTMENT_HEAD"
    );
    private static final Set<String> PRIVILEGED_DELETE_ROLES = Set.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST"
    );
    private static final Set<String> ASSET_UPLOAD_ROLES = Set.of(
        "ADMIN",
        "MM_MANAGER",
        "PROCUREMENT_MANAGER",
        "PROCUREMENT_SPECIALIST",
        "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR",
        "WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER",
        "FINANCE_CONTROLLER",
        "BUDGET_CONTROLLER",
        "DEPARTMENT_HEAD",
        "DEPARTMENT_WORKER",
        "AUDITOR"
    );
    private final com.enterprise.mm.service.inventory.InventoryAccessService inventoryAccess;
    private final com.enterprise.mm.service.transfer.TransferExceptionStateService transferState;
    private final ServiceRequestRepository requestRepository;
    private final ServiceExecutionRepository executionRepository;
    private final ServiceOrderRepository orderRepository;
    private final ServiceAcceptanceActRepository acceptanceActRepository;
    private final ServiceClaimRepository claimRepository;
    private final WorkCompletionActRepository workCompletionActRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final PurchaseOrderItemRepository purchaseOrderItemRepository;
    private final ServiceWorkflowAccessService workflowAccessService;
    private final SupplierQuotationRepository supplierQuotationRepository;
    private final RfqPublishedVersionRepository publishedVersionRepository;
    private final ProcurementMethodDecisionRepository procurementMethodDecisionRepository;
    private final FixedAssetRepository fixedAssetRepository;
    private final AssetAcceptanceRepository assetAcceptanceRepository;
    private final AssetAcceptanceUnitRepository assetAcceptanceUnitRepository;
    private final AssetHandoverRepository assetHandoverRepository;
    private final AssetVerificationRepository assetVerificationRepository;
    private final AssetOpeningImportRepository assetOpeningImportRepository;
    private final StockMovementDocumentRepository stockMovementDocumentRepository;
    private final InventoryAiDraftRepository inventoryAiDraftRepository;
    private final InventoryOrderRepository inventoryOrderRepository;
    private final InventoryOrderAllocationRepository inventoryOrderAllocationRepository;
    private final ResourceVersionAccessService resourceVersionAccess;
    private final org.springframework.beans.factory.ObjectProvider<DamagedStockService> damagedStock;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.enterprise.mm.repository.ManualUnmatchedIssueRepository unmatchedIssueRepository;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private RoleDataScopeService unmatchedIssueScope;

    public void requireAccess(String entityType, UUID entityId) {
        switch (entityType) {
            case "USER_PROFILE" -> requireOwnProfile(entityId);
            case "RESOURCE_VERSION" -> resourceVersionAccess.requireVisible(entityId);
            case "DAMAGED_STOCK" -> damagedStock.getObject().readCase(entityId);
            case "INVENTORY_AI_DRAFT" -> requireInventoryAiDraft(entityId);
            case "INVENTORY_ORDER" -> requireInventoryOrder(entityId);
            case "INVENTORY_ORDER_TASK" -> requireInventoryOrderTask(entityId);
            case "INVENTORY_DOCUMENT" -> inventoryAccess.requireVisibleExport(inventoryAccess.document(entityId));
            case "INVENTORY_ITEM" -> inventoryAccess.requireVisibleExport(inventoryAccess.document(inventoryAccess.line(entityId).getInventoryDocumentId()));
            case "INVENTORY_COUNT_EVIDENCE" -> inventoryAccess.document(entityId);
            case "INVENTORY_LINE_COUNT_EVIDENCE" -> inventoryAccess.line(entityId);
            case "SERVICE_REQUEST" -> requireRequest(entityId);
            case "TRANSFER_DOCUMENT" -> transferState.transfer(entityId, false);
            case "SERVICE_EXECUTION" -> requireExecution(entityId);
            case "SERVICE_ORDER" -> requireOrder(entityId);
            case "SERVICE_ACCEPTANCE_ACT" -> requireAcceptanceAct(entityId);
            case "SERVICE_CLAIM" -> requireClaim(entityId);
            case "WORK_COMPLETION_ACT" -> requireWorkCompletionAct(entityId);
            case "ASSET_ACCEPTANCE" -> requireAssetAcceptance(entityId);
            case "ASSET_ACCEPTANCE_UNIT" -> requireAssetAcceptanceUnit(entityId);
            case "FIXED_ASSET", "ASSET_REPAIR", "ASSET_SUPPLIER_RETURN", "ASSET_DISPOSAL" -> requireFixedAsset(entityId);
            case "ASSET_HANDOVER" -> requireAssetHandover(entityId);
            case "ASSET_VERIFICATION" -> requireAssetVerification(entityId);
            case "ASSET_OPENING_IMPORT" -> requireAssetOpeningImport(entityId);
            case "SUPPLIER_QUOTATION_EVIDENCE" -> requireQuotationEvidence(entityId);
            case "RFQ_PUBLISHED_VERSION" -> requirePublishedVersion(entityId);
            case "PROCUREMENT_METHOD_DECISION" -> requireProcurementMethodDecision(entityId);
            case "STOCK_MOVEMENT_DOCUMENT" -> requireStockMovementDocument(entityId);
            case "MANUAL_UNMATCHED_ISSUE" -> requireManualUnmatchedIssue(entityId);
            default -> {
                // Other aggregate families retain their existing controller/service authorization.
            }
        }
    }

    public void requireRead(String entityType, UUID entityId) {
        requireAccess(entityType, entityId);
    }

    public void requireUpload(String entityType, UUID entityId, String attachmentType) {
        if ("USER_PROFILE".equals(entityType)) {
            requireOwnProfile(entityId);
            throw new AccessDeniedException("Use the profile photo endpoint");
        }
        if ("RESOURCE_VERSION".equals(entityType)) {
            throw new AccessDeniedException("Use the Resources upload endpoint");
        }
        requireAccess(entityType, entityId);
        if ("DAMAGED_STOCK".equals(entityType)) {
            if (!"DOCUMENT".equals(attachmentType)) throw new AccessDeniedException("Use a document or photo as damage evidence");
            damagedStock.getObject().requireEditableEvidence(entityId);
            return;
        }
        if ("INVENTORY_AI_DRAFT".equals(entityType)) {
            throw new AccessDeniedException("Use the inventory AI draft file endpoint");
        }
        if ("INVENTORY_ORDER".equals(entityType)) {
            var order = inventoryOrderRepository.findByIdAndTenantIdAndCompanyId(entityId, TenantContext.currentTenant(), CompanyContext.currentCompany())
                .orElseThrow(() -> new NotFoundException("Inventory order not found"));
            if (!"LOCAL_INSTRUCTION".equals(attachmentType)
                || !Objects.equals(order.getOwnerUsername(), currentUsername()) && !hasAnyRole(Set.of("ADMIN", "MM_MANAGER")))
                throw new AccessDeniedException("Only the order planner can add local instructions");
            return;
        }
        if ("INVENTORY_ORDER_TASK".equals(entityType)) {
            var task = requireInventoryOrderTask(entityId);
            var order = inventoryOrderRepository.findByIdAndTenantIdAndCompanyId(task.getOrderId(), TenantContext.currentTenant(), CompanyContext.currentCompany())
                .orElseThrow(() -> new NotFoundException("Inventory order not found"));
            if (!Objects.equals(order.getOwnerUsername(), currentUsername()) && !hasAnyRole(Set.of("ADMIN", "MM_MANAGER")))
                throw new AccessDeniedException("Only the order planner can attach task reports");
            if (!"OPEN".equals(task.getStatus())) throw new AccessDeniedException("Task report is locked after submission");
            return;
        }
        if ("INVENTORY_LINE_COUNT_EVIDENCE".equals(entityType)) {
            var line = inventoryAccess.line(entityId);
            var d = inventoryAccess.document(line.getInventoryDocumentId());
            inventoryAccess.requireCount(d);
            com.enterprise.mm.service.inventory.InventoryRevisionService.requireEditable(d);
            return;
        }
        if ("INVENTORY_COUNT_EVIDENCE".equals(entityType)) {
            var d = inventoryAccess.document(entityId);
            if (!inventoryAccess.canCount(d) && !inventoryAccess.canReview(d)) throw new AccessDeniedException("Inventory evidence authority is required");
            com.enterprise.mm.service.inventory.InventoryRevisionService.requireEditable(d);
            return;
        }
        if (java.util.Set.of("INVENTORY_DOCUMENT", "INVENTORY_ITEM").contains(entityType)) {
            var d = inventoryAccess.document("INVENTORY_ITEM".equals(entityType) ? inventoryAccess.line(entityId).getInventoryDocumentId() : entityId);
            if (!inventoryAccess.canCount(d) && !inventoryAccess.canReview(d)) throw new AccessDeniedException("Inventory evidence authority is required");
            return;
        }
        if ("TRANSFER_DOCUMENT".equals(entityType)) {
            if (!hasAnyRole(ASSET_UPLOAD_ROLES)) throw new AccessDeniedException("Warehouse evidence upload authority is required");
            return;
        }
        if ("STOCK_MOVEMENT_DOCUMENT".equals(entityType) || "MANUAL_UNMATCHED_ISSUE".equals(entityType)) {
            if (!hasAnyRole(ASSET_UPLOAD_ROLES)) throw new AccessDeniedException("Warehouse evidence upload authority is required");
            return;
        }
        if ("SUPPLIER_QUOTATION_EVIDENCE".equals(entityType)) {
            String status = quotationStatus(entityId);
            if (hasAnyRole(PRIVILEGED_DELETE_ROLES)
                || ("DRAFT".equals(status) && isSupplierSession())) {
                return;
            }
            throw new AccessDeniedException("Supplier quotation evidence upload is not permitted");
        }
        if ("RFQ_PUBLISHED_VERSION".equals(entityType)) {
            if (hasAnyRole(INTERNAL_UPLOAD_ROLES)) {
                return;
            }
            throw new AccessDeniedException("RFQ published package upload is not permitted");
        }
        if (!SERVICE_ENTITY_TYPES.contains(entityType)) {
            if (ASSET_ENTITY_TYPES.contains(entityType)) {
                requireAssetAttachmentType(attachmentType);
                if (!hasAnyRole(ASSET_UPLOAD_ROLES)) {
                    throw new AccessDeniedException("Fixed asset evidence upload is not permitted");
                }
            }
            return;
        }
        String status = entityStatus(entityType, entityId);
        if (hasAnyRole(INTERNAL_UPLOAD_ROLES) && internalUploadAllowed(entityType, status)) {
            return;
        }
        if ((hasRole("SUPPLIER_MANAGER") || isSupplierSession())
            && supplierUploadAllowed(entityType, status)
            && supplierAttachmentTypeAllowed(entityType, attachmentType)) {
            return;
        }
        throw new AccessDeniedException("Service evidence upload is not permitted for this role or workflow status");
    }

    public boolean canUpload(String entityType, UUID entityId, String attachmentType) {
        try {
            requireUpload(entityType, entityId, attachmentType);
            return true;
        } catch (AccessDeniedException | NotFoundException exception) {
            return false;
        }
    }

    public void requireDelete(String entityType, UUID entityId, String uploadedBy) {
        if ("USER_PROFILE".equals(entityType)) {
            requireOwnProfile(entityId);
            return;
        }
        if ("RESOURCE_VERSION".equals(entityType)) {
            throw new AccessDeniedException("Published resource originals cannot be deleted as attachments");
        }
        requireAccess(entityType, entityId);
        if ("MANUAL_UNMATCHED_ISSUE".equals(entityType))
            throw new AccessDeniedException("Posted issue evidence is immutable");
        if ("DAMAGED_STOCK".equals(entityType)) {
            damagedStock.getObject().requireEditableEvidence(entityId);
            if (!Objects.equals(currentUsername(), uploadedBy)) damagedStock.getObject().requireManager();
            return;
        }
        if ("INVENTORY_AI_DRAFT".equals(entityType)) {
            throw new AccessDeniedException("Use the inventory AI draft file endpoint");
        }
        if ("INVENTORY_ORDER".equals(entityType)) {
            throw new AccessDeniedException("Inventory order source files are immutable");
        }
        if ("INVENTORY_ORDER_TASK".equals(entityType)) {
            var task = requireInventoryOrderTask(entityId);
            var order = inventoryOrderRepository.findByIdAndTenantIdAndCompanyId(task.getOrderId(), TenantContext.currentTenant(), CompanyContext.currentCompany())
                .orElseThrow(() -> new NotFoundException("Inventory order not found"));
            if (!Objects.equals(order.getOwnerUsername(), currentUsername()) && !hasAnyRole(Set.of("ADMIN", "MM_MANAGER")))
                throw new AccessDeniedException("Only the order planner can remove task reports");
            if (!"OPEN".equals(task.getStatus())) throw new AccessDeniedException("Task report is locked after submission");
            return;
        }
        if (java.util.Set.of("INVENTORY_DOCUMENT", "INVENTORY_ITEM", "INVENTORY_COUNT_EVIDENCE", "INVENTORY_LINE_COUNT_EVIDENCE").contains(entityType)) {
            var d = inventoryAccess.document(java.util.Set.of("INVENTORY_ITEM", "INVENTORY_LINE_COUNT_EVIDENCE").contains(entityType) ? inventoryAccess.line(entityId).getInventoryDocumentId() : entityId);
            if (!inventoryAccess.canCount(d) && !inventoryAccess.canReview(d)) throw new AccessDeniedException("Inventory evidence authority is required");
            if (d.getSnapshotAt() != null) throw new AccessDeniedException("Physical count evidence is retained with its observation history");
        }
        if ("TRANSFER_DOCUMENT".equals(entityType)) {
            transferState.requireEvidenceDeletionAllowed(entityId);
            if (!hasAnyRole(PRIVILEGED_DELETE_ROLES) && !Objects.equals(currentUsername(), uploadedBy)) throw new AccessDeniedException("Only the uploader or a warehouse manager can remove unlinked evidence");
            return;
        }
        if ("STOCK_MOVEMENT_DOCUMENT".equals(entityType)) {
            stockMovementDocumentRepository.findByIdAndIsDeletedFalse(entityId)
                .filter(this::inScope)
                .filter(d -> WarehouseStockFactService.REFERENCE_TYPE.equals(d.getReferenceType()) && "POSTED".equals(d.getStatus()))
                .ifPresent(d -> { throw new AccessDeniedException("Proof of a posted physical warehouse operation cannot be removed"); });
            if (!hasAnyRole(PRIVILEGED_DELETE_ROLES) && !Objects.equals(currentUsername(), uploadedBy)) throw new AccessDeniedException("Only the uploader or an administrator can remove warehouse document evidence");
            return;
        }
        if ("SUPPLIER_QUOTATION_EVIDENCE".equals(entityType)) {
            if (!"DRAFT".equals(quotationStatus(entityId))) {
                throw new AccessDeniedException("Submitted supplier proposal evidence is immutable");
            }
            if (hasAnyRole(PRIVILEGED_DELETE_ROLES)
                || (isSupplierSession() && Objects.equals(currentUsername(), uploadedBy))) {
                return;
            }
            throw new AccessDeniedException("Supplier quotation evidence deletion is not permitted");
        }
        if ("RFQ_PUBLISHED_VERSION".equals(entityType)) {
            throw new AccessDeniedException("Published RFQ package evidence is immutable");
        }
        if (!SERVICE_ENTITY_TYPES.contains(entityType)) {
            if (ASSET_ENTITY_TYPES.contains(entityType)
                && !(hasAnyRole(PRIVILEGED_DELETE_ROLES) || Objects.equals(currentUsername(), uploadedBy))) {
                throw new AccessDeniedException("Fixed asset evidence deletion is restricted to its uploader or procurement administrators");
            }
            return;
        }
        String status = entityStatus(entityType, entityId);
        if (!deleteAllowed(entityType, status)) {
            throw new AccessDeniedException("Service evidence cannot be deleted after the workflow leaves a draft-safe status");
        }
        if (hasAnyRole(PRIVILEGED_DELETE_ROLES)) {
            return;
        }
        if (hasAnyRole(INTERNAL_UPLOAD_ROLES) && Objects.equals(currentUsername(), uploadedBy)) {
            return;
        }
        throw new AccessDeniedException("Service evidence deletion is restricted to its internal uploader or procurement administrators");
    }

    private void requireInventoryAiDraft(UUID id) {
        var draft = inventoryAiDraftRepository.findByIdAndTenantIdAndCompanyIdAndOwnerUsername(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany(), currentUsername())
            .orElseThrow(() -> new NotFoundException("Inventory AI draft not found"));
        if (!"OPEN".equals(draft.getStatus()) || draft.getExpiresAt().isBefore(java.time.LocalDateTime.now())) {
            throw new NotFoundException("Inventory AI draft not found");
        }
    }

    private void requireInventoryOrder(UUID id) {
        var order = inventoryOrderRepository.findByIdAndTenantIdAndCompanyId(
            id, TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Inventory order not found"));
        if (!Objects.equals(order.getOwnerUsername(), currentUsername())
            && !hasAnyRole(Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT")))
            throw new AccessDeniedException("Inventory order access is restricted");
    }

    private com.enterprise.mm.entity.InventoryOrderAllocation requireInventoryOrderTask(UUID id) {
        var task = inventoryOrderAllocationRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Inventory order task not found"));
        var order = inventoryOrderRepository.findByIdAndTenantIdAndCompanyId(task.getOrderId(), TenantContext.currentTenant(), CompanyContext.currentCompany())
            .orElseThrow(() -> new NotFoundException("Inventory order task not found"));
        if (!Objects.equals(order.getOwnerUsername(), currentUsername())
            && !hasAnyRole(Set.of("ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT"))
            && !Objects.equals(task.getApproverUserId(), inventoryAccess.currentUserId()))
            throw new AccessDeniedException("Inventory order task access is restricted");
        return task;
    }

    private ServiceRequest requireRequest(UUID id) {
        ServiceRequest request = requestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .filter(workflowAccessService::canAccessServiceRequest)
            .orElseThrow(() -> notFound(id));
        return request;
    }

    private void requireExecution(UUID id) {
        var execution = executionRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        ServiceRequest request = requireRelatedRequest(execution.getServiceRequestId());
        if (!workflowAccessService.canAccessServiceExecution(execution, request)) {
            throw notFound(id);
        }
    }

    private ServiceOrder requireOrder(UUID id) {
        ServiceOrder order = orderRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        ServiceRequest request = relatedRequest(order.getServiceRequestId());
        if (!workflowAccessService.canAccessServiceOrder(order, request)) {
            throw notFound(id);
        }
        return order;
    }

    private void requireAcceptanceAct(UUID id) {
        ServiceAcceptanceAct act = acceptanceActRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        ServiceRequest request = relatedRequest(act.getServiceRequestId());
        ServiceOrder order = relatedOrder(act.getServiceOrderId());
        if (!workflowAccessService.canAccessServiceAcceptanceAct(act, request, order)) {
            throw notFound(id);
        }
    }

    private void requireClaim(UUID id) {
        var claim = claimRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        ServiceRequest request = relatedRequest(claim.getServiceRequestId());
        ServiceOrder serviceOrder = relatedOrder(claim.getServiceOrderId());
        var item = claim.getPurchaseOrderItemId() == null ? null
            : purchaseOrderItemRepository.findByIdAndIsDeletedFalse(claim.getPurchaseOrderItemId())
                .filter(this::inScope).orElseThrow(() -> notFound(id));
        var purchaseOrder = serviceOrder != null && serviceOrder.getPurchaseOrderId() != null
            ? purchaseOrderRepository.findByIdAndIsDeletedFalse(serviceOrder.getPurchaseOrderId())
                .filter(this::inScope).orElseThrow(() -> notFound(id))
            : item != null
                ? purchaseOrderRepository.findByIdAndIsDeletedFalse(item.getPurchaseOrderId())
                    .filter(this::inScope).orElseThrow(() -> notFound(id))
                : null;
        boolean coherent = request != null || serviceOrder != null || item != null;
        if (!coherent || !workflowAccessService.canAccessServiceClaim(claim, request, serviceOrder, purchaseOrder)) {
            throw notFound(id);
        }
    }

    private void requireWorkCompletionAct(UUID id) {
        var act = workCompletionActRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        var order = purchaseOrderRepository.findByIdAndIsDeletedFalse(act.getPurchaseOrderId())
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
        var item = purchaseOrderItemRepository.findByIdAndIsDeletedFalse(act.getPurchaseOrderItemId())
            .filter(this::inScope)
            .filter(record -> order.getId().equals(record.getPurchaseOrderId()))
            .orElseThrow(() -> notFound(id));
        if (!order.getId().equals(act.getPurchaseOrderId())
            || !item.getId().equals(act.getPurchaseOrderItemId())
            || !workflowAccessService.canAccessPurchaseOrder(order)) {
            throw notFound(id);
        }
    }

    private void requireAssetAcceptance(UUID id) {
        assetAcceptanceRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireAssetAcceptanceUnit(UUID id) {
        assetAcceptanceUnitRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireFixedAsset(UUID id) {
        fixedAssetRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireAssetHandover(UUID id) {
        assetHandoverRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireAssetVerification(UUID id) {
        assetVerificationRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireAssetOpeningImport(UUID id) {
        assetOpeningImportRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                com.enterprise.mm.common.CompanyContext.currentCompany(),
                id
            )
            .orElseThrow(() -> notFound(id));
    }

    private void requireAssetAttachmentType(String attachmentType) {
        if (!ASSET_ATTACHMENT_TYPES.contains(attachmentType)) {
            throw new AccessDeniedException("Fixed asset evidence type is not permitted");
        }
    }

    private void requireQuotationEvidence(UUID id) {
        supplierQuotationRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
    }

    private void requirePublishedVersion(UUID id) {
        publishedVersionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(),
            com.enterprise.mm.common.CompanyContext.currentCompany(),
            id
        ).orElseThrow(() -> notFound(id));
    }

    private void requireProcurementMethodDecision(UUID id) {
        procurementMethodDecisionRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
    }

    private void requireStockMovementDocument(UUID id) {
        stockMovementDocumentRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElseThrow(() -> notFound(id));
    }

    private void requireManualUnmatchedIssue(UUID id) {
        if (unmatchedIssueRepository == null) throw notFound(id);
        var issue = unmatchedIssueRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), id).orElseThrow(() -> notFound(id));
        // The entry context bounds access while the physical source is unknown.
        if (!inScope(issue)) throw notFound(id);
        if (unmatchedIssueScope == null) throw notFound(id);
        unmatchedIssueScope.assertWarehouseAccess(issue.getEntryContextWarehouseId());
    }

    private String quotationStatus(UUID id) {
        return supplierQuotationRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .map(value -> value.getStatus() == null ? null : value.getStatus().trim().toUpperCase(Locale.ROOT))
            .orElseThrow(() -> notFound(id));
    }

    private ServiceRequest requireRelatedRequest(UUID id) {
        ServiceRequest request = relatedRequest(id);
        if (request == null) {
            throw notFound(id);
        }
        return request;
    }

    private ServiceRequest relatedRequest(UUID id) {
        if (id == null) {
            return null;
        }
        return requestRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElse(null);
    }

    private ServiceOrder relatedOrder(UUID id) {
        if (id == null) {
            return null;
        }
        return orderRepository.findByIdAndIsDeletedFalse(id)
            .filter(this::inScope)
            .orElse(null);
    }

    private boolean inScope(com.enterprise.mm.common.BusinessEntity entity) {
        return TenantContext.currentTenant().equals(entity.getTenantId())
            && AccessScope.canAccessCompany(entity.getCompanyId());
    }

    private NotFoundException notFound(UUID id) {
        return new NotFoundException("Attachment entity not found: " + id);
    }

    private String entityStatus(String entityType, UUID entityId) {
        String status = switch (entityType) {
            case "SERVICE_REQUEST" -> requestRepository.findByIdAndIsDeletedFalse(entityId)
                .map(ServiceRequest::getStatus).orElse(null);
            case "SERVICE_EXECUTION" -> executionRepository.findByIdAndIsDeletedFalse(entityId)
                .map(com.enterprise.mm.entity.ServiceExecution::getStatus).orElse(null);
            case "SERVICE_ORDER" -> orderRepository.findByIdAndIsDeletedFalse(entityId)
                .map(ServiceOrder::getStatus).orElse(null);
            case "SERVICE_ACCEPTANCE_ACT" -> acceptanceActRepository.findByIdAndIsDeletedFalse(entityId)
                .map(ServiceAcceptanceAct::getStatus).orElse(null);
            case "SERVICE_CLAIM" -> claimRepository.findByIdAndIsDeletedFalse(entityId)
                .map(com.enterprise.mm.entity.ServiceClaim::getStatus).orElse(null);
            case "WORK_COMPLETION_ACT" -> workCompletionActRepository.findByIdAndIsDeletedFalse(entityId)
                .map(com.enterprise.mm.entity.WorkCompletionAct::getStatus).orElse(null);
            default -> null;
        };
        return status == null ? null : status.trim().toUpperCase(Locale.ROOT);
    }

    private boolean internalUploadAllowed(String entityType, String status) {
        if (status == null) {
            return false;
        }
        return switch (entityType) {
            case "SERVICE_REQUEST" -> List.of(
                "DRAFT", "RETURNED", "APPROVED", "WAITING_EXECUTION", "IN_PROGRESS",
                "WAITING_ACCEPTANCE", "PARTIALLY_ACCEPTED", "ACCEPTED", "ACT_PENDING"
            ).contains(status);
            case "SERVICE_EXECUTION" -> List.of(
                "NOT_STARTED", "IN_PROGRESS", "PAUSED", "COMPLETED_BY_CONTRACTOR", "UNDER_REVIEW", "REJECTED"
            ).contains(status);
            case "SERVICE_ORDER" -> !List.of("CLOSED", "CANCELLED").contains(status);
            case "SERVICE_ACCEPTANCE_ACT" -> !List.of("SENT_TO_PAYMENT", "PAID").contains(status);
            case "SERVICE_CLAIM" -> !"CLOSED".equals(status);
            case "WORK_COMPLETION_ACT" -> !List.of("SENT_TO_PAYMENT", "PAID").contains(status);
            default -> false;
        };
    }

    private boolean supplierUploadAllowed(String entityType, String status) {
        if (status == null) {
            return false;
        }
        return switch (entityType) {
            case "SERVICE_REQUEST" -> List.of(
                "APPROVED", "WAITING_EXECUTION", "IN_PROGRESS", "WAITING_ACCEPTANCE", "PARTIALLY_ACCEPTED", "ACT_PENDING"
            ).contains(status);
            case "SERVICE_EXECUTION" -> List.of(
                "NOT_STARTED", "IN_PROGRESS", "PAUSED", "COMPLETED_BY_CONTRACTOR", "REJECTED"
            ).contains(status);
            case "SERVICE_ORDER" -> List.of("DRAFT", "APPROVED", "REJECTED").contains(status);
            case "SERVICE_ACCEPTANCE_ACT" -> List.of("DRAFT", "REJECTED").contains(status);
            case "SERVICE_CLAIM" -> List.of("SENT", "UNDER_RESOLUTION").contains(status);
            case "WORK_COMPLETION_ACT" -> List.of("DRAFT", "REJECTED").contains(status);
            default -> false;
        };
    }

    private boolean supplierAttachmentTypeAllowed(String entityType, String attachmentType) {
        if (attachmentType == null) {
            return false;
        }
        return switch (entityType) {
            case "SERVICE_CLAIM" -> Set.of("CLAIM_DOCUMENT", "PHOTO", "SUPPLIER_RESPONSE").contains(attachmentType);
            case "SERVICE_REQUEST", "SERVICE_EXECUTION", "SERVICE_ORDER", "SERVICE_ACCEPTANCE_ACT", "WORK_COMPLETION_ACT" ->
                Set.of("CONTRACTOR_REPORT", "PHOTO", "SUPPLIER_DOCUMENT").contains(attachmentType);
            default -> false;
        };
    }

    private boolean deleteAllowed(String entityType, String status) {
        if (status == null) {
            return false;
        }
        return switch (entityType) {
            case "SERVICE_REQUEST" -> List.of("DRAFT", "RETURNED").contains(status);
            case "SERVICE_EXECUTION" -> List.of("NOT_STARTED", "IN_PROGRESS", "PAUSED", "REJECTED").contains(status);
            case "SERVICE_ORDER" -> List.of("DRAFT", "REJECTED").contains(status);
            case "SERVICE_ACCEPTANCE_ACT" -> List.of("DRAFT", "REJECTED").contains(status);
            case "SERVICE_CLAIM" -> "CREATED".equals(status);
            case "WORK_COMPLETION_ACT" -> List.of("DRAFT", "REJECTED").contains(status);
            default -> false;
        };
    }

    private boolean hasAnyRole(Set<String> expected) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            return false;
        }
        return authentication.getAuthorities().stream()
            .map(authority -> normalizeRole(authority == null ? null : authority.getAuthority()))
            .filter(Objects::nonNull)
            .anyMatch(expected::contains);
    }

    private boolean hasRole(String role) {
        return hasAnyRole(Set.of(role));
    }

    private String currentUsername() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getName();
    }

    private void requireOwnProfile(UUID userId) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
            || !(authentication.getCredentials() instanceof AuthenticatedTokenState tokenState)
            || !tokenState.userId().equals(userId)) {
            throw new AccessDeniedException("Only your own profile photo is accessible");
        }
    }

    private boolean isSupplierSession() {
        String username = currentUsername();
        return username != null && username.startsWith("supplier:");
    }

    private String normalizeRole(String value) {
        if (value == null) {
            return null;
        }
        String role = value.startsWith("ROLE_") ? value.substring("ROLE_".length()) : value;
        return role.toUpperCase(Locale.ROOT);
    }
}
