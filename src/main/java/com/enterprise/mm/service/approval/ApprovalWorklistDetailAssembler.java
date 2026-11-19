package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Attachment;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.entity.IntegrationEvent;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailItem;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalDetailSection;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalHistoryResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalStepProgressView;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskDetailResponse;
import com.enterprise.mm.payload.ApprovalDtos.ApprovalTaskResponse;
import com.enterprise.mm.payload.ApprovalDtos.DemandStockReviewLineResponse;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.repository.AuditLogRepository;
import com.enterprise.mm.repository.IntegrationEventRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.SupplierAccreditationDocumentRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Builds an honest, section-by-section detail contract without inventing unavailable source data. */
@Component
public class ApprovalWorklistDetailAssembler {
    // Direct attachment identities belong here; an empty attachment list explicitly delegates
    // to a child-evidence resolver. Integration/audit identity remains the approval entityId.
    // NON_MOVING_STOCK_DECISION is intentionally
    // absent because its context is stored against the parent NonMovingStockCase ID.
    private static final Map<String, SourceContextMapping> SOURCE_CONTEXT_MAPPINGS = Map.ofEntries(
        Map.entry("DEMAND_PLAN", new SourceContextMapping(
            List.of("DEMAND_PLAN", "DEMAND_PLANS"), "DemandPlan"
        )),
        Map.entry("INVENTORY_DOCUMENT", new SourceContextMapping(
            List.of("INVENTORY_DOCUMENT"), "InventoryDocument"
        )),
        Map.entry("DAMAGED_STOCK", new SourceContextMapping(
            List.of("DAMAGED_STOCK"), "DAMAGED_STOCK"
        )),
        Map.entry("MANAGEMENT_OPPORTUNITY", new SourceContextMapping(
            List.of("MANAGEMENT_OPPORTUNITY", "MANAGEMENT_OPPORTUNITIES"), "ManagementOpportunity"
        )),
        Map.entry("PROCUREMENT_PURCHASE_ORDER", new SourceContextMapping(
            List.of("PURCHASEORDER", "PURCHASE_ORDER"), "PurchaseOrder"
        )),
        Map.entry("PURCHASE_REQUEST", new SourceContextMapping(
            List.of("PURCHASEREQUEST", "PURCHASE_REQUEST"), "PurchaseRequest"
        )),
        Map.entry("PROCUREMENT_METHOD_DECISION", new SourceContextMapping(
            List.of("PROCUREMENT_METHOD_DECISION"), "ProcurementMethodDecision"
        )),
        Map.entry("SUPPLIER_ACCREDITATION", new SourceContextMapping(
            List.of(), "SupplierAccreditationApplication"
        )),
        Map.entry("SERVICE_ACCEPTANCE_ACT", new SourceContextMapping(
            List.of("SERVICE_ACCEPTANCE_ACT"), "ServiceAcceptanceAct"
        )),
        Map.entry("SERVICE_FINANCE_APPROVAL", new SourceContextMapping(
            List.of("SERVICE_ACCEPTANCE_ACT"), "ServiceAcceptanceAct"
        )),
        Map.entry("SERVICE_REQUEST", new SourceContextMapping(
            List.of("SERVICE_REQUEST", "SERVICE_REQUESTS"), "ServiceRequest"
        )),
        Map.entry("STOCK_MOVEMENT", new SourceContextMapping(
            List.of("STOCK_MOVEMENT", "STOCK_MOVEMENT_DOCUMENT"), "StockMovement"
        )),
        Map.entry("WORK_COMPLETION_ACT", new SourceContextMapping(
            List.of("WORK_COMPLETION_ACT"), "WorkCompletionAct"
        ))
    );

    private final AttachmentRepository attachmentRepository;
    private final IntegrationEventRepository integrationEventRepository;
    private final AuditLogRepository auditLogRepository;
    private final ApprovalModuleRegistry moduleRegistry;
    private final UserRepository userRepository;
    private final SupplierAccreditationDocumentRepository accreditationDocumentRepository;

    /** Legacy/test path: source context sections remain explicitly partial without repositories. */
    public ApprovalWorklistDetailAssembler() {
        this(null, null, null, null, null);
    }

    /**
     * Compatibility path for focused context-section tests and non-Spring callers.
     * Document-line adapters remain explicitly unavailable until a registry is supplied.
     */
    public ApprovalWorklistDetailAssembler(
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        AuditLogRepository auditLogRepository
    ) {
        this(attachmentRepository, integrationEventRepository, auditLogRepository, null, null);
    }

