package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ContractAiDtos.ContractAiAnalyzeRequest;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiAnalyzeResponse;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiFindingDto;
import com.enterprise.mm.payload.ContractAiDtos.ContractAiRecommendationDto;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptRequest;
import com.enterprise.mm.payload.ExternalIntegrationDtos.AiPromptResponse;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.common.BusinessEntity;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.entity.Contract;
import com.enterprise.mm.entity.ContractAddendum;
import com.enterprise.mm.entity.ContractInvoice;
import com.enterprise.mm.entity.ContractPayment;
import com.enterprise.mm.entity.ContractPowerOfAttorney;
import com.enterprise.mm.entity.ContractReconciliationAct;
import com.enterprise.mm.entity.DeliverySchedule;
import com.enterprise.mm.entity.Supplier;
import com.enterprise.mm.repository.ContractAddendumRepository;
import com.enterprise.mm.repository.ContractInvoiceRepository;
import com.enterprise.mm.repository.ContractPaymentRepository;
import com.enterprise.mm.repository.ContractPowerOfAttorneyRepository;
import com.enterprise.mm.repository.ContractReconciliationActRepository;
import com.enterprise.mm.repository.ContractRepository;
import com.enterprise.mm.repository.DeliveryScheduleRepository;
import com.enterprise.mm.repository.SupplierRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
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
public class ContractAiAnalysisService {
    private static final Set<String> AI_PROVIDER_SYSTEMS = Set.of("CHATGPT", "CLAUDE", "GEMINI");
    private static final Set<String> CLOSED_STATUSES = Set.of("CLOSED", "CANCELLED", "CANCELED", "VOID", "REJECTED");
    private static final Set<String> PAID_STATUSES = Set.of("PAID", "CLOSED", "CANCELLED", "CANCELED", "VOID");
    private final ContractRepository contractRepository;
    private final SupplierRepository supplierRepository;
    private final ContractInvoiceRepository invoiceRepository;
    private final ContractPaymentRepository paymentRepository;
    private final ContractPowerOfAttorneyRepository attorneyRepository;
    private final ContractReconciliationActRepository reconciliationActRepository;
    private final ContractAddendumRepository addendumRepository;
    private final DeliveryScheduleRepository deliveryScheduleRepository;
    private final ObjectProvider<IntegrationSettingService> integrationSettingServiceProvider;
    private final ContractAiJsonSupport jsonSupport;

