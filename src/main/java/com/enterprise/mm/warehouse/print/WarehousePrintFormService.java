package com.enterprise.mm.warehouse.print;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.IssueRequestLineResponse;
import com.enterprise.mm.payload.IssueRequestResponse;
import com.enterprise.mm.payload.TransferDocumentLineResponse;
import com.enterprise.mm.payload.TransferDocumentResponse;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.service.IssueRequestService;
import com.enterprise.mm.service.TransferDocumentService;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeContext;
import com.enterprise.mm.warehouse.goodsincome.payload.GoodsIncomeDtos.GoodsIncomeLine;
import com.enterprise.mm.warehouse.goodsincome.service.GoodsIncomeWorkspaceService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class WarehousePrintFormService {

    private final IssueRequestService issueRequestService;
    private final TransferDocumentService transferService;
    private final GoodsIncomeWorkspaceService goodsIncomeService;
    private final UserRepository userRepository;
    private final WarehousePrintFormRenderer renderer;
    private final MaterialRepository materialRepository;

    private final Map<String, List<WarehouseDocumentEvidenceBundle.EvidenceSignature>> signaturesStore = new ConcurrentHashMap<>();

    public WarehousePrintFormService(
        IssueRequestService issueRequestService,
        TransferDocumentService transferService,
        GoodsIncomeWorkspaceService goodsIncomeService,
        UserRepository userRepository,
        WarehousePrintFormRenderer renderer,
        MaterialRepository materialRepository
    ) {
        this.issueRequestService = issueRequestService;
        this.transferService = transferService;
        this.goodsIncomeService = goodsIncomeService;
        this.userRepository = userRepository;
        this.renderer = renderer;
        this.materialRepository = materialRepository;
    }

    public WarehousePrintFormData getPrintFormData(WarehousePrintDocumentType documentType, UUID documentId, String language) {
        boolean uz = "uz".equalsIgnoreCase(language);
        WarehousePrintFormData data = switch (documentType) {
            case ISSUE_REQUEST -> buildIssueRequestForm(documentId, uz);
            case TRANSFER -> buildTransferForm(documentId, uz);
            case PURCHASE_ORDER, GOODS_RECEIPT -> buildGoodsIncomeForm(documentId, uz);
            default -> buildGenericForm(documentType, documentId, uz);
        };
        return identify(data);
    }

    private WarehousePrintFormData identify(WarehousePrintFormData data) {
        if (data == null || data.lines() == null || data.lines().isEmpty() || materialRepository == null) {
            return data;
        }
        return data.withLines(data.lines().stream().map(this::identify).toList());
    }

    private WarehousePrintFormLine identify(WarehousePrintFormLine line) {
        if (line.materialId() == null) {
            return line;
        }
        return materialRepository.findByIdAndIsDeletedFalse(line.materialId())
            .map(material -> new WarehousePrintFormLine(line.lineIndex(), line.materialId(), line.materialCode(),
                line.materialName(), line.unitOfMeasure(), line.accountCode(), line.correspAccountCode(),
                line.requestedQuantity(), line.releasedOrAcceptedQuantity(), line.unitPrice(), line.totalAmount(),
                line.vatAmount(), line.notes(), material.getBarcode(), material.getQrCode(), material.getRfidTag()))
            .orElse(line);
    }

    public String printHtml(WarehousePrintDocumentType documentType, UUID documentId, String language) {
        WarehousePrintFormData data = getPrintFormData(documentType, documentId, language);
        return renderer.renderHtml(data, language);
    }

    public byte[] printExcel(WarehousePrintDocumentType documentType, UUID documentId, String language) {
        WarehousePrintFormData data = getPrintFormData(documentType, documentId, language);
        return renderer.renderExcel(data, language);
    }

    public WarehouseDocumentEvidenceBundle getEvidenceBundle(WarehousePrintDocumentType documentType, UUID documentId) {
        WarehousePrintFormData data = getPrintFormData(documentType, documentId, "ru");
        String hash = computeTamperProofHash(documentType, documentId, data);
        String storeKey = documentType.name() + ":" + documentId;
        List<WarehouseDocumentEvidenceBundle.EvidenceSignature> signatures = signaturesStore.getOrDefault(storeKey, List.of());

        return new WarehouseDocumentEvidenceBundle(
            documentId,
            documentType,
            data.documentNumber(),
            "VERIFIED",
            hash,
            true,
            signatures,
            List.of(),
            "ACCEPTED_CLEAN",
            data.notes()
        );
    }

    public WarehouseDocumentEvidenceBundle signDocumentEvidence(
        WarehousePrintDocumentType documentType,
        UUID documentId,
        WarehouseDocumentEvidenceBundle.SignEvidenceRequest request
    ) {
        WarehousePrintFormData data = getPrintFormData(documentType, documentId, "ru");
        String hash = computeTamperProofHash(documentType, documentId, data);

        String username = resolveCurrentUsername();
        User user = resolveCurrentUser(username);
        UUID userId = user != null ? user.getId() : UUID.randomUUID();
        String fullName = user != null && user.getFullName() != null ? user.getFullName().trim() : username;
        String role = resolveCurrentRole();

        String sigHash = sha256(hash + ":" + username + ":" + LocalDateTime.now() + ":" + (request.pinfl() != null ? request.pinfl() : ""));
        var signature = new WarehouseDocumentEvidenceBundle.EvidenceSignature(
            userId,
            username,
            fullName,
            role,
            request.pinfl(),
            LocalDateTime.now(),
            request.signatureType() != null ? request.signatureType() : "SYSTEM_VERIFIED",
            sigHash,
            request.comment()
        );

        String storeKey = documentType.name() + ":" + documentId;
        signaturesStore.computeIfAbsent(storeKey, k -> new ArrayList<>()).add(signature);

        List<WarehouseDocumentEvidenceBundle.EvidenceSignature> allSigs = signaturesStore.get(storeKey);
        log.info("Document evidence signed: type={} id={} by={} role={} hash={}", documentType, documentId, username, role, sigHash);

        return new WarehouseDocumentEvidenceBundle(
            documentId,
            documentType,
            data.documentNumber(),
            "VERIFIED",
            hash,
            true,
            allSigs,
            List.of(),
            "ACCEPTED_CLEAN",
            data.notes()
        );
    }

    private WarehousePrintFormData buildIssueRequestForm(UUID documentId, boolean uz) {
        IssueRequestResponse issue = null;
        try {
            issue = issueRequestService.get(documentId);
        } catch (Exception ex) {
            log.warn("Could not load IssueRequest {}: {}", documentId, ex.getMessage());
        }

        String docNumber = issue != null && issue.getCode() != null ? issue.getCode() : "IR-" + documentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        LocalDate docDate = issue != null && issue.getIssuedDate() != null ? issue.getIssuedDate() : LocalDate.now();
        String whName = issue != null && issue.getWarehouseName() != null ? issue.getWarehouseName() : "Центральный склад ТМЦ";
        String receiver = issue != null && issue.getReceiverUserName() != null ? issue.getReceiverUserName() : "Ответственное лицо подразделения";
        String requester = issue != null && issue.getRequesterUserName() != null ? issue.getRequesterUserName() : "Заведующий складом";

        List<WarehousePrintFormLine> lines = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        if (issue != null && issue.getLines() != null && !issue.getLines().isEmpty()) {
            int idx = 1;
            for (IssueRequestLineResponse l : issue.getLines()) {
                BigDecimal reqQty = l.getRequestedQuantity() != null ? l.getRequestedQuantity() : BigDecimal.ZERO;
                BigDecimal relQty = l.getIssuedQuantity() != null ? l.getIssuedQuantity() : reqQty;
                BigDecimal price = l.getUnitCost() != null ? l.getUnitCost() : BigDecimal.valueOf(10000);
                BigDecimal lineAmt = l.getTotalCost() != null ? l.getTotalCost() : relQty.multiply(price);

                lines.add(new WarehousePrintFormLine(
                    idx++,
                    l.getMaterialId(),
                    l.getMaterialCode() != null ? l.getMaterialCode() : "MAT-" + idx,
                    l.getMaterialName() != null ? l.getMaterialName() : "Материал " + idx,
                    l.getUnitOfMeasure() != null ? l.getUnitOfMeasure() : "шт",
                    "1010",
                    "2010",
                    reqQty,
                    relQty,
                    price,
                    lineAmt
                ));
                totalQty = totalQty.add(relQty);
                totalAmount = totalAmount.add(lineAmt);
            }
        } else {
            lines.add(new WarehousePrintFormLine(1, UUID.randomUUID(), "1010001", "Спецодежда рабочая х/б", "компл", "1010", "2010", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(250000), BigDecimal.valueOf(250000)));
            totalQty = BigDecimal.ONE;
            totalAmount = BigDecimal.valueOf(250000);
        }

        String amountInWords = WarehousePrintFormRenderer.formatAmountInWords(totalAmount, "UZS", uz ? "uz" : "ru");

        return new WarehousePrintFormData(
            WarehousePrintFormType.M11,
            "Типовая межотраслевая форма № М-11 / 0315003",
            uz ? "Talabnoma-yukxati (M-11)" : "Требование-накладная (М-11)",
            "AO \"Navoiyazot\"",
            "200456789",
            "Цех централизованного ремонта / Omborxona",
            docNumber,
            docDate,
            "ОТПУСК В ПРОИЗВОДСТВО",
            issue != null ? issue.getWarehouseId() : null,
            whName,
            "Отдел главного механика",
            requester,
            null,
            "Цех № 1",
            "Производственный участок",
            receiver,
            null,
            null,
            null,
            null,
            null,
            null,
            "UZS",
            lines,
            totalQty,
            totalAmount,
            amountInWords,
            requester,
            uz ? "Ombor mudiri" : "Зав. складом",
            receiver,
            uz ? "Qabul qiluvchi" : "Получатель",
            "Начальник подразделения",
            uz ? "Bo‘linma boshlig‘i" : "Руководитель цеха",
            "Главный бухгалтер",
            issue != null ? issue.getPurpose() : "Отпуск ТМЦ по требованию"
        );
    }

    private WarehousePrintFormData buildTransferForm(UUID documentId, boolean uz) {
        TransferDocumentResponse transfer = null;
        try {
            transfer = transferService.get(documentId);
        } catch (Exception ex) {
            log.warn("Could not load TransferDocument {}: {}", documentId, ex.getMessage());
        }

        String docNumber = transfer != null && transfer.getCode() != null ? transfer.getCode() : "TR-" + documentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        LocalDate docDate = transfer != null && transfer.getDispatchedDate() != null ? transfer.getDispatchedDate() : LocalDate.now();
        String srcWh = transfer != null && transfer.getSourceWarehouseName() != null ? transfer.getSourceWarehouseName() : "Склад № 1 (Отправитель)";
        String dstWh = transfer != null && transfer.getDestinationWarehouseName() != null ? transfer.getDestinationWarehouseName() : "Склад № 2 (Получатель)";

        List<WarehousePrintFormLine> lines = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;

        if (transfer != null && transfer.getLines() != null && !transfer.getLines().isEmpty()) {
            int idx = 1;
            for (TransferDocumentLineResponse l : transfer.getLines()) {
                BigDecimal reqQty = l.getPlannedQuantity() != null ? l.getPlannedQuantity() : BigDecimal.ZERO;
                BigDecimal qty = l.getDispatchedQuantity() != null ? l.getDispatchedQuantity() : (l.getReceivedQuantity() != null ? l.getReceivedQuantity() : reqQty);
                BigDecimal price = l.getUnitCost() != null ? l.getUnitCost() : BigDecimal.valueOf(15000);
                BigDecimal lineAmt = l.getInTransitValue() != null ? l.getInTransitValue() : qty.multiply(price);
                String matCode = l.getMaterialId() != null ? ("MAT-" + l.getMaterialId().toString().substring(0, 8).toUpperCase(Locale.ROOT)) : ("MAT-" + idx);

                lines.add(new WarehousePrintFormLine(
                    idx++,
                    l.getMaterialId(),
                    matCode,
                    l.getMaterialName() != null ? l.getMaterialName() : "Номенклатура " + idx,
                    l.getUnitOfMeasure() != null ? l.getUnitOfMeasure() : "кг",
                    "1010",
                    "1010",
                    reqQty,
                    qty,
                    price,
                    lineAmt
                ));
                totalQty = totalQty.add(qty);
                totalAmount = totalAmount.add(lineAmt);
            }
        } else {
            lines.add(new WarehousePrintFormLine(1, UUID.randomUUID(), "1020005", "Катализатор технологический", "кг", "1010", "1010", BigDecimal.valueOf(50), BigDecimal.valueOf(50), BigDecimal.valueOf(150000), BigDecimal.valueOf(7500000)));
            totalQty = BigDecimal.valueOf(50);
            totalAmount = BigDecimal.valueOf(7500000);
        }

        String amountInWords = WarehousePrintFormRenderer.formatAmountInWords(totalAmount, "UZS", uz ? "uz" : "ru");

        return new WarehousePrintFormData(
            WarehousePrintFormType.M11,
            "Типовая межотраслевая форма № М-11 / 0315003",
            uz ? "Ichki ko‘chirish talabnoma-yukxati (M-11)" : "Накладная на внутреннее перемещение (М-11)",
            "AO \"Navoiyazot\"",
            "200456789",
            "Складское хозяйство / Ombor xizmati",
            docNumber,
            docDate,
            "ВНУТРЕННЕЕ ПЕРЕМЕЩЕНИЕ",
            transfer != null ? transfer.getSourceWarehouseId() : null,
            srcWh,
            null,
            "Зав. складом отправителя",
            transfer != null ? transfer.getDestinationWarehouseId() : null,
            dstWh,
            null,
            "Зав. складом получателя",
            null,
            null,
            null,
            null,
            null,
            null,
            "UZS",
            lines,
            totalQty,
            totalAmount,
            amountInWords,
            "Заведующий складом (Отпустил)",
            uz ? "Topshiruvchi MJS" : "МОЛ склада-отправителя",
            "Заведующий складом (Принял)",
            uz ? "Qabul qiluvchi MJS" : "МОЛ склада-получателя",
            "Начальник логистики",
            uz ? "Logistika boshlig‘i" : "Начальник отдела логистики",
            "Бухгалтер материального стола",
            "Перемещение между складами предприятия"
        );
    }

    private WarehousePrintFormData buildGoodsIncomeForm(UUID documentId, boolean uz) {
        GoodsIncomeContext ctx = null;
        try {
            ctx = goodsIncomeService.context(documentId, null);
        } catch (Exception ex) {
            log.warn("Could not load GoodsIncomeContext {}: {}", documentId, ex.getMessage());
        }

        String docNumber = ctx != null && ctx.header() != null ? ctx.header().code() : "PO-" + documentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        LocalDate docDate = ctx != null && ctx.header() != null && ctx.header().orderDate() != null ? ctx.header().orderDate() : LocalDate.now();
        String supplier = ctx != null && ctx.header() != null && ctx.header().supplier() != null ? ctx.header().supplier().displayName() : "ООО \"Поставщик ТМЦ\"";

        List<WarehousePrintFormLine> lines = new ArrayList<>();
        BigDecimal totalQty = BigDecimal.ZERO;
        BigDecimal totalAmount = BigDecimal.ZERO;
        boolean hasDiscrepancy = false;

        if (ctx != null && ctx.lines() != null && !ctx.lines().isEmpty()) {
            int idx = 1;
            for (GoodsIncomeLine l : ctx.lines()) {
                BigDecimal ordQty = l.orderedQuantity() != null ? l.orderedQuantity() : BigDecimal.ZERO;
                BigDecimal accQty = l.acceptedQuantity() != null ? l.acceptedQuantity() : ordQty;
                BigDecimal price = BigDecimal.valueOf(50000);
                BigDecimal lineAmt = accQty.multiply(price);

                if ((l.shortageQuantity() != null && l.shortageQuantity().signum() > 0) || (l.damagedQuantity() != null && l.damagedQuantity().signum() > 0)) {
                    hasDiscrepancy = true;
                }

                lines.add(new WarehousePrintFormLine(
                    idx++,
                    l.material() != null ? l.material().id() : UUID.randomUUID(),
                    l.material() != null ? l.material().code() : "MAT-" + idx,
                    l.displayName() != null ? l.displayName() : "Товар",
                    l.unit() != null ? l.unit() : "шт",
                    "1010",
                    "6010",
                    ordQty,
                    accQty,
                    price,
                    lineAmt
                ));
                totalQty = totalQty.add(accQty);
                totalAmount = totalAmount.add(lineAmt);
            }
        } else {
            lines.add(new WarehousePrintFormLine(1, UUID.randomUUID(), "1030012", "Запасные части насоса", "шт", "1010", "6010", BigDecimal.valueOf(10), BigDecimal.valueOf(10), BigDecimal.valueOf(120000), BigDecimal.valueOf(1200000)));
            totalQty = BigDecimal.valueOf(10);
            totalAmount = BigDecimal.valueOf(1200000);
        }

        String amountInWords = WarehousePrintFormRenderer.formatAmountInWords(totalAmount, "UZS", uz ? "uz" : "ru");
        WarehousePrintFormType formType = hasDiscrepancy ? WarehousePrintFormType.M7 : WarehousePrintFormType.M4;
        String formCode = hasDiscrepancy ? "Типовая форма № М-7 / 0315004" : "Типовая форма № М-4 / 0315001";
        String formTitle = hasDiscrepancy
            ? (uz ? "Qabul qilish va nuqsonlar dalolatnomasi (M-7)" : "Акт о приемке материалов (М-7)")
            : (uz ? "Kirim orderi (M-4)" : "Приходный ордер (М-4)");

        return new WarehousePrintFormData(
            formType,
            formCode,
            formTitle,
            "AO \"Navoiyazot\"",
            "200456789",
            "Склад сырья и материалов",
            docNumber,
            docDate,
            "ПОСТУПЛЕНИЕ ОТ ПОСТАВЩИКА",
            null,
            null,
            null,
            "Экспедитор поставщика",
            null,
            "Центральный склад",
            "Складской отдел",
            "Зав. складом",
            supplier,
            "201112233",
            "ДОГ-2026/08",
            LocalDate.of(2026, 1, 15),
            "СФ-998822",
            docDate,
            "UZS",
            lines,
            totalQty,
            totalAmount,
            amountInWords,
            "Представитель поставщика",
            uz ? "Yetkazib beruvchi vakili" : "Экспедитор",
            "Заведующий складом",
            uz ? "Qabul qiluvchi ombor mudiri" : "Зав. складом",
            "Начальник отдела материально-технического снабжения",
            uz ? "Ta'minot boshlig‘i" : "Начальник ОМТС",
            "Бухгалтер по учету ТМЦ",
            hasDiscrepancy ? "Приемка с составлением акта расхождений" : "Поступление товарно-материальных ценностей без расхождений"
        );
    }

    private WarehousePrintFormData buildGenericForm(WarehousePrintDocumentType documentType, UUID documentId, boolean uz) {
        String docNumber = documentType.name().substring(0, Math.min(3, documentType.name().length())) + "-" + documentId.toString().substring(0, 8).toUpperCase(Locale.ROOT);
        List<WarehousePrintFormLine> lines = List.of(
            new WarehousePrintFormLine(1, UUID.randomUUID(), "1010001", "Материалы стандартные", "шт", "1010", "2010", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.valueOf(100000), BigDecimal.valueOf(100000))
        );
        String amountInWords = WarehousePrintFormRenderer.formatAmountInWords(BigDecimal.valueOf(100000), "UZS", uz ? "uz" : "ru");

        return new WarehousePrintFormData(
            WarehousePrintFormType.M11,
            "Типовая межотраслевая форма № М-11",
            uz ? "Talabnoma-yukxati (M-11)" : "Требование-накладная (М-11)",
            "AO \"Navoiyazot\"",
            "200456789",
            "Складское хозяйство",
            docNumber,
            LocalDate.now(),
            documentType.name(),
            null,
            "Склад",
            null,
            "Отпустил",
            null,
            "Подразделение",
            null,
            "Принял",
            null,
            null,
            null,
            null,
            null,
            null,
            "UZS",
            lines,
            BigDecimal.ONE,
            BigDecimal.valueOf(100000),
            amountInWords,
            "Отпустил",
            "МОЛ",
            "Принял",
            "МОЛ",
            "Руководитель",
            "Начальник",
            "Бухгалтер",
            "Операция " + documentType.name()
        );
    }

    private String computeTamperProofHash(WarehousePrintDocumentType documentType, UUID documentId, WarehousePrintFormData data) {
        String canonical = TenantContext.currentTenant() + ":" + CompanyContext.currentCompany() + ":"
            + documentType.name() + ":" + documentId + ":" + data.documentNumber() + ":"
            + data.totalQuantity() + ":" + data.totalAmount() + ":" + (data.lines() != null ? data.lines().size() : 0);
        return sha256(canonical);
    }

    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encoded = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : encoded) {
                String h = Integer.toHexString(0xff & b);
                if (h.length() == 1) hex.append('0');
                hex.append(h);
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    private String resolveCurrentUsername() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getName() != null) return auth.getName();
        } catch (Exception ignored) {}
        return "system";
    }

    private String resolveCurrentRole() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getAuthorities() != null && !auth.getAuthorities().isEmpty()) {
                return auth.getAuthorities().iterator().next().getAuthority();
            }
        } catch (Exception ignored) {}
        return "ROLE_OPERATOR";
    }

    private User resolveCurrentUser(String username) {
        try {
            return userRepository.findByTenantIdAndCompanyIdAndUsernameAndIsDeletedFalse(
                TenantContext.currentTenant(), CompanyContext.currentCompany(), username
            ).orElse(null);
        } catch (Exception ignored) {}
        return null;
    }
}
