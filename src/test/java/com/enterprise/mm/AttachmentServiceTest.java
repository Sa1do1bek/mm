package com.enterprise.mm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.ConflictException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.entity.ProcurementMethodDecision;
import com.enterprise.mm.entity.ServiceRequest;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.PurchaseOrder;
import com.enterprise.mm.entity.PurchaseOrderItem;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.entity.SupplierPortalAccount;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.entity.WorkCompletionAct;
import com.enterprise.mm.entity.InventoryDocument;
import com.enterprise.mm.enums.ProcurementLineType;
import com.enterprise.mm.enums.RequestType;
import com.enterprise.mm.enums.WorkInspectionResult;
import com.enterprise.mm.payload.AttachmentDto;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.ProcurementMethodDecisionRepository;
import com.enterprise.mm.repository.PurchaseOrderItemRepository;
import com.enterprise.mm.repository.PurchaseOrderRepository;
import com.enterprise.mm.repository.ServiceRequestRepository;
import com.enterprise.mm.repository.SupplierPortalAccountRepository;
import com.enterprise.mm.repository.SupplierRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.repository.WorkCompletionActRepository;
import com.enterprise.mm.repository.InventoryDocumentRepository;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.service.AttachmentService;
import com.enterprise.mm.service.InventoryAiDraftService;
import com.enterprise.mm.service.InventoryDocumentService;
import com.enterprise.mm.payload.InventoryDocumentCreateRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@WithMockUser(username = "tester", roles = "ADMIN")
class AttachmentServiceTest {
    @Autowired AttachmentService attachmentService;
    @Autowired InventoryAiDraftService inventoryAiDraftService;
    @Autowired InventoryDocumentRepository inventoryDocumentRepository;
    @Autowired AttachmentRepository attachmentRepository;
    @Autowired InventoryDocumentService inventoryDocumentService;

    @Test
    @Transactional
    void resourceVersionAttachmentIsVisibleToJdbcBeforeVersionInsert() {
        var file = new MockMultipartFile("file", "order.pdf", "application/pdf",
            "%PDF-1.7\nsource".getBytes(StandardCharsets.US_ASCII));
        var uploaded = attachmentService.uploadResourceVersion(UUID.randomUUID(), file);

        Long persisted = jdbc.queryForObject("SELECT count(*) FROM attachments WHERE id=?", Long.class,
            uploaded.getId());
        assertThat(persisted).isEqualTo(1L);
    }

    @Test
    void inventoryDraftDocumentsUseOwnerScopedUploadRoute() {
        var draft = inventoryAiDraftService.create();
        var pdf = new MockMultipartFile("file", "order.pdf", "application/pdf",
            "%PDF-1.7\nsource".getBytes(StandardCharsets.US_ASCII));
        var uploaded = inventoryAiDraftService.upload(draft.id(), pdf);
        assertThat(inventoryAiDraftService.files(draft.id())).extracting(AttachmentDto::getId).contains(uploaded.getId());
        assertThatThrownBy(() -> attachmentService.upload("INVENTORY_AI_DRAFT", draft.id(), "SOURCE_DOCUMENT", pdf))
            .isInstanceOf(AccessDeniedException.class);
        inventoryAiDraftService.removeFile(draft.id(), uploaded.getId());
        assertThat(inventoryAiDraftService.files(draft.id())).isEmpty();
    }

