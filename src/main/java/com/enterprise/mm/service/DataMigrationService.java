package com.enterprise.mm.service;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationTemplateDto;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationValidationResponse;
import com.enterprise.mm.payload.DataMigrationDtos.MigrationValidationRowDto;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DataMigrationRow;
import com.enterprise.mm.entity.DataMigrationRun;
import com.enterprise.mm.entity.StorageLocation;
import com.enterprise.mm.entity.Warehouse;
import com.enterprise.mm.repository.CurrencyRepository;
import com.enterprise.mm.repository.DataMigrationRowRepository;
import com.enterprise.mm.repository.DataMigrationRunRepository;
import com.enterprise.mm.repository.MaterialRepository;
import com.enterprise.mm.repository.StorageLocationRepository;
import com.enterprise.mm.repository.UnitOfMeasureRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;
import java.util.UUID;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class DataMigrationService {
    private static final String STOCK_BALANCES = "1c-stock-balances";
    private static final String MATERIALS = "materials-master-data";
    private static final String WAREHOUSE_INVENTORY = "warehouse-inventory-count";
    private static final String REFERENCE_MAPPINGS = "reference-mapping";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
        DateTimeFormatter.ISO_LOCAL_DATE,
        DateTimeFormatter.ofPattern("dd.MM.uuuu"),
        DateTimeFormatter.ofPattern("dd/MM/uuuu"),
        DateTimeFormatter.ofPattern("MM/dd/uuuu")
    );
    private final DataMigrationRunRepository runRepository;
    private final DataMigrationRowRepository rowRepository;
    private final MaterialRepository materialRepository;
    private final WarehouseRepository warehouseRepository;
    private final StorageLocationRepository storageLocationRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final CurrencyRepository currencyRepository;
    private final AuditService auditService;

    public List<MigrationTemplateDto> templates() {
        return templates(null);
    }

    public List<MigrationTemplateDto> templates(String search) {
        List<MigrationTemplateDto> templates = List.of(
            template(STOCK_BALANCES, "Остатки 1C", "Безопасная сверка остатков из 1C до ввода через инвентаризацию."),
            template(MATERIALS, "НСИ материалов", "Нормализация карточек материалов: артикул, ИКПУ, ТН ВЭД, производитель, единицы."),
            template(WAREHOUSE_INVENTORY, "Инвентаризация склада", "Фактический пересчёт по складам/ячейкам для ввода начальных остатков."),
            template(REFERENCE_MAPPINGS, "Маппинг НСИ", "Связки MM с 1C/FI/казначейством: внешние коды, счета, области учёта.")
        );
        if (search == null || search.isBlank()) {
            return templates;
        }
        String needle = search.trim().toLowerCase(Locale.ROOT);
        return templates.stream()
            .filter(item -> contains(item.code(), needle) || contains(item.name(), needle) || contains(item.description(), needle))
            .toList();
    }
    public String templateContentType() {
        return XLSX;
    }
    public byte[] templateWorkbook(String templateCode) {
        return switch (normalizeTemplateCode(templateCode)) {
            case STOCK_BALANCES -> stockBalancesTemplate();
            case MATERIALS -> materialsTemplate();
            case WAREHOUSE_INVENTORY -> inventoryCountTemplate();
            case REFERENCE_MAPPINGS -> referenceMappingTemplate();
            default -> throw new BusinessException("Unknown migration template: " + templateCode);
        };
    }
    @Transactional
    public MigrationValidationResponse validateAndStage(String templateCode, MultipartFile file) {
        String normalizedTemplate = normalizeTemplateCode(templateCode);
        if (!STOCK_BALANCES.equals(normalizedTemplate) && !WAREHOUSE_INVENTORY.equals(normalizedTemplate)) {
            throw new BusinessException("Validation is available for 1C stock balances and warehouse inventory templates");
        }
        validateXlsx(file);
        try (XSSFWorkbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = workbook.getNumberOfSheets() == 0 ? null : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new BusinessException("XLSX file has no data sheet");
            }
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            Map<String, Integer> headers = headers(sheet, formatter, evaluator);
            DataMigrationRun run = createRun(normalizedTemplate, file.getOriginalFilename());
            Set<String> seenKeys = new HashSet<>();
            List<DataMigrationRow> rows = new ArrayList<>();
            for (Row row : sheet) {
                if (row.getRowNum() == 0 || isBlankDataRow(row, formatter, evaluator)) {
                    continue;
                }
                rows.add(validateRow(run.getId(), row, headers, formatter, evaluator, seenKeys, normalizedTemplate));
            }
            rowRepository.saveAll(rows);
            int errors = (int) rows.stream().filter(item -> "ERROR".equals(item.getRowStatus())).count();
            int warnings = (int) rows.stream().filter(item -> "WARNING".equals(item.getRowStatus())).count();
            int valid = (int) rows.stream().filter(item -> "OK".equals(item.getRowStatus())).count();
            run.setTotalRows(rows.size());
            run.setValidRows(valid);
            run.setWarningRows(warnings);
            run.setErrorRows(errors);
            run.setStatus(errors > 0 ? "VALIDATED_WITH_ERRORS" : "READY_FOR_INVENTORY");
            run.setFinishedAt(LocalDateTime.now());
            runRepository.save(run);
            auditService.log("DataMigrationRun", run.getId(), "VALIDATE_XLSX", run.getName(), null, null);
            return response(run, rows);
        } catch (IOException ex) {
            throw new BusinessException("Cannot read migration XLSX file");
        } catch (POIXMLException | NotOfficeXmlFileException ex) {
            throw new BusinessException("Invalid XLSX file for migration");
        }
    }
    public List<MigrationValidationRowDto> rows(UUID runId) {
        return rowRepository.findByTenantIdAndCompanyIdAndRunIdAndIsDeletedFalseOrderByRowNumber(
            TenantContext.currentTenant(),
            CompanyContext.currentCompany(),
            runId
        ).stream().map(this::rowDto).toList();
    }
    private boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }

    private MigrationTemplateDto template(String code, String name, String description) {
        return new MigrationTemplateDto(code, name, description, "/api/migration/templates/" + code + ".xlsx");
    }
    private DataMigrationRun createRun(String migrationType, String fileName) {
        DataMigrationRun run = new DataMigrationRun();
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        run.setCode("MIG-" + stamp);
        run.setName("Проверка миграции " + migrationType);
        run.setStatus("VALIDATING");
        run.setMigrationType(migrationType);
        run.setSourceSystem(STOCK_BALANCES.equals(migrationType) ? "1C" : "WAREHOUSE_COUNT");
        run.setFileName(blankToNull(fileName));
        return runRepository.save(run);
    }
    private DataMigrationRow validateRow(
        UUID runId,
        Row row,
        Map<String, Integer> headers,
        DataFormatter formatter,
        FormulaEvaluator evaluator,
        Set<String> seenKeys,
        String migrationType
    ) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        DataMigrationRow item = new DataMigrationRow();
        item.setRunId(runId);
        item.setRowNumber(row.getRowNum() + 1);
        item.setCode("ROW-" + (row.getRowNum() + 1));
        item.setName("Строка проверки миграции");
        item.setStatus("ACTIVE");
        item.setCompanyCode(upper(value(row, headers, formatter, evaluator, "companyCode", "company", "organization", "организация", "компания")));
        item.setWarehouseCode(upper(requiredValue(row, headers, formatter, evaluator, errors, "warehouseCode", "warehouse", "кодСклада", "склад")));
        item.setStorageLocationCode(upper(value(row, headers, formatter, evaluator, "storageLocationCode", "locationCode", "ячейка", "кодЯчейки", "зона")));
        item.setMaterialCode(upper(value(row, headers, formatter, evaluator, "materialCode", "кодМатериала", "кодНоменклатуры", "номенклатураКод", "код")));
        item.setMaterialName(value(row, headers, formatter, evaluator, "materialName", "material", "номенклатура", "материал", "наименование"));
        item.setArticle(value(row, headers, formatter, evaluator, "article", "артикул"));
        item.setUomCode(upper(requiredValue(row, headers, formatter, evaluator, errors, "uomCode", "unit", "единица", "едИзм")));
        item.setBatchNumber(value(row, headers, formatter, evaluator, "batchNumber", "batch", "партия"));
        item.setSerialNumber(value(row, headers, formatter, evaluator, "serialNumber", "serial", "серийныйНомер"));
        item.setQuantity(decimal(row, headers, formatter, evaluator, errors, true, "quantity", "qty", "количество", "остаток", "факт"));
        item.setUnitCost(decimal(row, headers, formatter, evaluator, errors, false, "unitCost", "price", "цена", "стоимостьЕд"));
        item.setTotalValue(decimal(row, headers, formatter, evaluator, errors, false, "totalValue", "amount", "сумма", "стоимость"));
        item.setCurrencyCode(upper(value(row, headers, formatter, evaluator, "currencyCode", "currency", "валюта")));
        item.setAccountCode(value(row, headers, formatter, evaluator, "accountCode", "account", "счет", "счёт", "счетУчета"));
        item.setIkpuCode(digits(value(row, headers, formatter, evaluator, "ikpuCode", "ikpu", "mxik", "икпу", "мхик")));
        item.setTnvedCode(digits(value(row, headers, formatter, evaluator, "tnvedCode", "tnved", "tnVed", "тнвэд", "кодТнвэд")));
        item.setExpirationDate(date(row, headers, formatter, evaluator, errors, false, "expirationDate", "срокГодности", "годенДо"));
        item.setBalanceDate(date(row, headers, formatter, evaluator, errors, true, "balanceDate", "date", "датаБаланса", "дата"));
        item.setSourceDocument(value(row, headers, formatter, evaluator, "sourceDocument", "document", "документ"));
        validateReferenceState(item, errors, warnings, migrationType);
        validateAmounts(item, errors, warnings);
        validateDuplicates(item, seenKeys, errors);
        item.setSourceKey(sourceKey(item));
        item.setErrorMessages(String.join("\n", errors));
        item.setWarningMessages(String.join("\n", warnings));
        item.setRowStatus(errors.isEmpty() ? warnings.isEmpty() ? "OK" : "WARNING" : "ERROR");
        return item;
    }
    private void validateReferenceState(DataMigrationRow item, List<String> errors, List<String> warnings, String migrationType) {
        if (isBlank(item.getMaterialCode()) && isBlank(item.getMaterialName()) && isBlank(item.getArticle()) && isBlank(item.getIkpuCode()) && isBlank(item.getTnvedCode())) {
            errors.add("Не указан материал: нужен код, наименование, артикул, ИКПУ или ТН ВЭД");
        }
        if (!isBlank(item.getMaterialCode()) && materialRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), item.getMaterialCode()).isEmpty()) {
            warnings.add("Материал с кодом " + item.getMaterialCode() + " не найден в MM: сначала загрузите НСИ материалов или маппинг 1C");
        }
        Warehouse warehouse = null;
        if (!isBlank(item.getWarehouseCode())) {
            warehouse = warehouseRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), item.getWarehouseCode()).orElse(null);
            if (warehouse == null) {
                errors.add("Склад " + item.getWarehouseCode() + " не найден в MM");
            }
        }
        if (!isBlank(item.getStorageLocationCode())) {
            StorageLocation location = storageLocationRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), item.getStorageLocationCode()).orElse(null);
            if (location == null) {
                warnings.add("Ячейка/зона " + item.getStorageLocationCode() + " не найдена: можно создать до инвентаризации");
            } else if (warehouse != null && location.getWarehouseId() != null && !Objects.equals(location.getWarehouseId(), warehouse.getId())) {
                errors.add("Ячейка " + item.getStorageLocationCode() + " относится к другому складу");
            }
        }
        if (!isBlank(item.getUomCode()) && !unitOfMeasureRepository.existsByScopedLookup(
            TenantContext.currentTenant(), CompanyContext.currentCompany(), item.getUomCode())) {
            errors.add("Единица измерения " + item.getUomCode() + " не найдена в справочнике");
        }
        if (isBlank(item.getCurrencyCode())) {
            item.setCurrencyCode("UZS");
            warnings.add("Валюта не указана: применена UZS для проверки");
        } else if (currencyRepository.findByTenantIdAndCodeAndIsDeletedFalse(TenantContext.currentTenant(), item.getCurrencyCode()).isEmpty()
            && currencyRepository.findByTenantIdAndIsoCodeAndIsDeletedFalse(TenantContext.currentTenant(), item.getCurrencyCode()).isEmpty()) {
            warnings.add("Валюта " + item.getCurrencyCode() + " не найдена в справочнике валют");
        }
        if (isBlank(item.getIkpuCode())) {
            warnings.add("ИКПУ не указан: для счетов-фактур Soliq потребуется заполнить код товара/услуги");
        }
        if (isBlank(item.getTnvedCode())) {
            warnings.add("ТН ВЭД не указан: для импортных материалов и таможенной аналитики код нужно заполнить");
        }
        if (isBlank(item.getAccountCode())) {
            warnings.add("Счет учета не указан: FI/1C маппинг нужно заполнить перед проводками");
        }
        if (WAREHOUSE_INVENTORY.equals(migrationType) && item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            warnings.add("Фактический остаток равен нулю: строка будет полезна только для сверки расхождений");
        }
    }
    private void validateAmounts(DataMigrationRow item, List<String> errors, List<String> warnings) {
        if (item.getQuantity() != null && item.getQuantity().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("Количество не может быть отрицательным");
        }
        if (item.getUnitCost() != null && item.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("Цена за единицу не может быть отрицательной");
        }
        if (item.getTotalValue() != null && item.getTotalValue().compareTo(BigDecimal.ZERO) < 0) {
            errors.add("Сумма не может быть отрицательной");
        }
        if (item.getBalanceDate() == null) {
            return;
        }
        if (item.getExpirationDate() != null && item.getExpirationDate().isBefore(item.getBalanceDate())) {
            warnings.add("Срок годности раньше даты остатка");
        }
        if (item.getQuantity() != null && item.getUnitCost() != null && item.getTotalValue() != null) {
            BigDecimal expected = item.getQuantity().multiply(item.getUnitCost()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal actual = item.getTotalValue().setScale(2, RoundingMode.HALF_UP);
            if (expected.subtract(actual).abs().compareTo(new BigDecimal("0.05")) > 0) {
                warnings.add("Сумма не совпадает с количеством * ценой: ожидалось " + expected + ", в файле " + actual);
            }
        }
    }
    private void validateDuplicates(DataMigrationRow item, Set<String> seenKeys, List<String> errors) {
        String key = Stream.of(item.getWarehouseCode(), item.getStorageLocationCode(), firstNonBlank(item.getMaterialCode(), item.getArticle(), item.getIkpuCode(), item.getTnvedCode(), item.getMaterialName()), item.getBatchNumber(), item.getSerialNumber())
            .map(value -> value == null ? "" : value)
            .reduce((a, b) -> a + "|" + b)
            .orElse("");
        if (!seenKeys.add(key)) {
            errors.add("Дубль строки внутри XLSX по складу/ячейке/материалу/партии/серии");
        }
    }
    private MigrationValidationResponse response(DataMigrationRun run, List<DataMigrationRow> rows) {
        return new MigrationValidationResponse(
            run.getId(),
            run.getMigrationType(),
            run.getSourceSystem(),
            run.getStatus(),
            run.getFileName(),
            run.getTotalRows(),
            run.getValidRows(),
            run.getWarningRows(),
            run.getErrorRows(),
            run.getFinishedAt(),
            rows.stream().map(this::rowDto).toList()
        );
    }
    private MigrationValidationRowDto rowDto(DataMigrationRow row) {
        return new MigrationValidationRowDto(
            row.getRowNumber(),
            row.getRowStatus(),
            row.getSourceKey(),
            row.getCompanyCode(),
            row.getWarehouseCode(),
            row.getStorageLocationCode(),
            row.getMaterialCode(),
            row.getMaterialName(),
            row.getArticle(),
            row.getUomCode(),
            row.getQuantity(),
            row.getUnitCost(),
            row.getTotalValue(),
            row.getCurrencyCode(),
            row.getAccountCode(),
            row.getIkpuCode(),
            row.getTnvedCode(),
            row.getBalanceDate(),
            splitMessages(row.getErrorMessages()),
            splitMessages(row.getWarningMessages())
        );
    }
    private List<String> splitMessages(String value) {
        if (isBlank(value)) {
            return List.of();
        }
        return Stream.of(value.split("\\n")).map(String::trim).filter(line -> !line.isBlank()).toList();
    }
    private byte[] stockBalancesTemplate() {
        String[] headers = {
            "companyCode", "warehouseCode", "storageLocationCode", "materialCode", "materialName", "article", "uomCode",
            "batchNumber", "serialNumber", "quantity", "unitCost", "totalValue", "currencyCode", "accountCode",
            "ikpuCode", "tnvedCode", "expirationDate", "balanceDate", "sourceDocument", "comment"
        };
        List<String[]> examples = List.<String[]>of(
            new String[] {"TST", "WH-MAIN", "SL-RAW-A1", "MAT-STEEL-001", "Steel sheet 2mm", "ART-ST-2MM", "PCS", "B-2026-001", "", "1250", "125000", "156250000", "UZS", "1010", "01234501001000000", "7208390000", "", "2026-01-01", "1C Остатки 2026-01-01", "пример"}
        );
        return workbook("Остатки 1C", headers, examples, List.of(
            "Назначение: безопасная загрузка остатков из 1C для сверки и последующего ввода через инвентаризацию.",
            "Обязательные поля: warehouseCode, materialName или materialCode, uomCode, quantity, balanceDate.",
            "Склад и единица измерения должны существовать в MM. Материалы без кода будут помечены предупреждением.",
            "Файл не изменяет остатки напрямую: система создаёт staging run и строки проверки."
        ));
    }
    private byte[] inventoryCountTemplate() {
        String[] headers = {
            "companyCode", "warehouseCode", "storageLocationCode", "materialCode", "materialName", "article", "uomCode",
            "batchNumber", "serialNumber", "quantity", "unitCost", "totalValue", "currencyCode", "ikpuCode", "tnvedCode",
            "expirationDate", "balanceDate", "sourceDocument", "comment"
        };
        List<String[]> examples = List.<String[]>of(
            new String[] {"TST", "WH-HAZ", "SL-HAZ-FUEL", "MAT-DIESEL-D2", "Diesel fuel D2", "FUEL-D2", "L", "D2-0626", "", "8000", "11000", "88000000", "UZS", "01234503001000000", "2710194300", "", "2026-01-02", "Инвентаризация склада", "факт"}
        );
        return workbook("Инвентаризация", headers, examples, List.of(
            "Назначение: фактический пересчёт склада для ввода начальных остатков и корректировок.",
            "Заполняйте фактическое quantity. После чистой проверки создайте документ Инвентаризация и проведите корректировки.",
            "Штрихкод/QR/RFID можно указать в materialCode или article, если в MM настроен маппинг НСИ."
        ));
    }
    private byte[] materialsTemplate() {
        String[] headers = {
            "materialCode", "materialNameRu", "materialNameEn", "materialNameUz", "materialGroupCode", "uomCode", "article",
            "manufacturer", "countryOfOrigin", "ikpuCode", "tnvedCode", "barcode", "qrCode", "rfidTag", "hazardClass",
            "shelfLifeDays", "valuationMethod", "minStock", "maxStock", "status", "comment"
        };
        List<String[]> examples = List.<String[]>of(
            new String[] {"MAT-FLT-HYD-10", "Гидрофильтр 10 микрон", "Hydraulic filter 10 micron", "10 mikron gidrofiltr", "SPARE", "PCS", "FLT-HYD-10", "Parker", "US", "01234567890123456", "8421290009", "8600000000010", "", "", "", "730", "WEIGHTED_AVERAGE", "10", "200", "ACTIVE", "пример"}
        );
        return workbook("Материалы", headers, examples, List.of(
            "Назначение: предварительное обогащение НСИ материалов до миграции остатков.",
            "ИКПУ и ТН ВЭД должны быть уникальными в соответствующих справочниках.",
            "Производитель ведётся как справочник; если производителя нет, создайте его перед импортом."
        ));
    }
    private byte[] referenceMappingTemplate() {
        String[] headers = {
            "referenceEntityType", "referenceCode", "referenceName", "companyCode", "externalSystem", "externalCode",
            "externalName", "accountingArea", "chartOfAccountsCode", "accountCode", "subAccountCode", "costCenter",
            "warehouseAccountingCode", "taxCode", "currencyCode", "priority", "defaultMapping", "validFrom", "validTo", "comment"
        };
        List<String[]> examples = List.<String[]>of(
            new String[] {"MATERIAL", "MAT-FLT-HYD-10", "Гидрофильтр 10 микрон", "TST", "ONE_C", "00000000123", "Фильтр гидравлический", "WAREHOUSE", "UZ-NAS", "1010", "", "LOG", "WH-TASHKENT", "VAT12", "UZS", "100", "true", "2026-01-01", "", "пример"}
        );
        return workbook("Маппинг НСИ", headers, examples, List.of(
            "Назначение: связать записи MM с внешними кодами 1C/FI/казначейства/бюджетирования.",
            "Ключ уникальности: referenceEntityType + externalSystem + externalCode + accountingArea + companyCode.",
            "Заполните счета и области учёта до отправки проводок."
        ));
    }
    private byte[] workbook(String dataSheetName, String[] headers, List<String[]> examples, List<String> helpLines) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            Sheet data = workbook.createSheet(dataSheetName);
            Row header = data.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = header.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                data.setColumnWidth(i, Math.max(16, headers[i].length() + 4) * 256);
            }
            for (int r = 0; r < examples.size(); r++) {
                Row example = data.createRow(r + 1);
                String[] values = examples.get(r);
                for (int c = 0; c < values.length; c++) {
                    example.createCell(c).setCellValue(values[c]);
                }
            }
            data.createFreezePane(0, 1);
            Sheet help = workbook.createSheet("Справка");
            for (int i = 0; i < helpLines.size(); i++) {
                help.createRow(i).createCell(0).setCellValue(helpLines.get(i));
            }
            help.createRow(helpLines.size() + 1).createCell(0).setCellValue("Статусы проверки: OK — можно вводить через инвентаризацию, WARNING — нужно проверить, ERROR — нельзя использовать до исправления.");
            help.setColumnWidth(0, 120 * 256);
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new BusinessException("Cannot generate XLSX migration template");
        }
    }
    private Map<String, Integer> headers(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator) {
        Row header = sheet.getRow(0);
        if (header == null) {
            throw new BusinessException("XLSX header row is empty");
        }
        Map<String, Integer> headers = new HashMap<>();
        for (Cell cell : header) {
            String name = normalizeHeader(formatter.formatCellValue(cell, evaluator));
            if (!name.isBlank()) {
                headers.put(name, cell.getColumnIndex());
            }
        }
        return headers;
    }
    private String requiredValue(Row row, Map<String, Integer> headers, DataFormatter formatter, FormulaEvaluator evaluator, List<String> errors, String... names) {
        String value = value(row, headers, formatter, evaluator, names);
        if (isBlank(value)) {
            errors.add("Не заполнено обязательное поле: " + names[0]);
        }
        return value;
    }
    private String value(Row row, Map<String, Integer> headers, DataFormatter formatter, FormulaEvaluator evaluator, String... names) {
        for (String name : names) {
            Integer index = headers.get(normalizeHeader(name));
            if (index != null) {
                String value = formatter.formatCellValue(row.getCell(index), evaluator).trim();
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return null;
    }
    private BigDecimal decimal(Row row, Map<String, Integer> headers, DataFormatter formatter, FormulaEvaluator evaluator, List<String> errors, boolean required, String... names) {
        String value = value(row, headers, formatter, evaluator, names);
        if (isBlank(value)) {
            if (required) {
                errors.add("Не заполнено обязательное числовое поле: " + names[0]);
            }
            return null;
        }
        try {
            String normalized = value.replace("\u00a0", "").replace(" ", "").replace(",", ".");
            return new BigDecimal(normalized);
        } catch (NumberFormatException ex) {
            errors.add("Некорректное число в поле " + names[0] + ": " + value);
            return null;
        }
    }
    private LocalDate date(Row row, Map<String, Integer> headers, DataFormatter formatter, FormulaEvaluator evaluator, List<String> errors, boolean required, String... names) {
        for (String name : names) {
            Integer index = headers.get(normalizeHeader(name));
            if (index == null) {
                continue;
            }
            Cell cell = row.getCell(index);
            if (cell == null) {
                continue;
            }
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
            }
            String value = formatter.formatCellValue(cell, evaluator).trim();
            if (value.isBlank()) {
                continue;
            }
            for (DateTimeFormatter format : DATE_FORMATS) {
                try {
                    return LocalDate.parse(value, format);
                } catch (DateTimeParseException ignored) {
                    // try next accepted format
                }
            }
            errors.add("Некорректная дата в поле " + names[0] + ": " + value);
            return null;
        }
        if (required) {
            errors.add("Не заполнено обязательное поле даты: " + names[0]);
        }
        return null;
    }
    private boolean isBlankDataRow(Row row, DataFormatter formatter, FormulaEvaluator evaluator) {
        for (int i = 0; i < Math.max(row.getLastCellNum(), 0); i++) {
            if (!formatter.formatCellValue(row.getCell(i), evaluator).trim().isBlank()) {
                return false;
            }
        }
        return true;
    }
    private void validateXlsx(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("Migration file is empty");
        }
        String name = file.getOriginalFilename();
        if (name != null && !name.toLowerCase(Locale.ROOT).endsWith(".xlsx")) {
            throw new BusinessException("Only XLSX migration files are supported");
        }
    }
    private String sourceKey(DataMigrationRow item) {
        return Stream.of(item.getWarehouseCode(), item.getStorageLocationCode(), firstNonBlank(item.getMaterialCode(), item.getArticle(), item.getIkpuCode(), item.getTnvedCode(), item.getMaterialName()), item.getBatchNumber(), item.getSerialNumber())
            .map(value -> value == null ? "" : value)
            .reduce((a, b) -> a + "|" + b)
            .orElse("");
    }
    private String firstNonBlank(String... values) {
        return Stream.of(values).filter(value -> !isBlank(value)).findFirst().orElse(null);
    }
    private String normalizeTemplateCode(String code) {
        return code == null ? "" : code.trim().toLowerCase(Locale.ROOT).replace(".xlsx", "");
    }
    private String normalizeHeader(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }
    private String upper(String value) {
        return isBlank(value) ? null : value.trim().toUpperCase(Locale.ROOT);
    }
    private String digits(String value) {
        return isBlank(value) ? null : value.replaceAll("\\D", "");
    }
    private String blankToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }
    private boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }
}
