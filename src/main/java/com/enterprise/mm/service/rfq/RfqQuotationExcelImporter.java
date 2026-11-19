package com.enterprise.mm.service.rfq;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.RFQ;
import com.enterprise.mm.entity.RFQItem;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.payload.RfqProposalDtos.ImportIssue;
import com.enterprise.mm.payload.RfqProposalDtos.ImportedLine;
import com.enterprise.mm.payload.RfqProposalDtos.RfqQuotationImportPreview;
import com.enterprise.mm.repository.RFQItemRepository;
import com.enterprise.mm.repository.RFQRepository;
import com.enterprise.mm.repository.RfqPublishedVersionRepository;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.CellValue;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class RfqQuotationExcelImporter {
    private static final int FIRST_RESPONSE_ROW = 6;

    private final RFQRepository rfqRepository;
    private final RFQItemRepository itemRepository;
    private final RfqPublishedVersionRepository versionRepository;



    public RfqQuotationImportPreview preview(UUID rfqId, MultipartFile file) {
        RFQ rfq = requireOpenRfq(rfqId);
        requireWorkbook(file);
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            if (!workbook.getExternalLinksTable().isEmpty()) {
                throw new BusinessException("RFQ quotation workbook must not contain external links");
            }
            requireTemplateIdentity(workbook, rfq);
            Sheet sheet = workbook.getSheet("Quotation");
            if (sheet == null) {
                throw new BusinessException("RFQ quotation workbook is invalid");
            }
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            List<ImportIssue> issues = new ArrayList<>();
            List<ImportedLine> lines = readLines(sheet, evaluator, issues);
            validateCoverage(rfq, lines, issues);
            boolean valid = issues.stream().noneMatch(ImportIssue::blocking);
            return new RfqQuotationImportPreview(
                rfq.getId(),
                rfq.getActivePublishedVersionId(),
                List.copyOf(lines),
                List.copyOf(issues),
                valid
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new BusinessException("RFQ quotation workbook is invalid");
        }
    }

    private RFQ requireOpenRfq(UUID rfqId) {
        RFQ rfq = rfqRepository.findByIdAndIsDeletedFalse(rfqId)
            .filter(value -> TenantContext.currentTenant().equals(value.getTenantId()))
            .filter(value -> CompanyContext.currentCompany().equals(value.getCompanyId()))
            .orElseThrow(() -> new BusinessException("RFQ not found"));
        if (rfq.getActivePublishedVersionId() == null
            || isClosedStatus(rfq.getStatus())
            || rfq.getClosedAt() != null) {
            throw new BusinessException("RFQ is not open for quotation import");
        }
        return rfq;
    }

    private void requireWorkbook(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("RFQ quotation workbook is required");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("RFQ quotation workbook must be an .xlsx file");
        }
    }

    private void requireTemplateIdentity(XSSFWorkbook workbook, RFQ rfq) {
        Sheet metadata = workbook.getSheet("_RFQ_META");
        if (metadata == null
            || !"RFQ_ID".equals(metadataValue(metadata, 0, 0))
            || !rfq.getId().toString().equals(metadataValue(metadata, 0, 1))
            || !"TEMPLATE_VERSION".equals(metadataValue(metadata, 2, 0))
            || !"1".equals(metadataValue(metadata, 2, 1))) {
            throw new BusinessException("RFQ quotation workbook belongs to another RFQ or version");
        }
        String publishedVersionId = metadataValue(metadata, 3, 1);
        if (publishedVersionId != null && !publishedVersionId.isBlank()
            && !publishedVersionId.equals(String.valueOf(rfq.getActivePublishedVersionId()))) {
            throw new BusinessException("RFQ quotation workbook belongs to another RFQ or version");
        }
        if (rfq.getActivePublishedVersionId() != null) {
            versionRepository.findByTenantIdAndCompanyIdAndIdAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                rfq.getActivePublishedVersionId()
            ).orElseThrow(() -> new BusinessException(
                "RFQ quotation workbook belongs to another RFQ or version"
            ));
        }
    }

    private List<ImportedLine> readLines(
        Sheet sheet,
        FormulaEvaluator evaluator,
        List<ImportIssue> issues
    ) {
        List<ImportedLine> lines = new ArrayList<>();
        for (int rowIndex = FIRST_RESPONSE_ROW; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
            Row row = sheet.getRow(rowIndex);
            if (row == null || rowIsEmpty(row, evaluator)) {
                continue;
            }
            int rowNumber = rowIndex + 1;
            String identity = text(row.getCell(0), evaluator);
            UUID rfqItemId = uuid(identity, rowNumber, issues);
            BigDecimal unitPrice = decimal(row.getCell(6), evaluator, rowNumber, "unitPrice", issues);
            BigDecimal offeredQuantity = decimal(row.getCell(7), evaluator, rowNumber, "offeredQuantity", issues);
            Integer deliveryDays = integer(row.getCell(8), evaluator, rowNumber, "deliveryDays", issues);
            BigDecimal taxRate = decimal(row.getCell(9), evaluator, rowNumber, "taxRate", issues);
            boolean noBid = bool(row.getCell(10), evaluator, rowNumber, issues);
            String noBidReason = text(row.getCell(11), evaluator);
            String note = text(row.getCell(12), evaluator);
            ImportedLine line = new ImportedLine(
                rowNumber,
                rfqItemId,
                unitPrice,
                offeredQuantity,
                deliveryDays,
                taxRate,
                noBid,
                blankToNull(noBidReason),
                blankToNull(note)
            );
            validateResponse(line, issues);
            lines.add(line);
        }
        return lines;
    }

    private void validateCoverage(RFQ rfq, List<ImportedLine> lines, List<ImportIssue> issues) {
        List<RFQItem> rfqItems = itemRepository.findByTenantIdAndRfq_IdAndIsDeletedFalse(
            TenantContext.currentTenant(), rfq.getId()
        ).stream()
            .filter(item -> CompanyContext.currentCompany().equals(item.getCompanyId()))
            .toList();
        Map<UUID, RFQItem> expected = new HashMap<>();
        rfqItems.forEach(item -> expected.put(item.getId(), item));
        Set<UUID> seen = new HashSet<>();
        for (ImportedLine line : lines) {
            if (line.rfqItemId() == null) {
                continue;
            }
            if (!expected.containsKey(line.rfqItemId())) {
                issues.add(issue("UNKNOWN_RFQ_ITEM", line.rowNumber(), "rfqItemId",
                    "RFQ item does not belong to this RFQ"));
                continue;
            }
            if (!seen.add(line.rfqItemId())) {
                issues.add(issue("DUPLICATE_RFQ_ITEM", line.rowNumber(), "rfqItemId",
                    "RFQ item appears more than once"));
            }
        }
        for (RFQItem item : rfqItems) {
            if (!seen.contains(item.getId())) {
                issues.add(issue("MISSING_RFQ_ITEM", null, "rfqItemId",
                    "RFQ item is missing: " + item.getId()));
            }
        }
    }

    private void validateResponse(ImportedLine line, List<ImportIssue> issues) {
        if (line.noBid()) {
            if (line.noBidReason() == null) {
                issues.add(issue("NO_BID_REASON_REQUIRED", line.rowNumber(), "noBidReason",
                    "No-bid reason is required"));
            }
            return;
        }
        if (line.offeredQuantity() == null || line.offeredQuantity().signum() <= 0) {
            issues.add(issue("OFFERED_QUANTITY_REQUIRED", line.rowNumber(), "offeredQuantity",
                "Offered quantity must be positive"));
        }
        if (line.unitPrice() == null || line.unitPrice().signum() < 0) {
            issues.add(issue("UNIT_PRICE_REQUIRED", line.rowNumber(), "unitPrice",
                "Unit price must be zero or positive"));
        }
        if (line.deliveryDays() != null && line.deliveryDays() < 0) {
            issues.add(issue("DELIVERY_DAYS_INVALID", line.rowNumber(), "deliveryDays",
                "Delivery days must be zero or positive"));
        }
        if (line.taxRate() != null
            && (line.taxRate().signum() < 0 || line.taxRate().compareTo(new BigDecimal("100")) > 0)) {
            issues.add(issue("TAX_RATE_INVALID", line.rowNumber(), "taxRate",
                "Tax rate must be between 0 and 100"));
        }
    }

    private UUID uuid(String value, int rowNumber, List<ImportIssue> issues) {
        try {
            return value == null || value.isBlank() ? null : UUID.fromString(value.trim());
        } catch (IllegalArgumentException exception) {
            issues.add(issue("INVALID_RFQ_ITEM_ID", rowNumber, "rfqItemId", "RFQ item ID is invalid"));
            return null;
        }
    }

    private BigDecimal decimal(
        Cell cell,
        FormulaEvaluator evaluator,
        int rowNumber,
        String field,
        List<ImportIssue> issues
    ) {
        String value = text(cell, evaluator);
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value.trim());
        } catch (NumberFormatException exception) {
            issues.add(issue("INVALID_NUMBER", rowNumber, field, field + " must be numeric"));
            return null;
        }
    }

    private Integer integer(
        Cell cell,
        FormulaEvaluator evaluator,
        int rowNumber,
        String field,
        List<ImportIssue> issues
    ) {
        BigDecimal value = decimal(cell, evaluator, rowNumber, field, issues);
        if (value == null) {
            return null;
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException exception) {
            issues.add(issue("INVALID_INTEGER", rowNumber, field, field + " must be a whole number"));
            return null;
        }
    }

    private boolean bool(Cell cell, FormulaEvaluator evaluator, int rowNumber, List<ImportIssue> issues) {
        String value = text(cell, evaluator);
        if (value == null || value.isBlank()) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        if (Set.of("true", "1", "yes", "y", "да").contains(normalized)) {
            return true;
        }
        if (Set.of("false", "0", "no", "n", "нет").contains(normalized)) {
            return false;
        }
        issues.add(issue("INVALID_BOOLEAN", rowNumber, "noBid", "No bid must be yes or no"));
        return false;
    }

    private String text(Cell cell, FormulaEvaluator evaluator) {
        if (cell == null || cell.getCellType() == CellType.BLANK) {
            return null;
        }
        CellValue value = cell.getCellType() == CellType.FORMULA ? evaluator.evaluate(cell) : null;
        CellType type = value == null ? cell.getCellType() : value.getCellType();
        return switch (type) {
            case STRING -> value == null ? cell.getStringCellValue() : value.getStringValue();
            case NUMERIC -> numericText(value == null ? cell.getNumericCellValue() : value.getNumberValue());
            case BOOLEAN -> String.valueOf(value == null ? cell.getBooleanCellValue() : value.getBooleanValue());
            case BLANK -> null;
            default -> null;
        };
    }

    private String metadataValue(Sheet metadata, int rowIndex, int columnIndex) {
        Row row = metadata.getRow(rowIndex);
        return row == null ? null : text(row.getCell(columnIndex), metadata.getWorkbook()
            .getCreationHelper().createFormulaEvaluator());
    }

    private boolean rowIsEmpty(Row row, FormulaEvaluator evaluator) {
        for (int column = 0; column <= 12; column++) {
            String value = text(row.getCell(column), evaluator);
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String numericText(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private ImportIssue issue(String code, Integer row, String field, String message) {
        return new ImportIssue(code, row, field, message, true);
    }

    private boolean isClosedStatus(String status) {
        String normalized = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return Set.of("DRAFT", "CANCELLED", "CLOSED", "ARCHIVED", "BIDS_OPENED", "EVALUATED", "AWARDED")
            .contains(normalized);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