    @Test
    void boundInventoryKeepsEveryDraftSourceDocument() {
        var draft = inventoryAiDraftService.create();
        var first = inventoryAiDraftService.upload(draft.id(), new MockMultipartFile("file", "first.pdf", "application/pdf",
            "%PDF-1.7\nfirst".getBytes(StandardCharsets.US_ASCII)));
        var second = inventoryAiDraftService.upload(draft.id(), new MockMultipartFile("file", "second.pdf", "application/pdf",
            "%PDF-1.7\nsecond".getBytes(StandardCharsets.US_ASCII)));
        InventoryDocument inventory = new InventoryDocument();
        inventory.setName("Review source binding");
        inventory.setStatus("PLANNED");
        inventory.setGlobalWarehouse(true);
        inventory = inventoryDocumentRepository.saveAndFlush(inventory);

        inventoryAiDraftService.bindToInventory(draft.id(), inventory.getId());

        var bound = attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "INVENTORY_DOCUMENT", inventory.getId());
        assertThat(bound).extracting(com.enterprise.mm.entity.Attachment::getId).containsExactlyInAnyOrder(first.getId(), second.getId());
        assertThat(bound).allMatch(file -> "SOURCE_DOCUMENT".equals(file.getAttachmentType()));
    }

    @Test
    void reviewedCreateBindsSourceFilesAndReplaysOneInventory() {
        var draft = inventoryAiDraftService.create();
        var source = inventoryAiDraftService.upload(draft.id(), new MockMultipartFile("file", "order.pdf", "application/pdf",
            "%PDF-1.7\norder".getBytes(StandardCharsets.US_ASCII)));
        InventoryDocumentCreateRequest request = new InventoryDocumentCreateRequest();
        request.setName("Annual inventory from reviewed order");
        request.setGlobalWarehouse(true);
        request.setSourceAiDraftId(draft.id());
        String key = UUID.randomUUID().toString();

        var created = inventoryDocumentService.create(request, key);
        var replayed = inventoryDocumentService.create(request, key);

        assertThat(replayed.getId()).isEqualTo(created.getId());
        assertThat(attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), "INVENTORY_DOCUMENT", created.getId()))
            .extracting(com.enterprise.mm.entity.Attachment::getId).containsExactly(source.getId());
    }
    @Autowired ServiceRequestRepository serviceRequestRepository;
    @Autowired SupplierRepository supplierRepository;
    @Autowired SupplierPortalAccountRepository supplierPortalAccountRepository;
    @Autowired PurchaseOrderRepository purchaseOrderRepository;
    @Autowired PurchaseOrderItemRepository purchaseOrderItemRepository;
    @Autowired ContractRepository contractRepository;
    @Autowired WorkCompletionActRepository workCompletionActRepository;
    @Autowired UserRepository userRepository;
    @Autowired ProcurementMethodDecisionRepository procurementMethodDecisionRepository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void uploadsDownloadsListsAndDeletesAttachment() throws Exception {
        UUID materialId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "../quality-passport.pdf",
            "application/pdf",
            "passport".getBytes(StandardCharsets.UTF_8)
        );

        var uploaded = attachmentService.upload("material", materialId, "quality_passport", file);
        assertThat(uploaded.getEntityType()).isEqualTo("MATERIAL");
        assertThat(uploaded.getOriginalFileName()).isEqualTo("quality-passport.pdf");
        assertThat(uploaded.getAttachmentType()).isEqualTo("QUALITY_PASSPORT");
        assertThat(uploaded.getChecksumSha256()).hasSize(64);

        assertThat(attachmentService.list("MATERIAL", materialId)).extracting("id").contains(uploaded.getId());

        var download = attachmentService.download(uploaded.getId());
        assertThat(download.metadata().getContentType()).isEqualTo("application/pdf");
        assertThat(new String(download.resource().getInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("passport");

        attachmentService.softDelete(uploaded.getId());
        assertThat(attachmentService.list("MATERIAL", materialId)).isEmpty();
    }

    @Test
    void uploadReplaysTheStoredAttachmentAndRejectsKeyReuseWithDifferentContent() {
        UUID materialId = UUID.randomUUID();
        MockMultipartFile original = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "first".getBytes(StandardCharsets.UTF_8)
        );
        MockMultipartFile replay = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "first".getBytes(StandardCharsets.UTF_8)
        );

        var uploaded = attachmentService.upload(
            "MATERIAL", materialId, "QUALITY_PASSPORT", original, "attachment-replay-" + materialId
        );
        var replayed = attachmentService.upload(
            "material", materialId, "quality-passport", replay, "attachment-replay-" + materialId
        );

        assertThat(replayed.getId()).isEqualTo(uploaded.getId());
        assertThat(attachmentService.list("MATERIAL", materialId))
            .extracting(AttachmentDto::getId)
            .containsExactly(uploaded.getId());

        MockMultipartFile changed = new MockMultipartFile(
            "file", "evidence.pdf", "application/pdf", "other".getBytes(StandardCharsets.UTF_8)
        );
        assertThatThrownBy(() -> attachmentService.upload(
            "MATERIAL", materialId, "QUALITY_PASSPORT", changed, "attachment-replay-" + materialId
        )).isInstanceOf(ConflictException.class)
            .hasMessageContaining("different request payload");
    }

    @Test
    void uploadIdempotencyKeyIsScopedToTheEvidenceTarget() {
        UUID firstMaterialId = UUID.randomUUID();
        UUID secondMaterialId = UUID.randomUUID();
        String key = "attachment-shared-target-key";

        var first = attachmentService.upload(
            "MATERIAL", firstMaterialId, "DOCUMENT", pdf(), key
        );
        var second = attachmentService.upload(
            "MATERIAL", secondMaterialId, "DOCUMENT", pdf(), key
        );

        assertThat(second.getId()).isNotEqualTo(first.getId());
        assertThat(attachmentService.list("MATERIAL", firstMaterialId)).hasSize(1);
        assertThat(attachmentService.list("MATERIAL", secondMaterialId)).hasSize(1);
    }

    @Test
    void rejectsUnsupportedContentType() {
        MockMultipartFile file = new MockMultipartFile(
            "file",
            "script.html",
            "text/html",
            "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)
        );

        assertThatThrownBy(() -> attachmentService.upload("material", UUID.randomUUID(), "photo", file))
            .isInstanceOf(BadRequestException.class)
            .hasMessageContaining("Unsupported file type");
    }

    @Test
    void nonexistentServiceEntityCannotReceiveEvidence() {
        MockMultipartFile file = pdf();

        assertThatThrownBy(() -> attachmentService.upload("SERVICE_REQUEST", UUID.randomUUID(), "ACT", file))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void procurementMethodDecisionCanReceiveEvidenceOnlyWhenDecisionExists() {
        assertThatThrownBy(() -> attachmentService.upload("PROCUREMENT_METHOD_DECISION", UUID.randomUUID(), "MARKET_RESEARCH", pdf()))
            .isInstanceOf(NotFoundException.class);

        ProcurementMethodDecision decision = procurementMethodDecision();

        var uploaded = attachmentService.upload("PROCUREMENT_METHOD_DECISION", decision.getId(), "MARKET_RESEARCH", pdf());

        assertThat(uploaded.getEntityType()).isEqualTo("PROCUREMENT_METHOD_DECISION");
        assertThat(uploaded.getEntityId()).isEqualTo(decision.getId());
        assertThat(uploaded.getAttachmentType()).isEqualTo("MARKET_RESEARCH");
        assertThat(attachmentService.list("PROCUREMENT_METHOD_DECISION", decision.getId()))
            .extracting(AttachmentDto::getId)
            .contains(uploaded.getId());
    }

    @Test
    void nonexistentServiceEntityCannotBeListedAsAnEvidenceContainer() {
        assertThatThrownBy(() -> attachmentService.list("SERVICE_REQUEST", UUID.randomUUID()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inaccessibleServiceEntityCannotListEvidence() {
        ScopedAttachment scoped = scopedAttachment();

        assertThatThrownBy(() -> attachmentService.list("SERVICE_REQUEST", scoped.requestId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inaccessibleServiceEntityCannotDownloadEvidence() {
        ScopedAttachment scoped = scopedAttachment();

        assertThatThrownBy(() -> attachmentService.download(scoped.attachmentId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void inaccessibleServiceEntityCannotDeleteEvidence() {
        ScopedAttachment scoped = scopedAttachment();

        assertThatThrownBy(() -> attachmentService.softDelete(scoped.attachmentId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierCannotListAnotherSuppliersWorkCompletionEvidence() {
        ScopedAttachment scoped = crossSupplierWorkAttachment(true);

        assertThatThrownBy(() -> attachmentService.list("WORK_COMPLETION_ACT", scoped.requestId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierCannotUploadEvidenceToAnotherSuppliersWorkCompletionAct() {
        ScopedAttachment scoped = crossSupplierWorkAttachment(false);

        assertThatThrownBy(() -> attachmentService.upload("WORK_COMPLETION_ACT", scoped.requestId(), "ACT", pdf()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierCannotDownloadAnotherSuppliersWorkCompletionEvidence() {
        ScopedAttachment scoped = crossSupplierWorkAttachment(true);

        assertThatThrownBy(() -> attachmentService.download(scoped.attachmentId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierCannotDeleteAnotherSuppliersWorkCompletionEvidence() {
        ScopedAttachment scoped = crossSupplierWorkAttachment(true);

        assertThatThrownBy(() -> attachmentService.softDelete(scoped.attachmentId()))
            .isInstanceOf(NotFoundException.class);
    }

    @Test
    void supplierCanListEvidenceForOwnWorkCompletionAct() {
        ScopedAttachment scoped = workAttachment(true, true);

        assertThat(attachmentService.list("WORK_COMPLETION_ACT", scoped.requestId()))
            .extracting("id")
            .contains(scoped.attachmentId());
    }

    @Test
    void viewerCanReadOwnedRequestButCannotUploadServiceEvidence() {
        User viewer = user("viewer-" + shortId().toLowerCase(java.util.Locale.ROOT));
        ServiceRequest request = serviceRequest(viewer.getId());
        authenticate(viewer.getUsername(), "VIEWER");

        assertThat(attachmentService.list("SERVICE_REQUEST", request.getId())).isEmpty();
        assertThatThrownBy(() -> attachmentService.upload("SERVICE_REQUEST", request.getId(), "ACT", pdf()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void auditorCanReadButCannotUploadOrDeleteServiceEvidence() {
        ServiceRequest request = serviceRequest();
        authenticate("admin", "ADMIN");
        var attachment = attachmentService.upload("SERVICE_REQUEST", request.getId(), "ACT", pdf());
        authenticate("evidence-auditor", "AUDITOR");

        assertThat(attachmentService.list("SERVICE_REQUEST", request.getId()))
            .extracting("id")
            .contains(attachment.getId());
        assertThatThrownBy(() -> attachmentService.upload("SERVICE_REQUEST", request.getId(), "ACT", pdf()))
            .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> attachmentService.softDelete(attachment.getId()))
            .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void supplierCanUploadOwnDraftWorkEvidenceButCannotDeleteAuthoritativeEvidence() {
        ScopedAttachment scoped = workAttachment(false, true);

        var uploaded = attachmentService.upload("WORK_COMPLETION_ACT", scoped.requestId(), "CONTRACTOR_REPORT", pdf());

        assertThatThrownBy(() -> attachmentService.softDelete(uploaded.getId()))
            .isInstanceOf(AccessDeniedException.class);
        assertThat(attachmentService.list("WORK_COMPLETION_ACT", scoped.requestId()))
            .extracting("id")
            .contains(uploaded.getId());
    }

    @Test
    void supplierCannotSpoofAuthoritativeActEvidenceForOwnWork() {
        ScopedAttachment scoped = workAttachment(false, true);

        assertThatThrownBy(() -> attachmentService.upload("WORK_COMPLETION_ACT", scoped.requestId(), "ACT", pdf()))
            .isInstanceOf(AccessDeniedException.class);
    }

    private ScopedAttachment scopedAttachment() {
        ServiceRequest request = serviceRequest();
        authenticate("admin", "ADMIN");
        var attachment = attachmentService.upload("SERVICE_REQUEST", request.getId(), "ACT", pdf());
        authenticate("unrelated-viewer", "VIEWER");
        return new ScopedAttachment(request.getId(), attachment.getId());
    }

    private ScopedAttachment crossSupplierWorkAttachment(boolean uploadEvidence) {
        return workAttachment(uploadEvidence, false);
    }

    private ScopedAttachment workAttachment(boolean uploadEvidence, boolean ownSupplier) {
        Supplier linkedSupplier = supplier("Linked supplier");
        Supplier workSupplier = ownSupplier ? linkedSupplier : supplier("Work supplier");
        String login = "supplier-" + UUID.randomUUID() + "@vendor.test";
        SupplierPortalAccount account = new SupplierPortalAccount();
        account.setCode("SPA-" + shortId());
        account.setName("Linked supplier portal account");
        account.setStatus("ACTIVE");
        account.setSupplierId(linkedSupplier.getId());
        account.setLoginEmail(login);
        supplierPortalAccountRepository.save(account);

        Contract contract = new Contract();
        contract.setCode("CTR-" + shortId());
        contract.setName("Works contract");
        contract.setStatus("ACTIVE");
        contract.setSupplierId(workSupplier.getId());
        contract.setLimitAmount(BigDecimal.valueOf(100));
        contract = contractRepository.save(contract);

        PurchaseOrder order = new PurchaseOrder();
        order.setCode("PO-" + shortId());
        order.setName("Works purchase order");
        order.setStatus("SENT");
        order.setSupplierId(workSupplier.getId());
        order.setContractId(contract.getId());
        order.setRequestType(RequestType.SERVICE);
        order.setTotalAmount(BigDecimal.valueOf(100));
        order = purchaseOrderRepository.save(order);

        PurchaseOrderItem item = new PurchaseOrderItem();
        item.setCode("POI-" + shortId());
        item.setName("Works line");
        item.setStatus("OPEN");
        item.setPurchaseOrderId(order.getId());
        item.setLineType(ProcurementLineType.WORK);
        item.setQuantity(BigDecimal.TEN);
        item.setUnitPrice(BigDecimal.TEN);
        item = purchaseOrderItemRepository.save(item);

        WorkCompletionAct act = new WorkCompletionAct();
        act.setCode("WCA-" + shortId());
        act.setName("Other supplier work act");
        act.setStatus("DRAFT");
        act.setPurchaseOrderId(order.getId());
        act.setPurchaseOrderItemId(item.getId());
        act.setContractId(contract.getId());
        act.setCompletedQuantity(BigDecimal.ONE);
        act.setAmount(BigDecimal.TEN);
        act.setInspectionResult(WorkInspectionResult.PASSED);
        act = workCompletionActRepository.save(act);

        UUID attachmentId = null;
        if (uploadEvidence) {
            authenticate("admin", "ADMIN");
            attachmentId = attachmentService.upload("WORK_COMPLETION_ACT", act.getId(), "ACT", pdf()).getId();
        }
        authenticate(login, "SUPPLIER_MANAGER");
        return new ScopedAttachment(act.getId(), attachmentId);
    }

    private Supplier supplier(String name) {
        Supplier supplier = new Supplier();
        supplier.setCode("SUP-" + shortId());
        supplier.setName(name);
        supplier.setLegalName(name + " LLC");
        supplier.setStatus("ACTIVE");
        return supplierRepository.save(supplier);
    }

    private String shortId() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase(java.util.Locale.ROOT);
    }

    private ServiceRequest serviceRequest() {
        return serviceRequest(null);
    }

    private ServiceRequest serviceRequest(UUID responsibleUserId) {
        ServiceRequest request = new ServiceRequest();
        request.setCode("SR-ATT-" + UUID.randomUUID().toString().substring(0, 8));
        request.setName("Scoped service evidence");
        request.setStatus("DRAFT");
        request.setCreatedBy("request-owner");
        request.setResponsibleUserId(responsibleUserId);
        return serviceRequestRepository.save(request);
    }

    private ProcurementMethodDecision procurementMethodDecision() {
        ProcurementMethodDecision decision = new ProcurementMethodDecision();
        decision.setCode("PMD-ATT-" + shortId());
        decision.setName("Attachment-backed PMD");
        decision.setStatus("DRAFT");
        decision.setDecisionNumber(decision.getCode());
        decision.setSelectedMethod("OPEN_TENDER");
        decision.setEstimatedAmount(BigDecimal.valueOf(100));
        decision.setCurrencyCode("UZS");
        decision.setPotentialSupplierCount(2);
        return procurementMethodDecisionRepository.save(decision);
    }

    private User user(String username) {
        jdbc.update("""
            insert into companies (id, tenant_id, company_id, created_at, version, is_delete, name,
                status, non_moving_stock_threshold_months, non_moving_stock_auto_detect_enabled)
            select ?, 'default', ?, current_timestamp, 0, false, 'Default test company', 'ACTIVE', 12, false
            where not exists (select 1 from companies where id = ?)
            """, CompanyContext.DEFAULT_COMPANY_ID, CompanyContext.DEFAULT_COMPANY_ID,
            CompanyContext.DEFAULT_COMPANY_ID);
        User user = new User();
        user.setCode("USR-" + shortId());
        user.setName(username);
        user.setStatus("ACTIVE");
        user.setUsername(username);
        user.setPasswordHash("test-only");
        return userRepository.save(user);
    }

    private MockMultipartFile pdf() {
        return new MockMultipartFile(
            "file",
            "service-act.pdf",
            "application/pdf",
            "service evidence".getBytes(StandardCharsets.UTF_8)
        );
    }

    private void authenticate(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                username,
                "n/a",
                java.util.List.of(new SimpleGrantedAuthority("ROLE_" + role))
            )
        );
    }

    private record ScopedAttachment(UUID requestId, UUID attachmentId) {
    }
}
