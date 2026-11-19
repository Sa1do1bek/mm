package com.enterprise.mm.service;

import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ProcurementCloseout;
import com.enterprise.mm.entity.ProcurementCloseoutAuditEvent;
import com.enterprise.mm.enums.RoleName;
import com.enterprise.mm.repository.ProcurementCloseoutAuditEventRepository;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ProcurementCloseoutAuditHistoryService {
    private final ProcurementCloseoutAuditEventRepository repository;
    private final NotificationService notificationService;

    @Transactional
    public void record(ProcurementCloseout closeout, String actionCode, String details, boolean notify) {
        if (closeout == null || closeout.getId() == null || actionCode == null || actionCode.isBlank()) {
            return;
        }
        String key = idempotencyKey(closeout, actionCode);
        if (repository.findByTenantIdAndCompanyIdAndIdempotencyKeyAndIsDeletedFalse(
            TenantContext.currentTenant(), closeout.getCompanyId(), key).isPresent()) {
            return;
        }
        ProcurementCloseoutAuditEvent event = new ProcurementCloseoutAuditEvent();
        event.setTenantId(closeout.getTenantId());
        event.setCompanyId(closeout.getCompanyId());
        event.setCode("PROC-CLOSEOUT-AUD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        event.setName("Procurement closeout " + actionCode);
        event.setStatus("POSTED");
        event.setCloseoutId(closeout.getId());
        event.setActionCode(actionCode);
        event.setActor(actor());
        event.setEventAt(LocalDateTime.now());
        event.setIdempotencyKey(key);
        event.setSnapshotJson(snapshot(closeout, actionCode, details));
        repository.save(event);
        if (notify) {
            notificationService.createForRole(
                RoleName.PROCUREMENT_MANAGER,
                "Procurement closeout " + actionCode,
                "Closeout " + display(closeout) + " changed to " + closeout.getCloseoutStatus(),
                "Procurement closeout " + actionCode,
                "ProcurementCloseout",
                closeout.getId()
            );
        }
    }

    private String idempotencyKey(ProcurementCloseout closeout, String actionCode) {
        return "PROC_CLOSEOUT:" + closeout.getId() + ":" + actionCode + ":" + (closeout.getVersion() == null ? 0 : closeout.getVersion());
    }

    private String snapshot(ProcurementCloseout closeout, String actionCode, String details) {
        return "{"
            + "\"closeoutId\":\"" + closeout.getId() + "\","
            + "\"action\":\"" + escape(actionCode) + "\","
            + "\"status\":\"" + (closeout.getCloseoutStatus() == null ? "" : closeout.getCloseoutStatus().name()) + "\","
            + "\"type\":\"" + (closeout.getCloseoutType() == null ? "" : closeout.getCloseoutType().name()) + "\","
            + "\"procedureId\":\"" + closeout.getProcurementProcedureId() + "\","
            + "\"purchaseOrderId\":\"" + (closeout.getPurchaseOrderId() == null ? "" : closeout.getPurchaseOrderId()) + "\","
            + "\"purchaseRequestId\":\"" + (closeout.getPurchaseRequestId() == null ? "" : closeout.getPurchaseRequestId()) + "\","
            + "\"details\":\"" + escape(details) + "\""
            + "}";
    }

    private String display(ProcurementCloseout closeout) {
        return closeout.getCode() == null || closeout.getCode().isBlank() ? closeout.getId().toString() : closeout.getCode();
    }

    private String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String actor() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || !authentication.isAuthenticated() || authentication.getName() == null ? "system" : authentication.getName();
    }
}
