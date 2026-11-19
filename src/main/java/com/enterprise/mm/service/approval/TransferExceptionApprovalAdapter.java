package com.enterprise.mm.service.approval;

import com.enterprise.mm.common.*;
import com.enterprise.mm.entity.*;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.ApprovalDtos.*;
import com.enterprise.mm.repository.ApprovalInstanceRepository;
import com.enterprise.mm.service.FinancialAuthorizationService;
import com.enterprise.mm.service.transfer.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component @RequiredArgsConstructor @Transactional
public class TransferExceptionApprovalAdapter implements ApprovalModuleAdapter {
    public static final String MODULE_KEY="TRANSFER_EXCEPTION";
    private final TransferExceptionStateService state;
    private final FinancialAuthorizationService finance;
    private final ApprovalInstanceRepository instances;
    public String moduleKey(){return MODULE_KEY;}
    public UUID companyId(UUID id){return state.get(id).getCompanyId();}
    public String displayName(UUID id){return state.get(id).getName();}
    public void validateCanSubmit(UUID id) {
        var c=state.lock(id);
        if(!TransferActionPolicy.hasRole("ADMIN","MM_MANAGER","WAREHOUSE_MANAGER","WAREHOUSE_HEAD")) throw new BusinessException("Warehouse review authority is required");
        if(!Set.of("REVIEWED","PENDING_APPROVAL").contains(c.getStatus()) || !"CONFIRM_LOSS".equals(c.getDecision()) || c.getReviewedBy()==null)
            throw new BusinessException("Warehouse review of the loss is required before finance approval");
        if(c.getAttachmentIds().isEmpty()) throw new BusinessException("Loss approval requires attached evidence");
        state.validateEvidence(c.getTransferDocumentId(),c.getAttachmentIds());
    }
    public void markSubmitted(UUID id) {
        var c=state.lock(id); c.setStatus("PENDING_APPROVAL");c.setApprovalRevision(c.getRevision());
        c.setApprovalInstanceId(instances.findByTenantIdAndCompanyIdAndModuleKeyAndEntityIdAndIsDeletedFalse(c.getTenantId(),c.getCompanyId(),MODULE_KEY,id).orElseThrow().getId());
        state.history(c,"SUBMITTED","Finance approval requested for this revision");state.save(c);
    }
    public void lockApprovalRevision(UUID id, UUID approvalInstanceId, UUID taskId) {
        var c=state.lock(id); requireCurrent(c);
        if (!Objects.equals(c.getApprovalInstanceId(), approvalInstanceId)) throw new BusinessException("This approval cycle is stale");
        state.refreshApprovalTask(approvalInstanceId, taskId);
    }
    public void beforeApproveTask(UUID id,ApprovalFlowStep step,ApprovalTaskDecisionRequest request) {
        var c=state.lock(id); requireCurrent(c);
        validateFinalActor(TransferExceptionStateService.actor(),c.getCreatedBy(),finance.hasAnyRole("ADMIN","FINANCE_CONTROLLER","ACCOUNTANT"));
    }
    public Boolean isApprovalTaskActive(UUID id) {
        var c=state.get(id); return "PENDING_APPROVAL".equals(c.getStatus()) && Objects.equals(c.getApprovalRevision(),c.getRevision());
    }
    public void markApproved(UUID id,String comment) {
        var c=state.lock(id);requireCurrent(c);
        validateFinalActor(TransferExceptionStateService.actor(),c.getCreatedBy(),finance.hasAnyRole("ADMIN","FINANCE_CONTROLLER","ACCOUNTANT"));
        c.setFinanceApprovedBy(TransferExceptionStateService.actor());c.setStatus("APPROVED");state.history(c,"FINANCE_APPROVED",comment);state.save(c);
    }
    public void markRejected(UUID id,String comment){returned(id,"REJECTED",comment);}
    public void onReturned(UUID id,String comment){returned(id,"RETURNED_FOR_CORRECTION",comment);}
    public void onCancelled(UUID id,String comment){returned(id,"CANCELLED_APPROVAL",comment);}
    private void returned(UUID id,String action,String comment){
        var c=state.lock(id);requireCurrent(c);c.setStatus("OPEN");c.setApprovalRevision(null);c.setFinanceApprovedBy(null);c.setReviewedBy(null);c.setRevision(c.getRevision()+1);
        state.history(c,action,comment);state.save(c);
    }
    private void requireCurrent(TransferException c){
        if(!"PENDING_APPROVAL".equals(c.getStatus()) || !Objects.equals(c.getApprovalRevision(),c.getRevision()) || c.getQuantity().signum()<=0)
            throw new BusinessException("This transfer exception approval is stale");
    }
    public static void validateFinalActor(String actor,String reporter,boolean finance) {
        if(!finance) throw new BusinessException("Finance authority is required to approve a loss");
        if(actor==null || reporter==null || actor.equalsIgnoreCase(reporter)) throw new BusinessException("Loss approval requires an independent finance approver");
    }
    public List<ApprovalDetailItem> documentLines(UUID id){
        var c=state.get(id);var values=new HashMap<String,Object>();values.put("quantity",c.getQuantity());values.put("reason",c.getReason());values.put("revision",c.getRevision());
        values.put("attachmentIds",c.getAttachmentIds());if(finance.hasAnyRole("ADMIN","FINANCE_CONTROLLER","ACCOUNTANT")) values.put("bookValue",c.getBookValue());
        return List.of(new ApprovalDetailItem(c.getId().toString(),c.getName(),values));
    }
    public List<ApprovalDetailItem> evidence(UUID id) {
        return state.evidence(id).stream().map(a->{
            var values=new HashMap<String,Object>();
            values.put("entityType",a.getEntityType());values.put("fileName",a.getOriginalFileName());
            values.put("contentType",a.getContentType());values.put("sizeBytes",a.getSizeBytes());
            values.put("uploadedAt",a.getUploadedAt());values.put("previewable",a.isPreviewable());values.put("downloadable",a.isDownloadable());
            return new ApprovalDetailItem(a.getId().toString(),a.getOriginalFileName(),values);
        }).toList();
    }
}