    public ApprovalWorklistDetailAssembler(
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        AuditLogRepository auditLogRepository,
        ApprovalModuleRegistry moduleRegistry
    ) {
        this(attachmentRepository, integrationEventRepository, auditLogRepository, moduleRegistry, null);
    }

    public ApprovalWorklistDetailAssembler(
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        AuditLogRepository auditLogRepository,
        UserRepository userRepository
    ) {
        this(attachmentRepository, integrationEventRepository, auditLogRepository, null, userRepository);
    }

    public ApprovalWorklistDetailAssembler(
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        AuditLogRepository auditLogRepository,
        ApprovalModuleRegistry moduleRegistry,
        UserRepository userRepository
    ) {
        this(attachmentRepository, integrationEventRepository, auditLogRepository,
            moduleRegistry, userRepository, null);
    }

    @Autowired
    public ApprovalWorklistDetailAssembler(
        AttachmentRepository attachmentRepository,
        IntegrationEventRepository integrationEventRepository,
        AuditLogRepository auditLogRepository,
        ApprovalModuleRegistry moduleRegistry,
        UserRepository userRepository,
        SupplierAccreditationDocumentRepository accreditationDocumentRepository
    ) {
        this.attachmentRepository = attachmentRepository;
        this.integrationEventRepository = integrationEventRepository;
        this.auditLogRepository = auditLogRepository;
        this.moduleRegistry = moduleRegistry;
        this.userRepository = userRepository;
        this.accreditationDocumentRepository = accreditationDocumentRepository;
    }

    public ApprovalTaskDetailResponse assemble(
        ApprovalTaskResponse task,
        List<ApprovalHistoryResponse> approvalHistory
    ) {
        if (moduleRegistry != null && task.moduleKey() != null && task.entityId() != null) {
            moduleRegistry.adapter(task.moduleKey()).requireDetailRead(task.entityId());
        }
        return new ApprovalTaskDetailResponse(
            task,
            source(task),
            sourceLines(task),
            evidence(task),
            workflow(task),
            history(approvalHistory),
            partial("relatedDocuments", "No related-document adapter is registered for this approval source"),
            integrationImpact(task),
            auditTrail(task)
        );
    }

    private ApprovalDetailSection source(ApprovalTaskResponse task) {
        Map<String, Object> values = values(
            "moduleKey", task.moduleKey(),
            "entityId", task.entityId(),
            "entityName", task.entityName(),
            "sourceValue", task.sourceValue(),
            "currencyCode", task.currencyCode(),
            "sourceDataStatus", task.sourceDataStatus()
        );
        String status = task.moduleKey() == null || task.entityId() == null
            || !"READY".equals(task.sourceDataStatus()) ? "PARTIAL" : "READY";
        String message = "READY".equals(status) ? null : "Approval instance has incomplete source identity";
        return new ApprovalDetailSection(
            "source",
            status,
            message,
            List.of(new ApprovalDetailItem(
                task.entityId() == null ? task.id().toString() : task.entityId().toString(),
                task.entityName() == null ? task.moduleKey() : task.entityName(),
                values
            ))
        );
    }

    private ApprovalDetailSection sourceLines(ApprovalTaskResponse task) {
        // Prefer rich stock-review lines when the current step is DEMAND_STOCK_CHECK.
        if (task.demandStockReview() != null) {
            List<DemandStockReviewLineResponse> lines = task.demandStockReview().lines() == null
                ? List.of()
                : task.demandStockReview().lines();
            List<ApprovalDetailItem> items = lines.stream()
                .map(this::stockReviewLine)
                .toList();
            return new ApprovalDetailSection(
                "sourceLines",
                "READY",
                null,
                items
            );
        }
        // Otherwise load plain document lines via the module adapter (demand plan / PR / PO / …).
        if (moduleRegistry != null && task.moduleKey() != null && task.entityId() != null) {
            try {
                List<ApprovalDetailItem> items = moduleRegistry.adapter(task.moduleKey()).documentLines(task.entityId());
                if (items != null) {
                    return new ApprovalDetailSection("sourceLines", "READY", null, items);
                }
            } catch (RuntimeException ignored) {
                return unavailableSource(
                    "sourceLines",
                    "Позиции документа не удалось загрузить"
                );
            }
        }
        return partial(
            "sourceLines",
            "Позиции документа для этого типа согласования пока не подключены"
        );
    }

