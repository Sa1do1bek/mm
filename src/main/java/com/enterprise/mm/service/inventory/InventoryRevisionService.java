package com.enterprise.mm.service.inventory;

import com.enterprise.mm.entity.*;
import com.enterprise.mm.enums.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.*;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service @RequiredArgsConstructor @Transactional(propagation = Propagation.MANDATORY)
public class InventoryRevisionService {
    private final InventoryAccessService access;
    private final ApprovalInstanceRepository instances;
    private final ApprovalTaskRepository tasks;
    private final ApprovalHistoryRepository history;
    private final EntityManager em;

    public InventoryDocument lock(UUID id) {
        var d = access.document(id);
        if (em.getLockMode(d) != LockModeType.PESSIMISTIC_WRITE) em.refresh(d, LockModeType.PESSIMISTIC_WRITE);
        access.requireRead(d);
        return d;
    }
    public long invalidate(UUID id, String reason) {
        var d = lock(id);
        if (java.util.Set.of("CLOSED", "CANCELLED").contains(d.getStatus()) || "ADJUSTMENT_POSTED".equals(d.getStatus()))
            throw new BusinessException("Posted, closed or cancelled counts cannot be changed");
        instances.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(d.getTenantId(), d.getCompanyId(), "INVENTORY_DOCUMENT", id).ifPresent(i -> {
            tasks.findByTenantIdAndApprovalInstanceIdAndIsDeletedFalseOrderByStepOrder(d.getTenantId(), i.getId()).stream()
                .filter(t -> t.getTaskStatus() == ApprovalTaskStatus.PENDING).forEach(t -> { t.setTaskStatus(ApprovalTaskStatus.CANCELLED); t.setCommentText(reason); });
            if (i.getApprovalStatus() == ApprovalInstanceStatus.PENDING) { i.setApprovalStatus(ApprovalInstanceStatus.CANCELLED); i.setStatus("CANCELLED"); }
            i.setDeleted(true);
            var h = new ApprovalHistory(); h.setTenantId(d.getTenantId()); h.setCompanyId(d.getCompanyId()); h.setApprovalInstanceId(i.getId());
            h.setAction(ApprovalHistoryAction.CANCELLED); h.setActor(access.username()); h.setCommentText(reason); h.setActionAt(LocalDateTime.now()); history.save(h);
            instances.saveAndFlush(i);
        });
        d.setApprovalInstanceId(null); d.setSubmittedCountRevision(null); d.setApprovedCountRevision(null);
        d.setCommissionApprovedRevision(null); d.setCommissionApprovedBy(null); d.setCommissionApprovedAt(null);
        d.setFinanceApprovedRevision(null); d.setFinanceApprovedBy(null); d.setFinanceApprovedAt(null);
        d.setCountRevision(d.getCountRevision() + 1);
        return d.getCountRevision();
    }
    public static void requireEditable(InventoryDocument d) {
        if (d.getSnapshotAt() == null || !InventoryStatus.from(d.getStatus()).countable())
            throw new BusinessException("Return this count for correction before changing its reviewed revision");
    }
}
