package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAnalyzeRequest;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAnalyzeResponse;
import com.enterprise.mm.payload.DocumentAiDtos.DocumentAiAttachmentDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.repository.AttachmentRepository;
import com.enterprise.mm.entity.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class DocumentAiAnalysisService {
    private static final Set<String> AI_PROVIDER_SYSTEMS = Set.of("CHATGPT", "CLAUDE", "GEMINI");
    private static final Set<String> CLOSED_STATUSES = Set.of("CLOSED", "CANCELLED", "CANCELED", "VOID", "REJECTED", "REVOKED");
    private static final Map<String, Class<? extends BusinessEntity>> DOCUMENT_TYPES = documentTypes();
    @PersistenceContext
    private EntityManager entityManager;
    private final AttachmentRepository attachmentRepository;
    private final com.enterprise.mm.service.inventory.InventoryAccessService inventoryAccess;
    private final ObjectProvider<IntegrationSettingService> integrationSettingServiceProvider;
    private final ContractAiJsonSupport jsonSupport;

    @Transactional(readOnly = true)
    public DocumentAiAnalyzeResponse analyze(DocumentAiAnalyzeRequest request) {
        if (request == null || request.entityId() == null) {
            throw new BadRequestException("Тип и ID документа обязательны");
        }
        String entityType = normalized(request.entityType());
        Class<? extends BusinessEntity> entityClass = DOCUMENT_TYPES.get(entityType);
        if (entityClass == null) {
            throw new BadRequestException("AI-анализ не поддерживает тип документа: " + request.entityType());
        }
        BusinessEntity document = entityManager.find(entityClass, request.entityId());
        if (!belongsToCurrentScope(document)) {
            throw new NotFoundException("Document not found: " + request.entityId());
        }
        if (document instanceof InventoryDocument inventory) inventoryAccess.requireVisibleExport(inventory);
        String provider = aiProvider(request.providerSystem());
        boolean includeAttachments = request.includeAttachments() == null || request.includeAttachments();
        boolean includeRelated = request.includeRelatedDocuments() == null || request.includeRelatedDocuments();
        RelatedDocuments related = includeRelated ? related(document) : new RelatedDocuments(null, null, null, null);
        List<DocumentAiAttachmentDto> attachments = includeAttachments ? attachments(entityType, document) : List.of();
        List<ContractAiFindingDto> ruleFindings = ruleFindings(entityType, document, related, attachments);
        List<ContractAiRecommendationDto> ruleRecommendations = recommendations(ruleFindings);
        Map<String, Object> context = context(entityType, document, related, attachments, ruleFindings);
        AiPromptResponse ai = runAi(provider, context);
        List<ContractAiFindingDto> findings = jsonSupport.mergeFindings(ruleFindings, ai == null ? null : ai.response());
        List<ContractAiRecommendationDto> recommendations = jsonSupport.mergeRecommendations(ruleRecommendations, ai == null ? null : ai.response());
        return new DocumentAiAnalyzeResponse(
                provider,
                ai == null ? "DISABLED" : ai.status(),
                ai == null ? "AI provider service is not available" : ai.message(),
                ai == null ? null : ai.model(),
                entityType,
                document.getId(),
                documentNumber(document),
                firstNonBlank(document.getName(), document.getCode()),
                document.getStatus(),
                summary(entityType, document, findings),
                extractedFields(document),
                attachments,
                findings,
                recommendations,
                context,
                LocalDateTime.now()
        );
    }
    private List<ContractAiFindingDto> ruleFindings(String entityType, BusinessEntity document, RelatedDocuments related, List<DocumentAiAttachmentDto> attachments) {
        List<ContractAiFindingDto> findings = new ArrayList<>();
        LocalDate today = LocalDate.now();
        if (blank(document.getStatus())) {
            findings.add(finding("WARNING", "STATUS", "Не указан статус документа", "Для контроля workflow документ должен иметь статус."));
        }
        if (document instanceof ContractInvoice invoice) {
            if (nvl(invoice.getAmount()).signum() <= 0) findings.add(finding("WARNING", "AMOUNT", "Некорректная сумма счет-фактуры", "Сумма должна быть больше нуля."));
            if (invoice.getDueDate() != null && invoice.getDueDate().isBefore(today) && !CLOSED_STATUSES.contains(status(invoice))) findings.add(finding("CRITICAL", "DUE_DATE", "Счет-фактура просрочена", "Дата оплаты " + invoice.getDueDate() + " уже прошла."));
            if (related.contract() != null && nvl(related.contract().getLimitAmount()).signum() > 0 && nvl(invoice.getAmount()).compareTo(nvl(related.contract().getLimitAmount())) > 0) findings.add(finding("CRITICAL", "CONTRACT_LIMIT", "Сумма выше лимита договора", "Счет-фактура превышает лимит связанного договора."));
        } else if (document instanceof ContractPowerOfAttorney attorney) {
            if (blank(attorney.getIssuedTo())) findings.add(finding("WARNING", "AUTHORIZATION", "Не указано доверенное лицо", "В доверенности нужно заполнить получателя/представителя."));
            if (attorney.getValidTo() != null && attorney.getValidTo().isBefore(today)) findings.add(finding("CRITICAL", "VALIDITY", "Доверенность истекла", "Срок действия доверенности истек " + attorney.getValidTo() + "."));
            else if (attorney.getValidTo() != null && !attorney.getValidTo().isAfter(today.plusDays(10))) findings.add(finding("WARNING", "VALIDITY", "Доверенность скоро истечет", "До окончания срока действия меньше 10 дней."));
        } else if (document instanceof ContractReconciliationAct act) {
            if (nvl(act.getClosingBalance()).compareTo(BigDecimal.ZERO) != 0) findings.add(finding("WARNING", "RECONCILIATION", "Есть расхождение по акту сверки", "Closing balance: " + nvl(act.getClosingBalance()) + "."));
            if (act.getPeriodStart() != null && act.getPeriodEnd() != null && act.getPeriodStart().isAfter(act.getPeriodEnd())) findings.add(finding("CRITICAL", "PERIOD", "Некорректный период акта", "Начало периода позже окончания."));
        } else if (document instanceof ContractPayment payment) {
            if (nvl(payment.getAmount()).signum() <= 0) findings.add(finding("WARNING", "AMOUNT", "Некорректная сумма оплаты", "Сумма оплаты должна быть больше нуля."));
            if (payment.getPaymentDate() != null && payment.getPaymentDate().isAfter(today)) findings.add(finding("WARNING", "DATE", "Дата оплаты в будущем", "Проверьте дату оплаты."));
            if (blank(payment.getBankReference())) findings.add(finding("INFO", "BANK", "Нет банковской ссылки", "Для сверки с казначейством желательно указать банковский референс."));
        } else if (document instanceof ContractAddendum addendum) {
            if ("POSTED".equals(status(addendum)) && addendum.getPostedAt() == null) findings.add(finding("WARNING", "POSTING", "Нет даты проведения", "Проведенное допсоглашение должно иметь дату проведения."));
            if (addendum.getNewEndDate() != null && related.contract() != null && related.contract().getStartDate() != null && addendum.getNewEndDate().isBefore(related.contract().getStartDate())) findings.add(finding("CRITICAL", "VALIDITY", "Новая дата окончания раньше начала договора", "Проверьте срок действия допсоглашения."));
        } else if (document instanceof SupplierLicense license) {
            if (license.getValidTo() != null && license.getValidTo().isBefore(today)) findings.add(finding("CRITICAL", "VALIDITY", "Лицензия истекла", "Срок лицензии истек " + license.getValidTo() + "."));
            else if (license.isRenewalRequired() && license.getValidTo() != null && !license.getValidTo().isAfter(today.plusDays(30))) findings.add(finding("WARNING", "RENEWAL", "Нужно продление лицензии", "До окончания срока меньше 30 дней."));
            if (license.getRevokedAt() != null) findings.add(finding("CRITICAL", "VALIDITY", "Лицензия отозвана", firstNonBlank(license.getRevocationReason(), "Есть дата отзыва лицензии.")));
        } else if (document instanceof SupplierLetter letter) {
            if (blank(letter.getSubject())) findings.add(finding("INFO", "CONTENT", "Нет темы письма", "Для поиска и аудита желательно указать тему."));
            if (letter.getLetterDate() != null && letter.getLetterDate().isAfter(today)) findings.add(finding("WARNING", "DATE", "Дата письма в будущем", "Проверьте дату письма."));
        } else if (document instanceof DeliverySchedule schedule) {
            if (schedule.isOverdueFlag() || (schedule.getScheduleDate() != null && schedule.getScheduleDate().isBefore(today) && !"RECEIVED".equals(status(schedule)))) findings.add(finding("WARNING", "DELIVERY", "Строка графика просрочена", "Плановая дата поставки требует контроля."));
        } else {
            genericRules(entityType, document, findings);
        }
        if (related.supplier() != null && "BLOCKED".equals(status(related.supplier()))) findings.add(finding("CRITICAL", "SUPPLIER", "Поставщик заблокирован", "Связанный поставщик имеет статус BLOCKED."));
        if (attachments.isEmpty()) findings.add(finding("INFO", "ATTACHMENTS", "Нет вложений", "Оригинал или подтверждающий файл не прикреплен к карточке."));
        if (findings.isEmpty()) findings.add(finding("INFO", "CONTROL", "Критичных отклонений не найдено", "По реквизитам документа критичные риски не выявлены."));
        return findings;
    }
    private void genericRules(String entityType, BusinessEntity document, List<ContractAiFindingDto> findings) {
        if (entityType.contains("PURCHASE") && "CANCELLED".equals(status(document))) findings.add(finding("INFO", "WORKFLOW", "Документ отменен", "Проверьте, нет ли связанных незакрытых операций."));
        if (document instanceof StockMovement movement && "POSTED".equals(status(movement)) && movement.getPostedAt() == null) findings.add(finding("WARNING", "POSTING", "Нет времени проведения", "Проведенное складское движение должно иметь postedAt."));
        if (document instanceof StockValuationDocument valuation && "POSTED".equals(status(valuation)) && valuation.getPostedAt() == null) findings.add(finding("WARNING", "POSTING", "Нет времени проведения оценки", "Проведенный документ оценки должен иметь postedAt."));
    }
    private List<ContractAiRecommendationDto> recommendations(List<ContractAiFindingDto> findings) {
        List<ContractAiRecommendationDto> out = new ArrayList<>();
        for (ContractAiFindingDto finding : findings) {
            String priority = "CRITICAL".equals(finding.severity()) ? "HIGH" : "WARNING".equals(finding.severity()) ? "MEDIUM" : "LOW";
            String action = switch (finding.category()) {
                case "DUE_DATE", "PAYMENT", "BANK" -> "Сверить оплату и банковские реквизиты";
                case "VALIDITY", "RENEWAL", "AUTHORIZATION" -> "Проверить срок действия и полномочия";
                case "RECONCILIATION", "PERIOD" -> "Провести сверку и исправить период";
                case "CONTRACT_LIMIT", "AMOUNT" -> "Проверить сумму и лимиты договора";
                case "ATTACHMENTS" -> "Прикрепить оригинал документа";
                default -> "Проверить документ ответственным пользователем";
            };
            out.add(new ContractAiRecommendationDto(priority, action, finding.details()));
        }
        return jsonSupport.distinctRecommendations(out);
    }
    private AiPromptResponse runAi(String provider, Map<String, Object> context) {
        IntegrationSettingService service = integrationSettingServiceProvider.getIfAvailable();
        if (service == null) return null;
        String prompt = """
            Ты ERP MM/FI эксперт. Проанализируй документ и связанные реквизиты.
            Верни только JSON {"findings":[{"severity":"CRITICAL|WARNING|INFO","category":"...","title":"...","details":"..."}],"recommendations":[{"priority":"HIGH|MEDIUM|LOW","action":"...","reason":"..."}]}.
            Не придумывай документы, которых нет в context, и не раскрывай секреты.
            """;
        try {
            return service.testAiProvider(ExternalIntegrationSystem.from(provider), new AiPromptRequest(prompt, context));
        } catch (RuntimeException ex) {
            return new AiPromptResponse(provider, provider, null, "AI_ERROR", ex.getMessage(), null, null, LocalDateTime.now());
        }
    }
    private RelatedDocuments related(BusinessEntity document) {
        ServiceOrder serviceOrder = findScoped(ServiceOrder.class, readUuid(document, "getServiceOrderId"));
        UUID contractId = firstUuid(readUuid(document, "getContractId"), serviceOrder == null ? null : serviceOrder.getContractId());
        Contract contract = findScoped(Contract.class, contractId);
        UUID supplierId = firstUuid(readUuid(document, "getSupplierId"), serviceOrder == null ? null : serviceOrder.getSupplierId(), contract == null ? null : contract.getSupplierId());
        Supplier supplier = findScoped(Supplier.class, supplierId);
        PurchaseOrder purchaseOrder = findScoped(PurchaseOrder.class, firstUuid(readUuid(document, "getPurchaseOrderId"), serviceOrder == null ? null : serviceOrder.getPurchaseOrderId()));
        return new RelatedDocuments(supplier, contract, purchaseOrder, serviceOrder);
    }
    private Map<String, Object> context(String entityType, BusinessEntity document, RelatedDocuments related, List<DocumentAiAttachmentDto> attachments, List<ContractAiFindingDto> findings) {
        return map(
                "tenantId", TenantContext.currentTenant(),
                "companyId", CompanyContext.currentCompany(),
                "entityType", entityType,
                "document", extractedFields(document),
                "related", map(
                        "supplier", related.supplier() == null ? null : extractedFields(related.supplier()),
                        "contract", related.contract() == null ? null : extractedFields(related.contract()),
                        "purchaseOrder", related.purchaseOrder() == null ? null : extractedFields(related.purchaseOrder()),
                        "serviceOrder", related.serviceOrder() == null ? null : extractedFields(related.serviceOrder())
                ),
                "attachments", attachments,
                "ruleFindings", findings
        );
    }
    private List<DocumentAiAttachmentDto> attachments(String entityType, BusinessEntity document) {
        List<String> candidates = List.of(entityType, document.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1_$2").toUpperCase(Locale.ROOT));
        Map<UUID, DocumentAiAttachmentDto> out = new LinkedHashMap<>();
        for (String candidate : candidates) {
            attachmentRepository.findByTenantIdAndCompanyIdAndEntityTypeAndEntityIdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), CompanyContext.currentCompany(), candidate, document.getId())
                    .forEach(attachment -> out.putIfAbsent(attachment.getId(), new DocumentAiAttachmentDto(attachment.getId(), attachment.getOriginalFileName(), attachment.getAttachmentType(), attachment.getContentType(), attachment.getSizeBytes(), attachment.getChecksumSha256())));
        }
        return new ArrayList<>(out.values());
    }
    private Map<String, Object> extractedFields(Object value) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Method method : value.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getName().startsWith("get") && !method.getName().equals("getClass")
                    && isSafeReturnType(method.getReturnType())) {
                try {
                    out.put(decap(method.getName().substring(3)), method.invoke(value));
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return out;
    }

    private boolean isSafeReturnType(Class<?> type) {
        if (type.isPrimitive() || type.isEnum()) {
            return true;
        }
        return String.class.isAssignableFrom(type)
                || Number.class.isAssignableFrom(type)
                || Boolean.class.isAssignableFrom(type)
                || Character.class.isAssignableFrom(type)
                || UUID.class.isAssignableFrom(type)
                || Temporal.class.isAssignableFrom(type);
    }
    private String summary(String entityType, BusinessEntity document, List<ContractAiFindingDto> findings) {
        long critical = findings.stream().filter(item -> "CRITICAL".equals(item.severity())).count();
        long warnings = findings.stream().filter(item -> "WARNING".equals(item.severity())).count();
        return documentLabel(entityType) + " " + firstNonBlank(documentNumber(document), document.getName(), document.getCode()) + ": критичных рисков " + critical + ", предупреждений " + warnings + ".";
    }
    private String documentNumber(BusinessEntity document) {
        return firstNonBlank(
                readString(document, "getInvoiceNumber"), readString(document, "getPaymentNumber"), readString(document, "getAttorneyNumber"),
                readString(document, "getActNumber"), readString(document, "getAddendumNumber"), readString(document, "getLetterNumber"),
                readString(document, "getLicenseNumber"), readString(document, "getCertificateNumber"), readString(document, "getContractNumber"),
                document.getCode()
        );
    }
    private static Map<String, Class<? extends BusinessEntity>> documentTypes() {
        Map<String, Class<? extends BusinessEntity>> out = new LinkedHashMap<>();
        register(out, ContractInvoice.class, "CONTRACT_INVOICE", "CONTRACT_INVOICES");
        register(out, ContractPowerOfAttorney.class, "CONTRACT_POWER_OF_ATTORNEY", "CONTRACT_POWERS_OF_ATTORNEY");
        register(out, ContractReconciliationAct.class, "CONTRACT_RECONCILIATION_ACT", "CONTRACT_RECONCILIATION_ACTS");
        register(out, ContractPayment.class, "CONTRACT_PAYMENT", "CONTRACT_PAYMENTS");
        register(out, ContractAddendum.class, "CONTRACT_ADDENDUM", "CONTRACT_ADDENDUMS");
        register(out, DeliverySchedule.class, "DELIVERY_SCHEDULE", "DELIVERY_SCHEDULES");
        register(out, SupplierLetter.class, "SUPPLIER_LETTER", "SUPPLIER_LETTERS");
        register(out, SupplierLicense.class, "SUPPLIER_LICENSE", "SUPPLIER_LICENSES");
        register(out, SupplierCertificate.class, "SUPPLIER_CERTIFICATE", "SUPPLIER_CERTIFICATES");
        register(out, PurchaseOrder.class, "PURCHASE_ORDER", "PURCHASE_ORDERS");
        register(out, PurchaseRequest.class, "PURCHASE_REQUEST", "PURCHASE_REQUESTS");
        register(out, RFQ.class, "RFQ", "RFQS");
        register(out, SupplierQuotation.class, "SUPPLIER_QUOTATION", "QUOTATION", "QUOTATIONS");
        register(out, ServiceOrder.class, "SERVICE_ORDER", "SERVICES");
        register(out, ServiceAcceptanceAct.class, "SERVICE_ACCEPTANCE_ACT", "SERVICE_ACCEPTANCE_ACTS");
        register(out, InventoryDocument.class, "INVENTORY_DOCUMENT", "INVENTORY");
        register(out, StockMovement.class, "STOCK_MOVEMENT", "STOCK_MOVEMENTS");
        register(out, StockValuationDocument.class, "STOCK_VALUATION_DOCUMENT", "VALUATION_DOCUMENT", "VALUATION_DOCUMENTS");
        register(out, NonMovingStock.class, "NON_MOVING_STOCK", "NON_MOVING_STOCKS");
        return out;
    }
    @SafeVarargs
    private static void register(Map<String, Class<? extends BusinessEntity>> out, Class<? extends BusinessEntity> type, String... aliases) {
        for (String alias : aliases) out.put(normalized(alias), type);
    }
    private <T extends BusinessEntity> T findScoped(Class<T> type, UUID id) {
        if (id == null) return null;
        T entity = entityManager.find(type, id);
        return belongsToCurrentScope(entity) ? entity : null;
    }
    private boolean belongsToCurrentScope(BusinessEntity entity) {
        return entity != null && !entity.isDeleted() && TenantContext.currentTenant().equals(entity.getTenantId()) && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private UUID readUuid(Object target, String methodName) {
        Object value = read(target, methodName);
        return value instanceof UUID id ? id : null;
    }
    private String readString(Object target, String methodName) {
        Object value = read(target, methodName);
        return value == null ? null : String.valueOf(value);
    }
    private Object read(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }
    private static UUID firstUuid(UUID... values) {
        for (UUID value : values) if (value != null) return value;
        return null;
    }
    private String aiProvider(String value) {
        String provider = firstNonBlank(value, "CHATGPT").replace('-', '_').toUpperCase(Locale.ROOT);
        if (!AI_PROVIDER_SYSTEMS.contains(provider)) throw new BadRequestException("Для AI-анализа доступны только CHATGPT, CLAUDE или GEMINI");
        return provider;
    }
    private static String normalized(String value) {
        return firstNonBlank(value).replace('-', '_').replace(' ', '_').toUpperCase(Locale.ROOT);
    }
    private String status(BusinessEntity entity) {
        return firstNonBlank(entity.getStatus()).toUpperCase(Locale.ROOT);
    }
    private String documentLabel(String entityType) {
        return entityType.replace('_', ' ');
    }
    private ContractAiFindingDto finding(String severity, String category, String title, String details) {
        return new ContractAiFindingDto(severity, category, title, details);
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length - 1; i += 2) out.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return out;
    }
    private static String decap(String value) {
        return value.isEmpty() ? value : value.substring(0, 1).toLowerCase(Locale.ROOT) + value.substring(1);
    }
    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
    private static String firstNonBlank(String... values) {
        if (values == null) return "";
        for (String value : values) if (!blank(value)) return value.trim();
        return "";
    }
    private record RelatedDocuments(Supplier supplier, Contract contract, PurchaseOrder purchaseOrder, ServiceOrder serviceOrder) {
    }
}