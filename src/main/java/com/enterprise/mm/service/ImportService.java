package com.enterprise.mm.service;
import lombok.RequiredArgsConstructor;

import com.enterprise.mm.payload.ReferenceMappingUpdateRequest;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.enums.ExternalIntegrationSystem;
import com.enterprise.mm.enums.ValuationMethod;
import com.enterprise.mm.enums.MaterialStatus;
import com.enterprise.mm.enums.MaterialType;
import com.enterprise.mm.entity.IkpuCode;
import com.enterprise.mm.entity.Material;
import com.enterprise.mm.entity.MXIK;
import com.enterprise.mm.entity.StockBalance;
import com.enterprise.mm.entity.TnvedCode;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.IkpuCodeRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.MXIKRepository;
import com.enterprise.mm.repository.ReferenceMappingRepository;
import com.enterprise.mm.repository.StockBalanceRepository;
import com.enterprise.mm.repository.TnvedCodeRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class ImportService {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ExactOpeningImportGuard openingImportGuard;

    private void guardOpeningImport(UUID companyId) {
        if (openingImportGuard != null) openingImportGuard.assertOperationsAllowed(companyId);
    }

    private final InventoryScopeLockService inventoryScopeLocks;
    private final InventoryFreezeGuardService inventoryFreezeGuard;
    private final MaterialRepository materialRepository;
    private final StockBalanceRepository stockBalanceRepository;
    private final WarehouseRepository warehouseRepository;
    private final IkpuCodeRepository ikpuCodeRepository;
    private final MXIKRepository mxikRepository;
    private final TnvedCodeRepository tnvedCodeRepository;
    private final ReferenceMappingRepository referenceMappingRepository;
    private final ReferenceMappingService referenceMappingService;
    private final AuditService auditService;

    @Transactional
    public int importMaterials(MultipartFile file) {
        validateXlsx(file);
        guardOpeningImport(CompanyContext.currentCompany());
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = firstSheet(workbook, "materials");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headers = headerColumns(sheet.getRow(sheet.getFirstRowNum()), formatter, evaluator);
            int nameRuColumn = headerColumn(headers, 1, "nameru", "runame", "name", "materialname");
            int nameEnColumn = headerColumn(headers, -1, "nameen", "enname");
            int nameUzColumn = headerColumn(headers, -1, "nameuz", "uzname");
            int valuationColumn = headerColumn(
                headers,
                nameEnColumn >= 0 || nameUzColumn >= 0 ? 4 : 2,
                "valuationmethod",
                "valuation"
            );
            Set<String> importedCodes = new HashSet<>();
            int count = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0 || emptyRow(row, formatter, evaluator, 0, 1, 2, 3)) {
                    continue;
                }
                String code = requiredString(row, 0, "material code", formatter, evaluator).trim().toUpperCase(Locale.ROOT);
                String nameRu = requiredString(row, nameRuColumn, "material name (RU)", formatter, evaluator);
                String nameEn = nameEnColumn < 0 ? nameRu : readString(row, nameEnColumn, formatter, evaluator);
                String nameUz = nameUzColumn < 0 ? nameRu : readString(row, nameUzColumn, formatter, evaluator);
                String name = com.enterprise.mm.common.LocalizedText.ensureComplete(
                    com.enterprise.mm.common.LocalizedText.format(nameRu, nameEn, nameUz)
                );
                checkLength(code, 80, "material code", row);
                checkLength(name, 240, "material name", row);
                if (!importedCodes.add(code)) {
                    throw rowError(row, "Duplicate material code in import file: " + code);
                }
                String valuationMethod = readString(row, valuationColumn, formatter, evaluator);
                com.enterprise.mm.enums.ValuationMethod vMethod = com.enterprise.mm.enums.ValuationMethod.ACTUAL_COST;
                if(valuationMethod != null && !valuationMethod.trim().isEmpty()) { try { vMethod = com.enterprise.mm.enums.ValuationMethod.valueOf(valuationMethod.trim().toUpperCase()); } catch(Exception e) {} }
                if (valuationMethod.isBlank()) {
                    valuationMethod = ValuationMethod.ACTUAL_COST.name();
                }
                validateValuationMethod(valuationMethod, row);
                Material material = materialRepository.findByTenantIdAndCompanyIdAndCodeAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    code
                ).orElseGet(Material::new);
                if (material.getCompanyId() != null) guardOpeningImport(material.getCompanyId());
                material.setCode(code);
                material.setName(name);
                material.setStatus(MaterialStatus.ACTIVE.name());
                material.setValuationMethod(vMethod);
                materialRepository.save(material);
                count++;
            }
            auditService.log("Material", null, "IMPORT_EXCEL", count + " rows", null, null);
            return count;
        } catch (IOException ex) {
            throw new BusinessException("Cannot read materials import file");
        } catch (POIXMLException ex) {
            throw new BusinessException("Invalid XLSX file for materials import");
        }
    }
    @Transactional
    public int importStockBalances(MultipartFile file) {
        throw new BusinessException("Direct balance overwrite is disabled. Use the signed opening-stock posting API /api/opening-stock with stock UOM, location, value and import identity.");
    }
    @Transactional
    public int importReferenceMappings(MultipartFile file) {
        validateXlsx(file);
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = firstSheet(workbook, "reference mappings");
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Set<String> importedKeys = new HashSet<>();
            int count = 0;
            for (Row row : sheet) {
                if (row.getRowNum() == 0 || emptyRow(row, formatter, evaluator, 0, 4, 5, 7)) {
                    continue;
                }
                ReferenceMappingUpdateRequest dto = new ReferenceMappingUpdateRequest();
                dto.setReferenceEntityType(normalizeCode(requiredString(row, 0, "reference entity type", formatter, evaluator)));
                dto.setReferenceId(readOptionalUuid(row, 1, "reference id", formatter, evaluator));
                dto.setReferenceCode(blankToNull(readString(row, 2, formatter, evaluator)));
                dto.setReferenceName(blankToNull(readString(row, 3, formatter, evaluator)));
                dto.setExternalSystem(normalizeCode(requiredString(row, 4, "external system", formatter, evaluator)));
                dto.setExternalCode(requiredString(row, 5, "external code", formatter, evaluator));
                dto.setExternalName(blankToNull(readString(row, 6, formatter, evaluator)));
                dto.setAccountingArea(normalizeCode(requiredString(row, 7, "accounting area", formatter, evaluator)));
                dto.setChartOfAccountsCode(blankToNull(readString(row, 8, formatter, evaluator)));
                dto.setAccountCode(blankToNull(readString(row, 9, formatter, evaluator)));
                dto.setSubAccountCode(blankToNull(readString(row, 10, formatter, evaluator)));
                dto.setCostCenter(blankToNull(readString(row, 11, formatter, evaluator)));
                dto.setProfitCenter(blankToNull(readString(row, 12, formatter, evaluator)));
                dto.setWarehouseAccountingCode(blankToNull(readString(row, 13, formatter, evaluator)));
                dto.setTaxCode(blankToNull(readString(row, 14, formatter, evaluator)));
                dto.setCurrencyCode(blankToNull(readString(row, 15, formatter, evaluator)));
                dto.setPriority(readInteger(row, 16, 100, "priority", formatter, evaluator));
                dto.setDefaultMapping(readBoolean(row, 17, false, formatter, evaluator));
                dto.setValidFrom(readDate(row, 18, formatter, evaluator));
                dto.setValidTo(readDate(row, 19, formatter, evaluator));
                dto.setStatus("ACTIVE");
                String key = dto.getReferenceEntityType() + ":" + dto.getExternalSystem() + ":" + dto.getExternalCode() + ":" + dto.getAccountingArea();
                if (!importedKeys.add(key)) {
                    throw rowError(row, "Duplicate reference mapping key in import file: " + key);
                }
                var existing = referenceMappingRepository.findByTenantIdAndCompanyIdAndReferenceEntityTypeAndExternalSystemAndExternalCodeAndAccountingAreaAndIsDeletedFalse(
                    TenantContext.currentTenant(),
                    CompanyContext.currentCompany(),
                    dto.getReferenceEntityType(),
                    dto.getExternalSystem(),
                    dto.getExternalCode(),
                    dto.getAccountingArea()
                );
                if (existing.isPresent()) {
                    referenceMappingService.update(existing.get().getId(), dto);
                } else {
                    referenceMappingService.create(dto);
                }
                count++;
            }
            auditService.log("ReferenceMapping", null, "IMPORT_EXCEL", count + " rows", null, null);
            return count;
        } catch (IOException ex) {
            throw new BusinessException("Cannot read reference mappings import file");
        } catch (POIXMLException ex) {
            throw new BusinessException("Invalid XLSX file for reference mappings import");
        }
    }
    @Transactional
    public int importIkpuCodes(MultipartFile file) {
        List<ProductCodeImportRow> rows = readProductCodeRows(file, "IKPU codes");
        Set<String> importedCodes = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (ProductCodeImportRow row : rows) {
            String code = normalizeProductCode(firstValue(row, "ikpucode", "ikpu", "mxikcode", "mxik", "classcode", "code", "мхик", "икпу", "кодмхик", "кодикпу", "код"));
            if (code == null) {
                if (row.values().isEmpty()) {
                    continue;
                }
                throw importRowError(row, "Required IKPU/MXIK code column is empty");
            }
            if (!importedCodes.add(code)) {
                throw importRowError(row, "Duplicate IKPU/MXIK code in import file: " + code);
            }
            checkLength(code, 40, "IKPU/MXIK code", row.rowNumber());
            IkpuCode item = ikpuCodeRepository.findByTenantIdAndCompanyIdAndIkpuCodeAndIsDeletedFalse(
                TenantContext.currentTenant(),
                CompanyContext.currentCompany(),
                code
            ).orElseGet(IkpuCode::new);
            item.setCode(code);
            item.setIkpuCode(code);
            String importedDescription = productCodeDescription(row);
            item.setName(firstNonBlank(importedDescription, entityName(firstNonBlank(
                firstValue(row, "name", "nameru", "title", "productname", "fullname", "label", "mxikname", "subpositionnameru", "positionnameru", "classnameru", "groupnameru", "наименование", "название", "наименованиеру"),
                "IKPU Item"
            ), "IKPU Item")));
            item.setDescription(importedDescription);
            item.setStatus(productCodeStatus(row));
            item.setPackageCode(normalizeProductCode(firstValue(row, "packagecode", "package", "кодупаковки", "упаковка")));
            item.setUnitCode(normalizeProductCode(firstValue(row, "unitcode", "unit", "uom", "categoryunitname", "unitsname", "recommendedunitsname", "единицаизмерения", "едизм", "единица")));
            item.setGroupCode(normalizeProductCode(firstValue(row, "groupcode", "group", "кодгруппы", "группа")));
            item.setVatRate(decimalOrNull(firstValue(row, "vatrate", "ndsrate", "ratevat", "ставкандс", "ндс")));
            item.setService(booleanValue(firstValue(row, "service", "isservice", "услуга", "этоуслуга")));
            item.setSourceSystem(ExternalIntegrationSystem.SOLIQ.name());
            item.setExternalId(firstValue(row, "externalid", "id", "guid", "pkey", "внешнийкод"));
            item.setLastSyncedAt(now);
            ikpuCodeRepository.save(item);
            upsertMxik(row, code);
            upsertLinkedTnvedFromIkpuImport(row, now);
            count++;
        }
        auditService.log("IkpuCode", null, "IMPORT_PRODUCT_CODES", count + " rows", null, null);
        return count;
    }
    @Transactional
    public int importTnvedCodes(MultipartFile file) {
        List<ProductCodeImportRow> rows = readProductCodeRows(file, "TN VED codes");
        Set<String> importedCodes = new HashSet<>();
        LocalDateTime now = LocalDateTime.now();
        int count = 0;
        for (ProductCodeImportRow row : rows) {
            String code = tnvedCode(row);
            if (code == null) {
                if (row.values().isEmpty()) {
                    continue;
                }
                throw importRowError(row, "Required TN VED code column is empty");
            }
            if (!importedCodes.add(code)) {
                throw importRowError(row, "Duplicate TN VED code in import file: " + code);
            }
            upsertTnvedImportRow(row, code, now);
            count++;
        }
        auditService.log("TnvedCode", null, "IMPORT_PRODUCT_CODES", count + " rows", null, null);
        return count;
    }
    private List<ProductCodeImportRow> readProductCodeRows(MultipartFile file, String importName) {
        validateProductCodeFile(file);
        String fileName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        try {
            if (fileName.endsWith(".csv")) {
                return readCsvProductCodeRows(file);
            }
            try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
                return readXlsxProductCodeRows(workbook, importName);
            }
        } catch (IOException ex) {
            throw new BusinessException("Cannot read " + importName + " import file");
        } catch (POIXMLException ex) {
            throw new BusinessException("Invalid XLSX file for " + importName + " import");
        }
    }
    private List<ProductCodeImportRow> readXlsxProductCodeRows(XSSFWorkbook workbook, String importName) {
        Sheet sheet = firstSheet(workbook, importName);
        DataFormatter formatter = new DataFormatter(Locale.ROOT);
        FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
        Row headerRow = sheet.getRow(0);
        if (headerRow == null) {
            throw new BusinessException(importName + " import file has no header row");
        }
        Map<Integer, String> headers = productCodeHeaders(headerRow, formatter, evaluator);
        if (headers.isEmpty()) {
            throw new BusinessException(importName + " import file has no headers");
        }
        List<ProductCodeImportRow> rows = new ArrayList<>();
        for (Row row : sheet) {
            if (row.getRowNum() == 0) {
                continue;
            }
            Map<String, String> values = new HashMap<>();
            boolean hasValue = false;
            for (Map.Entry<Integer, String> header : headers.entrySet()) {
                String value = readString(row, header.getKey(), formatter, evaluator);
                if (!value.isBlank()) {
                    hasValue = true;
                    values.put(header.getValue(), value);
                }
            }
            if (hasValue) {
                rows.add(new ProductCodeImportRow(row.getRowNum() + 1, values));
            }
        }
        return rows;
    }
    private Map<Integer, String> productCodeHeaders(Row headerRow, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<Integer, String> headers = new HashMap<>();
        short lastCell = headerRow.getLastCellNum();
        for (int index = 0; index < lastCell; index++) {
            String header = normalizeHeader(readString(headerRow, index, formatter, evaluator));
            if (!header.isBlank()) {
                headers.put(index, header);
            }
        }
        return headers;
    }
    private List<ProductCodeImportRow> readCsvProductCodeRows(MultipartFile file) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) {
                throw new BusinessException("Import file is empty");
            }
            List<String> headers = parseCsvLine(headerLine).stream().map(this::normalizeHeader).toList();
            List<ProductCodeImportRow> rows = new ArrayList<>();
            String line;
            int rowNumber = 1;
            while ((line = reader.readLine()) != null) {
                rowNumber++;
                List<String> values = parseCsvLine(line);
                Map<String, String> row = new HashMap<>();
                boolean hasValue = false;
                for (int index = 0; index < headers.size() && index < values.size(); index++) {
                    String header = headers.get(index);
                    String value = values.get(index).trim();
                    if (!header.isBlank() && !value.isBlank()) {
                        hasValue = true;
                        row.put(header, value);
                    }
                }
                if (hasValue) {
                    rows.add(new ProductCodeImportRow(rowNumber, row));
                }
            }
            return rows;
        }
    }
    private List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char delimiter = line.indexOf(';') >= 0 ? ';' : ',';
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == delimiter && !quoted) {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        values.add(current.toString());
        return values;
    }
    private void validateProductCodeFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Import file is empty");
        }
        String fileName = file.getOriginalFilename();
        if (fileName != null && !fileName.isBlank()) {
            String lower = fileName.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".xlsx") && !lower.endsWith(".csv")) {
                throw new BusinessException("Only .xlsx and .csv import files are supported");
            }
        }
    }
    private String firstValue(ProductCodeImportRow row, String... keys) {
        for (String key : keys) {
            String value = row.values().get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
    private String productCodeDescription(ProductCodeImportRow row) {
        String description = firstValue(row, "description", "descriptionru", "descript", "описание");
        String ru = firstValue(row, "descriptionru", "nameru", "groupnameru", "classnameru", "positionnameru", "subpositionnameru", "наименование", "название", "описание");
        String en = firstValue(row, "descriptionen", "nameen", "namelat", "nameeng");
        String uz = firstValue(row, "descriptionuz", "nameuz", "groupnameuz", "classnameuz", "positionnameuz", "subpositionnameuz");
        if (ru == null && en == null && uz == null) {
            return description;
        }
        return "ru: " + firstNonBlank(ru, "") + "\nen: " + firstNonBlank(en, "") + "\nuz: " + firstNonBlank(uz, "");
    }
    private String productCodeStatus(ProductCodeImportRow row) {
        String raw = firstValue(row, "status", "state", "isactive", "статус");
        if (raw == null) {
            return "ACTIVE";
        }
        if ("1".equals(raw) || "true".equalsIgnoreCase(raw) || "active".equalsIgnoreCase(raw) || "активен".equalsIgnoreCase(raw)) {
            return "ACTIVE";
        }
        return normalizeCode(raw);
    }
    private void upsertLinkedTnvedFromIkpuImport(ProductCodeImportRow row, LocalDateTime now) {
        String code = tnvedCode(row);
        if (code == null) {
            return;
        }
        upsertTnvedImportRow(row, code, now);
    }
    private void upsertTnvedImportRow(ProductCodeImportRow row, String code, LocalDateTime now) {
        checkLength(code, 40, "TN VED code", row.rowNumber());
        TnvedCode item = tnvedCodeRepository.findByTenantIdAndCompanyIdAndTnvedCodeAndIsDeletedFalse(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            code
        ).orElseGet(TnvedCode::new);
        item.setCode(code);
        item.setTnvedCode(code);
        String importedDescription = productCodeDescription(row);
        item.setName(firstNonBlank(
            importedDescription,
            entityName(firstNonBlank(firstValue(row, "tnvedname", "tnvednameru", "hsname", "hsnameru", "commodityname", "commoditynameru", "name", "nameru", "title", "fullname", "label"), "TN VED Item"), "TN VED Item")
        ));
        item.setDescription(importedDescription);
        item.setStatus(productCodeStatus(row));
        item.setParentCode(normalizeProductCode(firstValue(row, "parentcode", "parent", "родительскийкод", "родитель")));
        item.setSectionCode(normalizeProductCode(firstValue(row, "sectioncode", "section", "кодраздела", "раздел")));
        item.setUnitCode(normalizeProductCode(firstValue(row, "unitcode", "unit", "uom", "categoryunitname", "unitsname", "recommendedunitsname", "единицаизмерения", "едизм", "единица")));
        item.setDutyRate(decimalOrNull(firstValue(row, "dutyrate", "customsdutyrate", "ставкапошлины", "пошлина")));
        item.setVatRate(decimalOrNull(firstValue(row, "vatrate", "ndsrate", "ratevat", "ставкандс", "ндс")));
        item.setSourceSystem(ExternalIntegrationSystem.SOLIQ.name());
        item.setExternalId(firstValue(row, "tnvedexternalid", "externalid", "id", "guid", "pkey", "внешнийкод"));
        item.setLastSyncedAt(now);
        tnvedCodeRepository.save(item);
    }
    private void upsertMxik(ProductCodeImportRow row, String code) {
        MXIK mxik = mxikRepository.findByKod(code).orElseGet(MXIK::new);
        mxik.setKod(code);
        String importedName = entityName(firstNonBlank(
            firstValue(row, "name", "nameru", "title", "productname", "fullname", "label", "mxikname", "subpositionnameru", "positionnameru", "classnameru", "groupnameru", "наименование", "название", "наименованиеру"),
            "MXIK Item"
        ), "MXIK Item");
        mxik.setName(importedName);
        mxik.setType(firstNonBlank(trimToNull(firstValue(row, "type", "class", "classifiertype")), booleanValue(firstValue(row, "service", "isservice", "услуга", "этоуслуга")) ? "SERVICE" : "GOODS"));
        mxik.setGroupName(trimToNull(firstValue(row, "groupname", "groupcode", "group", "группа", "кодгруппы")));
        mxik.setGroupNameRu(trimToNull(firstValue(row, "groupnameru", "groupname")));
        mxik.setClassName(trimToNull(firstValue(row, "classname", "classcode", "code")));
        mxik.setClassNameRu(trimToNull(firstValue(row, "classnameru", "classname")));
        mxik.setPositionName(trimToNull(firstValue(row, "positionname", "position")));
        mxik.setPositionNameRu(trimToNull(firstValue(row, "positionnameru", "positionname")));
        mxik.setSubPositionName(trimToNull(firstValue(row, "subpositionname", "subposition")));
        mxik.setSubPositionNameRu(trimToNull(firstValue(row, "subpositionnameru", "subpositionname", "mxikname")));
        mxik.setBrandName(trimToNull(firstValue(row, "brandname", "brand")));
        mxik.setBrandNameRu(trimToNull(firstValue(row, "brandnameru", "brandname")));
        mxik.setAttributeName(trimToNull(firstValue(row, "attributename", "attribute")));
        mxik.setAttributeNameRu(trimToNull(firstValue(row, "attributenameru", "attributename")));
        mxik.setBarcode(trimToNull(firstValue(row, "barcode", "gtin")));
        mxikRepository.save(mxik);
    }
    private String tnvedCode(ProductCodeImportRow row) {
        return normalizeProductCode(firstValue(row, "tnvedcode", "tnved", "tnvedcode", "hscode", "internationalcode", "commoditycode", "code", "кодтнвэд", "кодтнвед", "тнвэд", "тнвед", "код"));
    }
    private BigDecimal decimalOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace(" ", "");
        if (normalized.contains(",") && !normalized.contains(".")) {
            normalized = normalized.replace(',', '.');
        } else {
            normalized = normalized.replace(",", "");
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
    private boolean booleanValue(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("да");
    }
    private String firstNonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
    private String entityName(String value, String fallback) {
        String resolved = firstNonBlank(value, fallback);
        return resolved.length() <= 240 ? resolved : resolved.substring(0, 240);
    }
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
    private String normalizeProductCode(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        return normalized.isBlank() ? null : normalized;
    }
    private String normalizeHeader(String value) {
        return value == null ? "" : value.replace("\uFEFF", "").trim().toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }
    private BusinessException importRowError(ProductCodeImportRow row, String message) {
        return new BusinessException("Row " + row.rowNumber() + ": " + message);
    }
    private void validateXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Import file is empty");
        }
        String fileName = file.getOriginalFilename();
        if (fileName != null && !fileName.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("Only .xlsx import files are supported");
        }
        String contentType = file.getContentType();
        if (contentType != null
            && !contentType.isBlank()
            && !"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet".equals(contentType)
            && !"application/octet-stream".equals(contentType)) {
            throw new BusinessException("Only XLSX content type is supported");
        }
    }
    private Sheet firstSheet(XSSFWorkbook workbook, String importName) {
        if (workbook.getNumberOfSheets() == 0) {
            throw new BusinessException("No sheet found in " + importName + " import file");
        }
        return workbook.getSheetAt(0);
    }
    private boolean emptyRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator, int... indexes) {
        for (int index : indexes) {
            if (!readString(row, index, formatter, evaluator).isBlank()) {
                return false;
            }
        }
        return true;
    }
    private String requiredString(Row row, int index, String label, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            throw rowError(row, "Required column is empty: " + label);
        }
        return value;
    }
    private String readString(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (row.getCell(index) == null) {
            return "";
        }
        return formatter.formatCellValue(row.getCell(index), evaluator).trim();
    }
    private BigDecimal readDecimal(
        Row row,
        int index,
        BigDecimal defaultValue,
        String label,
        DataFormatter formatter,
        FormulaEvaluator evaluator
    ) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            if (defaultValue == null) {
                throw rowError(row, "Required numeric column is empty: " + label);
            }
            return defaultValue;
        }
        String normalized = value.replace(" ", "");
        if (normalized.contains(",") && !normalized.contains(".")) {
            normalized = normalized.replace(',', '.');
        } else {
            normalized = normalized.replace(",", "");
        }
        try {
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            throw rowError(row, "Invalid decimal value for " + label + ": " + value);
        }
    }
    private BigDecimal requiredNonNegative(BigDecimal value, String label, Row row) {
        if (value.signum() < 0) {
            throw rowError(row, "Negative value is not allowed for " + label);
        }
        return value;
    }
    private UUID requiredUuid(Row row, int index, String label, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = requiredString(row, index, label, formatter, evaluator);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw rowError(row, "Invalid UUID for " + label + ": " + value);
        }
    }

    private UUID requiredMaterialId(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = requiredString(row, index, "material id or code", formatter, evaluator);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            Material material = materialRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), value)
                .orElseThrow(() -> rowError(row, "Material code does not exist in current tenant: " + value));
            if (!com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId())) {
                throw rowError(row, "Material code does not exist in current company: " + value);
            }
            return material.getId();
        }
    }

    private UUID requiredWarehouseId(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = requiredString(row, index, "warehouse id or code", formatter, evaluator);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            Warehouse warehouse = warehouseRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), value)
                .orElseThrow(() -> rowError(row, "Warehouse code does not exist in current tenant: " + value));
            if (!com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId())) {
                throw rowError(row, "Warehouse code does not exist in current company: " + value);
            }
            return warehouse.getId();
        }
    }
    private UUID readOptionalUuid(Row row, int index, String label, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw rowError(row, "Invalid UUID for " + label + ": " + value);
        }
    }
    private int readInteger(Row row, int index, int defaultValue, String label, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            throw rowError(row, "Invalid integer value for " + label + ": " + value);
        }
    }
    private boolean readBoolean(Row row, int index, boolean defaultValue, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            return defaultValue;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("true") || normalized.equals("1") || normalized.equals("yes") || normalized.equals("да");
    }
    private LocalDate readDate(Row row, int index, DataFormatter formatter, FormulaEvaluator evaluator) {
        String value = readString(row, index, formatter, evaluator);
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException ex) {
            throw rowError(row, "Invalid ISO date value: " + value);
        }
    }
    private Map<String, Integer> headerColumns(Row header, DataFormatter formatter, FormulaEvaluator evaluator) {
        Map<String, Integer> columns = new HashMap<>();
        if (header == null) {
            return columns;
        }
        for (int index = 0; index < header.getLastCellNum(); index++) {
            String value = readString(header, index, formatter, evaluator)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "");
            if (!value.isBlank()) {
                columns.putIfAbsent(value, index);
            }
        }
        return columns;
    }
    private int headerColumn(Map<String, Integer> headers, int fallback, String... aliases) {
        for (String alias : aliases) {
            Integer index = headers.get(alias);
            if (index != null) {
                return index;
            }
        }
        return fallback;
    }
    private void validateValuationMethod(String valuationMethod, Row row) {
        try {
            ValuationMethod.valueOf(valuationMethod);
        } catch (IllegalArgumentException ex) {
            throw rowError(row, "Unknown valuation method: " + valuationMethod);
        }
    }
    private void requireMaterialInCurrentTenant(UUID materialId, Row row) {
        Material material = materialRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), materialId)
            .orElseThrow(() -> rowError(row, "Material does not exist in current tenant: " + materialId));
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(material.getCompanyId())) {
            throw rowError(row, "Material does not exist in current company: " + materialId);
        }
        if (material.getMaterialType() != MaterialType.TMB) {
            throw rowError(row, "Stock balances can be imported only for TMB materials: " + materialId);
        }
    }
    private void requireWarehouseInCurrentTenant(UUID warehouseId, Row row) {
        Warehouse warehouse = warehouseRepository.findByTenantIdAndIdAndIsDeletedFalse(TenantContext.currentTenant(), warehouseId)
            .orElseThrow(() -> rowError(row, "Warehouse does not exist in current tenant: " + warehouseId));
        if (!com.enterprise.mm.security.AccessScope.canAccessCompany(warehouse.getCompanyId())) {
            throw rowError(row, "Warehouse does not exist in current company: " + warehouseId);
        }
    }
    private void checkLength(String value, int maxLength, String label, Row row) {
        if (value.length() > maxLength) {
            throw rowError(row, label + " is longer than " + maxLength + " characters");
        }
    }
    private void checkLength(String value, int maxLength, String label, int rowNumber) {
        if (value.length() > maxLength) {
            throw new BusinessException("Row " + rowNumber + ": " + label + " is longer than " + maxLength + " characters");
        }
    }
    private BusinessException rowError(Row row, String message) {
        return new BusinessException("Row " + (row.getRowNum() + 1) + ": " + message);
    }
    private String normalizeCode(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
    private record ProductCodeImportRow(int rowNumber, Map<String, String> values) {
    }
}