    @Transactional(readOnly = true)
    public ContractAiAnalyzeResponse analyze(UUID contractId, ContractAiAnalyzeRequest request) {
        if (contractId == null) {
            throw new BadRequestException("ID договора обязателен");
        }
        Contract contract = contractRepository.findByIdAndIsDeletedFalse(contractId)
            .filter(this::belongsToCurrentScope)
            .orElseThrow(() -> new NotFoundException("Contract not found: " + contractId));
        Supplier supplier = contract.getSupplier();
        if (supplier != null && !belongsToCurrentScope(supplier)) {
            supplier = null;
        }
        if (supplier == null) {
            supplier = supplierRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), contract.getSupplierId())
                .filter(this::belongsToCurrentScope)
                .orElse(null);
        }
        String provider = aiProvider(request == null ? null : request.providerSystem());
        boolean includeRelated = request == null || request.includeRelatedDocuments() == null || request.includeRelatedDocuments();
        boolean includeFinancials = request == null || request.includeFinancials() == null || request.includeFinancials();
        List<ContractInvoice> invoices = includeFinancials ? scoped(invoiceRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), contractId)) : List.of();
        List<ContractPayment> payments = includeFinancials ? scoped(paymentRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), contractId)) : List.of();
        List<ContractReconciliationAct> acts = includeFinancials ? scoped(reconciliationActRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), contractId)) : List.of();
        List<ContractPowerOfAttorney> attorneys = includeRelated ? scoped(attorneyRepository.findByTenantIdAndContract_IdAndIsDeletedFalseOrderByCreatedAtDesc(TenantContext.currentTenant(), contractId)) : List.of();
        List<ContractAddendum> addendums = includeRelated ? addendumRepository.findByTenantIdAndCompanyIdAndContract_IdAndIsDeletedFalseOrderByAddendumDateDesc(TenantContext.currentTenant(), CompanyContext.currentCompany(), contractId) : List.of();
        List<DeliverySchedule> schedules = includeRelated ? deliveryScheduleRepository.findByTenantIdAndCompanyIdAndContract_IdAndIsDeletedFalseOrderByScheduleDateAsc(TenantContext.currentTenant(), CompanyContext.currentCompany(), contractId) : List.of();
        ContractAiMetrics metrics = metrics(contract, invoices, payments, attorneys, schedules);
        List<ContractAiFindingDto> ruleFindings = ruleFindings(contract, supplier, metrics, acts, addendums);
        List<ContractAiRecommendationDto> ruleRecommendations = recommendations(ruleFindings, metrics);
        Map<String, Object> context = ContractAiContextBuilder.context(contract, supplier, metrics, invoices, payments, acts, attorneys, addendums, schedules, ruleFindings);
        AiPromptResponse ai = runAiContractAnalysis(provider, context);
        List<ContractAiFindingDto> findings = jsonSupport.mergeFindings(ruleFindings, ai == null ? null : ai.response());
        List<ContractAiRecommendationDto> recommendations = jsonSupport.mergeRecommendations(ruleRecommendations, ai == null ? null : ai.response());
        return new ContractAiAnalyzeResponse(
            provider,
            ai == null ? "DISABLED" : ai.status(),
            ai == null ? "AI provider service is not available" : ai.message(),
            ai == null ? null : ai.model(),
            contract.getId(),
            firstNonBlank(contract.getContractNumber(), contract.getCode(), contract.getName()),
            supplier == null ? null : firstNonBlank(supplier.getLegalName(), supplier.getShortName(), supplier.getName()),
            metrics.utilizationPercent(),
            metrics.totalInvoices(),
            metrics.totalPayments(),
            metrics.unpaidAmount(),
            metrics.overdueInvoices(),
            metrics.expiredPowersOfAttorney(),
            metrics.overdueSchedules(),
            findings,
            recommendations,
            context,
            LocalDateTime.now()
        );
    }
    private ContractAiMetrics metrics(
        Contract contract,
        List<ContractInvoice> invoices,
        List<ContractPayment> payments,
        List<ContractPowerOfAttorney> attorneys,
        List<DeliverySchedule> schedules
    ) {
        LocalDate today = LocalDate.now();
        BigDecimal limit = nvl(contract.getLimitAmount());
        BigDecimal used = nvl(contract.getUsedAmount());
        BigDecimal utilization = limit.signum() > 0
            ? used.multiply(BigDecimal.valueOf(100)).divide(limit, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;
        BigDecimal totalInvoices = invoices.stream().map(ContractInvoice::getAmount).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalPayments = payments.stream().map(ContractPayment::getAmount).map(this::nvl).reduce(BigDecimal.ZERO, BigDecimal::add);
        int overdueInvoices = (int) invoices.stream()
            .filter(invoice -> invoice.getDueDate() != null && invoice.getDueDate().isBefore(today))
            .filter(invoice -> !PAID_STATUSES.contains(status(invoice)))
            .count();
        int expiredPowersOfAttorney = (int) attorneys.stream()
            .filter(attorney -> attorney.getValidTo() != null && attorney.getValidTo().isBefore(today))
            .filter(attorney -> !CLOSED_STATUSES.contains(status(attorney)))
            .count();
        int overdueSchedules = (int) schedules.stream()
            .filter(schedule -> schedule.isOverdueFlag() || scheduleOverdue(schedule, today))
            .count();
        return new ContractAiMetrics(limit, used, utilization, totalInvoices, totalPayments, totalInvoices.subtract(totalPayments), overdueInvoices, expiredPowersOfAttorney, overdueSchedules);
    }
    private List<ContractAiFindingDto> ruleFindings(
        Contract contract,
        Supplier supplier,
        ContractAiMetrics metrics,
        List<ContractReconciliationAct> acts,
        List<ContractAddendum> addendums
    ) {
        List<ContractAiFindingDto> findings = new ArrayList<>();
        LocalDate today = LocalDate.now();
        String contractNo = firstNonBlank(contract.getContractNumber(), contract.getCode());
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(today)) {
            findings.add(finding("CRITICAL", "VALIDITY", "Срок договора истек", "Договор " + contractNo + " истек " + contract.getEndDate() + "; новые закупки нужно блокировать или продлить договор."));
        } else if (contract.getEndDate() != null && !contract.getEndDate().isAfter(today.plusDays(30))) {
            long daysLeft = ChronoUnit.DAYS.between(today, contract.getEndDate());
            findings.add(finding("WARNING", "VALIDITY", "Договор скоро истечет", "До окончания договора осталось " + daysLeft + " дней; проверьте пролонгацию или новый договор."));
        }
        if (metrics.limitAmount().signum() > 0 && metrics.usedAmount().compareTo(metrics.limitAmount()) > 0) {
            findings.add(finding("CRITICAL", "LIMIT", "Лимит договора превышен", "Использовано " + metrics.usedAmount() + " из " + metrics.limitAmount() + " " + contract.getCurrencyCode() + "."));
        } else if (metrics.utilizationPercent().compareTo(BigDecimal.valueOf(90)) >= 0) {
            findings.add(finding("WARNING", "LIMIT", "Лимит почти исчерпан", "Использование лимита: " + metrics.utilizationPercent() + "%; нужен контроль новых PO и актов."));
        }
        if (metrics.unpaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            findings.add(finding("WARNING", "PAYMENT", "Есть неоплаченные суммы", "Сумма счетов-фактур превышает оплаты на " + metrics.unpaidAmount() + " " + contract.getCurrencyCode() + "."));
        } else if (metrics.unpaidAmount().compareTo(BigDecimal.ZERO) < 0) {
            findings.add(finding("INFO", "PAYMENT", "Есть авансовая оплата", "Оплаты превышают счета-фактуры на " + metrics.unpaidAmount().abs() + " " + contract.getCurrencyCode() + "."));
        }
        if (metrics.overdueInvoices() > 0) {
            findings.add(finding("CRITICAL", "PAYMENT", "Есть просроченные счета-фактуры", "Просрочено счетов-фактур: " + metrics.overdueInvoices() + "."));
        }
        if (metrics.expiredPowersOfAttorney() > 0) {
            findings.add(finding("WARNING", "AUTHORIZATION", "Есть истекшие доверенности", "Истекших доверенностей по договору: " + metrics.expiredPowersOfAttorney() + "."));
        }
        if (metrics.overdueSchedules() > 0) {
            findings.add(finding("WARNING", "DELIVERY", "Есть просроченные графики поставки", "Просроченных или неисполненных строк графика: " + metrics.overdueSchedules() + "."));
        }
        acts.stream()
            .filter(act -> nvl(act.getClosingBalance()).compareTo(BigDecimal.ZERO) != 0)
            .findFirst()
            .ifPresent(act -> findings.add(finding("WARNING", "RECONCILIATION", "Есть расхождение по акту сверки", "Последний найденный ненулевой остаток по акту сверки: " + nvl(act.getClosingBalance()) + ".")));
        if (supplier != null && "BLOCKED".equalsIgnoreCase(supplier.getStatus())) {
            findings.add(finding("CRITICAL", "SUPPLIER", "Поставщик заблокирован", "Договор связан с заблокированным поставщиком; новые операции требуют отдельного решения."));
        } else if (supplier != null && nvl(supplier.getRating()).compareTo(BigDecimal.valueOf(60)) < 0) {
            findings.add(finding("WARNING", "SUPPLIER", "Низкий рейтинг поставщика", "Рейтинг поставщика ниже порога: " + supplier.getRating() + "."));
        }
        if (addendums.isEmpty() && contract.getEndDate() != null && !contract.getEndDate().isAfter(today.plusDays(30))) {
            findings.add(finding("INFO", "DOCUMENTS", "Нет допсоглашения к истекающему договору", "Для продления срока или лимита можно оформить дополнительное соглашение."));
        }
        if (findings.isEmpty()) {
            findings.add(finding("INFO", "CONTROL", "Критичных отклонений не найдено", "По связанным документам договора критичные риски не выявлены."));
        }
        return findings;
    }
    private List<ContractAiRecommendationDto> recommendations(List<ContractAiFindingDto> findings, ContractAiMetrics metrics) {
        List<ContractAiRecommendationDto> result = new ArrayList<>();
        for (ContractAiFindingDto finding : findings) {
            switch (finding.category()) {
                case "VALIDITY" -> result.add(recommendation(priority(finding), "Проверить пролонгацию договора", "Срок действия влияет на возможность создания PO, актов и оплат."));
                case "LIMIT" -> result.add(recommendation(priority(finding), "Согласовать допсоглашение или новый лимит", "Лимит договора должен покрывать планируемые закупки и услуги."));
                case "PAYMENT" -> result.add(recommendation(priority(finding), "Сверить счета-фактуры и оплаты", "Нужно закрыть просроченную задолженность или уточнить авансы."));
                case "AUTHORIZATION" -> result.add(recommendation("MEDIUM", "Переоформить доверенности", "Истекшие доверенности могут блокировать приемку и отгрузку."));
                case "DELIVERY" -> result.add(recommendation("MEDIUM", "Обновить график поставок", "Просроченные строки графика нужно перенести, закрыть или предъявить претензию."));
                case "RECONCILIATION" -> result.add(recommendation("MEDIUM", "Провести сверку с поставщиком", "Ненулевой остаток в акте сверки требует подтверждения финансовым контролем."));
                case "SUPPLIER" -> result.add(recommendation(priority(finding), "Проверить статус поставщика", "Операции с поставщиком должны соответствовать политике блокировок и рейтингов."));
                default -> {
                }
            }
        }
        if (metrics.utilizationPercent().compareTo(BigDecimal.valueOf(80)) >= 0) {
            result.add(recommendation("LOW", "Обновить прогноз потребления договора", "Использование лимита выше 80%; полезно сверить план закупок на оставшийся период."));
        }
        if (result.isEmpty()) {
            result.add(recommendation("LOW", "Продолжить стандартный мониторинг", "Критичных действий не требуется; достаточно регулярного контроля договора."));
        }
        return jsonSupport.distinctRecommendations(result);
    }
    private AiPromptResponse runAiContractAnalysis(String provider, Map<String, Object> context) {
        IntegrationSettingService integrationSettingService = integrationSettingServiceProvider.getIfAvailable();
        if (integrationSettingService == null) {
            return null;
        }
        String prompt = """
            Ты senior ERP MM/FI контролер. Проанализируй договор поставки и связанные документы.
            Верни только JSON вида {"findings":[{"severity":"CRITICAL|WARNING|INFO","category":"...","title":"...","details":"..."}],"recommendations":[{"priority":"HIGH|MEDIUM|LOW","action":"...","reason":"..."}]}.
            Не раскрывай секреты и не придумывай документы, которых нет в context.documents.
            """;
        try {
            return integrationSettingService.testAiProvider(ExternalIntegrationSystem.from(provider), new AiPromptRequest(prompt, context));
        } catch (RuntimeException ex) {
            return new AiPromptResponse(provider, provider, null, "AI_ERROR", ex.getMessage(), null, null, LocalDateTime.now());
        }
    }
    private boolean scheduleOverdue(DeliverySchedule schedule, LocalDate today) {
        if (schedule.getScheduleDate() == null || !schedule.getScheduleDate().isBefore(today) || CLOSED_STATUSES.contains(status(schedule))) {
            return false;
        }
        BigDecimal plannedAmount = nvl(schedule.getPlannedAmount());
        BigDecimal receivedAmount = nvl(schedule.getReceivedAmount());
        BigDecimal plannedQuantity = nvl(schedule.getPlannedQuantity());
        BigDecimal receivedQuantity = nvl(schedule.getReceivedQuantity());
        return (plannedAmount.signum() > 0 && receivedAmount.compareTo(plannedAmount) < 0)
            || (plannedQuantity.signum() > 0 && receivedQuantity.compareTo(plannedQuantity) < 0);
    }
    private <T extends BusinessEntity> List<T> scoped(List<T> rows) {
        return rows.stream().filter(this::belongsToCurrentScope).toList();
    }
    private boolean belongsToCurrentScope(BusinessEntity entity) {
        return entity != null
            && !entity.isDeleted()
            && TenantContext.currentTenant().equals(entity.getTenantId())
            && com.enterprise.mm.security.AccessScope.canAccessCompany(entity.getCompanyId());
    }
    private ContractAiFindingDto finding(String severity, String category, String title, String details) {
        return new ContractAiFindingDto(severity, category, title, details);
    }
    private ContractAiRecommendationDto recommendation(String priority, String action, String reason) {
        return new ContractAiRecommendationDto(priority, action, reason);
    }
    private String priority(ContractAiFindingDto finding) {
        return "CRITICAL".equals(finding.severity()) ? "HIGH" : "MEDIUM";
    }
    private String status(BusinessEntity entity) {
        return firstNonBlank(entity.getStatus(), "").toUpperCase(Locale.ROOT);
    }
    private String aiProvider(String value) {
        String provider = firstNonBlank(value, "CHATGPT").replace('-', '_').toUpperCase(Locale.ROOT);
        if (!AI_PROVIDER_SYSTEMS.contains(provider)) {
            throw new BadRequestException("Для AI-анализа доступны только CHATGPT, CLAUDE или GEMINI");
        }
        return provider;
    }
    private BigDecimal nvl(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
    private String firstNonBlank(String... values) {
        if (values == null) {
            return "";
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}