    private ApprovalDetailItem stockReviewLine(DemandStockReviewLineResponse line) {
        return new ApprovalDetailItem(
            line.demandPlanItemId() == null ? null : line.demandPlanItemId().toString(),
            line.materialName(),
            values(
                "materialId", line.materialId(),
                "unitOfMeasure", line.unitOfMeasureShortName(),
                "requestedQuantity", line.requestedQuantity(),
                "approvedQuantity", line.approvedQuantity(),
                "availableStock", line.availableStock(),
                "differenceQuantity", line.differenceQuantity(),
                "recommendedApprovedQuantity", line.recommendedApprovedQuantity(),
                "changeReason", line.changeReason()
            )
        );
    }

    private ApprovalDetailSection workflow(ApprovalTaskResponse task) {
        List<ApprovalStepProgressView> steps = task.steps() == null ? List.of() : task.steps();
        List<ApprovalDetailItem> items = steps.stream()
            .map(step -> {
                boolean parallelApprover = step.stepOrder() != null
                    && steps.stream().filter(candidate -> step.stepOrder().equals(candidate.stepOrder())).count() > 1
                    && step.assigneeUsername() != null
                    && !step.assigneeUsername().isBlank();
                String id = parallelApprover
                    ? "approver-" + step.assigneeUsername()
                    : (step.stepOrder() == null ? null : String.valueOf(step.stepOrder()));
                String label = parallelApprover
                    ? "Approver: " + preferred(step.assigneeName(), step.assigneeUsername())
                    : (step.stepOrder() == null ? "Approval step" : "Step " + step.stepOrder());
                return new ApprovalDetailItem(
                    id,
                    label,
                    values(
                        "status", step.status(),
                        "assigneeType", step.assigneeType(),
                        "assigneeName", step.assigneeName(),
                        "assigneeUsername", step.assigneeUsername(),
                        "assigneeRole", step.assigneeRole(),
                        "actedBy", preferred(step.actedByName(), step.actedBy()),
                        "actedAt", step.actedAt(),
                        "comment", step.commentText()
                    )
                );
            })
            .toList();
        return new ApprovalDetailSection(
            "workflow",
            steps.isEmpty() ? "PARTIAL" : "READY",
            steps.isEmpty() ? "Approval workflow progress is unavailable" : null,
            items
        );
    }

    private ApprovalDetailSection history(List<ApprovalHistoryResponse> approvalHistory) {
        List<ApprovalHistoryResponse> safeHistory = approvalHistory == null ? List.of() : approvalHistory;
        Map<String, String> userDisplayNames = userDisplayNames(
            safeHistory.stream().map(ApprovalHistoryResponse::actor).toList()
        );
        List<ApprovalDetailItem> items = safeHistory.stream()
            .map(event -> new ApprovalDetailItem(
                event.id() == null ? null : event.id().toString(),
                event.action() == null ? "Approval event" : event.action().name(),
                values(
                    "actor", userDisplayName(event.actor(), userDisplayNames),
                    "fromStepOrder", event.fromStepOrder(),
                    "toStepOrder", event.toStepOrder(),
                    "comment", event.commentText(),
                    "actionAt", event.actionAt()
                )
            ))
            .toList();
        return new ApprovalDetailSection("history", "READY", null, items);
    }

