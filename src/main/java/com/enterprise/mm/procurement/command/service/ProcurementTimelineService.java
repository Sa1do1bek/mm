package com.enterprise.mm.procurement.command.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.AuditLog;
import com.enterprise.mm.procurement.command.payload.ProcurementCommandDtos.CommandTimelineEntry;
import com.enterprise.mm.repository.AuditLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class ProcurementTimelineService {

    private final AuditLogRepository auditLogRepository;

    public ProcurementTimelineService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional(readOnly = true)
    public Page<CommandTimelineEntry> timeline(UUID purchaseRequestId, Pageable pageable) {
        return auditLogRepository
            .findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByOccurredAtDesc(
                TenantContext.currentTenant(), CompanyContext.currentCompany(),
                "PurchaseRequest", purchaseRequestId, pageable)
            .map(this::toEntry);
    }

    private CommandTimelineEntry toEntry(AuditLog log) {
        return new CommandTimelineEntry(
            log.getOccurredAt(), log.getAction(), log.getFullName(), log.getEntityType(),
            log.getEntityId(), "procurement.command.timeline." + log.getAction(),
            log.getDetails() == null ? Map.of() : Map.of("details", log.getDetails()));
    }
}