    private ApprovalDetailSection evidence(ApprovalTaskResponse task) {
        if (moduleRegistry != null && task.moduleKey() != null && task.entityId() != null) {
            try {
                var ownedEvidence = moduleRegistry.adapter(task.moduleKey()).evidence(task.entityId());
                if (ownedEvidence != null) return ready("evidence", ownedEvidence);
            } catch (RuntimeException unavailable) {
                return unavailableSource("evidence", "Evidence and attachments could not be loaded");
            }
        }
        SourceContextMapping mapping = mapping(task);
        if (mapping == null) {
            return unsupportedMapping("evidence");
        }
        if (attachmentRepository == null) {
            return unavailableSource("evidence", "Evidence repository is unavailable in this assembler path");
        }
        if ("SUPPLIER_ACCREDITATION".equals(ApprovalModuleRegistry.normalize(task.moduleKey()))) {
            return accreditationEvidence(task);
        }
        try {
            String tenantId = TenantContext.currentTenant();
            UUID companyId = CompanyContext.currentCompany();
            Map<UUID, Attachment> unique = new LinkedHashMap<>();
            for (String entityType : mapping.attachmentEntityTypes()) {
                attachmentRepository
                    .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                        tenantId, companyId, entityType, task.entityId()
                    )
                    .forEach(item -> unique.putIfAbsent(item.getId(), item));
            }
            List<ApprovalDetailItem> items = unique.values().stream()
                .sorted(Comparator.comparing(
                    Attachment::getUploadedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())
                ))
                .map(this::evidenceItem)
                .toList();
            return ready("evidence", items);
        } catch (RuntimeException ignored) {
            return unavailableSource("evidence", "Evidence and attachments could not be loaded");
        }
    }

    private ApprovalDetailItem evidenceItem(Attachment attachment) {
        return new ApprovalDetailItem(
            id(attachment.getId()),
            display(attachment.getOriginalFileName(), attachment.getName(), "Attachment"),
            values(
                "entityType", attachment.getEntityType(),
                "attachmentType", attachment.getAttachmentType(),
                "fileName", attachment.getOriginalFileName(),
                "contentType", attachment.getContentType(),
                "sizeBytes", attachment.getSizeBytes(),
                "uploadedAt", attachment.getUploadedAt(),
                "previewable", attachment.isPreviewable(),
                "downloadable", attachment.isDownloadable()
            )
        );
    }

    private ApprovalDetailSection accreditationEvidence(ApprovalTaskResponse task) {
        if (accreditationDocumentRepository == null) {
            return unavailableSource("evidence", "Accreditation evidence repository is unavailable");
        }
        try {
            String tenantId = TenantContext.currentTenant();
            UUID companyId = CompanyContext.currentCompany();
            List<ApprovalDetailItem> items = new ArrayList<>();
            boolean missingAttachment = false;
            for (var document : accreditationDocumentRepository.findApprovalEvidence(
                tenantId, companyId, task.entityId()
            )) {
                // The file belongs to the child document, never directly to the application.
                // Also match attachmentId: unrelated uploads to the child are not its evidence.
                Attachment linked = document.getAttachmentId() == null ? null : attachmentRepository
                    .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
                        tenantId, companyId, "SUPPLIER_ACCREDITATION_DOCUMENT", document.getId()
                    ).stream()
                    .filter(attachment -> document.getAttachmentId().equals(attachment.getId()))
                    .findFirst().orElse(null);
                Map<String, Object> metadata = new LinkedHashMap<>();
                if (linked != null) metadata.putAll(evidenceItem(linked).values());
                else {
                    missingAttachment = true;
                    metadata.put("downloadable", false);
                }
                metadata.putAll(values(
                    "documentType", document.getDocumentType(),
                    "status", document.getStatus(),
                    "validFrom", document.getValidFrom(),
                    "validTo", document.getValidTo(),
                    "verifiedAt", document.getVerifiedAt()
                ));
                items.add(new ApprovalDetailItem(
                    linked == null ? null : id(linked.getId()),
                    linked == null ? display(document.getName(), document.getDocumentType(), "Evidence")
                        : display(linked.getOriginalFileName(), linked.getName(), "Attachment"),
                    metadata
                ));
            }
            return new ApprovalDetailSection("evidence", missingAttachment ? "PARTIAL" : "READY",
                missingAttachment ? "Some accreditation documents have no accessible linked file" : null, items);
        } catch (RuntimeException ignored) {
            return unavailableSource("evidence", "Evidence and attachments could not be loaded");
        }
    }

    private ApprovalDetailSection integrationImpact(ApprovalTaskResponse task) {
        if (mapping(task) == null) {
            return unsupportedMapping("integrationImpact");
        }
        if (integrationEventRepository == null) {
            return unavailableSource(
                "integrationImpact",
                "Integration-event repository is unavailable in this assembler path"
            );
        }
        try {
            List<ApprovalDetailItem> items = integrationEventRepository
                .findByTenantIdAndCompanyIdAndAggregateIdAndIsDeletedFalseOrderByCreatedAtDesc(
                    TenantContext.currentTenant(), CompanyContext.currentCompany(), task.entityId()
                )
                .stream()
                .map(this::integrationItem)
                .toList();
            return ready("integrationImpact", items);
        } catch (RuntimeException ignored) {
            return unavailableSource("integrationImpact", "Integration impact could not be loaded");
        }
    }

    private ApprovalDetailItem integrationItem(IntegrationEvent event) {
        return new ApprovalDetailItem(
            id(event.getId()),
            display(event.getEventType(), "Integration event"),
            values(
                "eventType", event.getEventType(),
                "aggregateType", event.getAggregateType(),
                "status", event.getStatus(),
                "retryCount", event.getRetryCount(),
                "nextRetryAt", event.getNextRetryAt(),
                "lastAttemptAt", event.getLastAttemptAt(),
                "createdAt", event.getCreatedAt(),
                "hasError", event.getLastError() != null && !event.getLastError().isBlank()
            )
        );
    }

    private ApprovalDetailSection auditTrail(ApprovalTaskResponse task) {
        SourceContextMapping mapping = mapping(task);
        if (mapping == null) {
            return unsupportedMapping("auditTrail");
        }
        if (auditLogRepository == null) {
            return unavailableSource("auditTrail", "Audit-log repository is unavailable in this assembler path");
        }
        try {
            List<AuditLog> auditLogs = auditLogRepository
                .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtAsc(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    mapping.auditEntityType(),
                    task.entityId()
                );
            Map<String, String> userDisplayNames = userDisplayNames(
                auditLogs.stream().map(AuditLog::getUsername).toList()
            );
            List<ApprovalDetailItem> items = auditLogs
                .stream()
                .map(auditLog -> auditItem(auditLog, userDisplayNames))
                .toList();
            return ready("auditTrail", items);
        } catch (RuntimeException ignored) {
            return unavailableSource("auditTrail", "Audit trail could not be loaded");
        }
    }

    private ApprovalDetailItem auditItem(AuditLog auditLog, Map<String, String> userDisplayNames) {
        return new ApprovalDetailItem(
            id(auditLog.getId()),
            display(auditLog.getAction(), "Audit event"),
            values(
                "entityType", auditLog.getEntityType(),
                "action", auditLog.getAction(),
                "actor", userDisplayName(auditLog.getFullName(), userDisplayNames),
                "occurredAt", auditLog.getOccurredAt()
            )
        );
    }

    private SourceContextMapping mapping(ApprovalTaskResponse task) {
        if (task == null || task.entityId() == null) {
            return null;
        }
        return SOURCE_CONTEXT_MAPPINGS.get(ApprovalModuleRegistry.normalize(task.moduleKey()));
    }

    private ApprovalDetailSection unsupportedMapping(String key) {
        return partial(key, "No direct source-entity mapping is registered for this approval source");
    }

    private ApprovalDetailSection unavailableSource(String key, String message) {
        return partial(key, message);
    }

    private ApprovalDetailSection ready(String key, List<ApprovalDetailItem> items) {
        return new ApprovalDetailSection(key, "READY", null, items);
    }

    private ApprovalDetailSection partial(String key, String message) {
        return new ApprovalDetailSection(key, "PARTIAL", message, List.of());
    }

    private String id(UUID id) {
        return id == null ? null : id.toString();
    }

    private String display(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return "Context item";
    }

    private String preferred(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private Map<String, String> userDisplayNames(Collection<String> usernames) {
        if (userRepository == null || usernames == null) {
            return Map.of();
        }
        Map<String, String> resolved = new HashMap<>();
        for (String username : usernames) {
            String normalized = blank(username);
            if (normalized == null || resolved.containsKey(normalized.toLowerCase())) {
                continue;
            }
            userRepository
                .resolveActiveUserByUsername(TenantContext.currentTenant(), CompanyContext.currentCompany(), normalized)
                .map(this::displayName)
                .map(this::blank)
                .ifPresent(name -> resolved.put(normalized.toLowerCase(), name));
        }
        return resolved;
    }

    private String userDisplayName(String username, Map<String, String> userDisplayNames) {
        String normalized = blank(username);
        if (normalized == null) {
            return null;
        }
        return userDisplayNames.getOrDefault(normalized.toLowerCase(), normalized);
    }

    private String displayName(User user) {
        if (user == null) {
            return null;
        }
        return display(user.getFullName(), user.getName(), user.getUsername());
    }

    private String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private Map<String, Object> values(Object... keyValues) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < keyValues.length; index += 2) {
            Object value = keyValues[index + 1];
            if (value != null) {
                result.put(String.valueOf(keyValues[index]), value);
            }
        }
        return result;
    }

    private record SourceContextMapping(
        List<String> attachmentEntityTypes,
        String auditEntityType
    ) {
    }
}
