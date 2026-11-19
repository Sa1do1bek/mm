package com.enterprise.mm.inventory.availability.service;

import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.inventory.availability.enums.InventoryAvailabilityQueueKey;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityFilter;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityOverview;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityQueueDefinition;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityRow;
import com.enterprise.mm.inventory.availability.payload.InventoryAvailabilityDtos.InventoryAvailabilityWorkspaceResponse;
import com.enterprise.mm.inventory.availability.repository.InventoryAvailabilityQueryRepository;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InventoryAvailabilityWorkspaceService {
    private static final int EXPORT_PAGE_SIZE = 200;
    private static final List<String> EXPORT_COLUMNS = List.of(
            "Material code", "Material name", "Material status", "Material group",
            "Replacement code", "On hand", "Available", "Reserved", "Quarantine",
            "Open demand", "Open supply", "Nearest demand date", "Nearest supply date",
            "Projected available", "Shortage", "Min stock", "Safety stock", "Reorder point",
            "Max stock", "Total value", "Warehouses", "Locations", "Batches", "Balance at",
            "Signal", "Signal reasons", "Warehouse scope mode");

    private final InventoryAvailabilityQueryRepository repository;
    private final com.enterprise.mm.service.RoleDataScopeService roleDataScopeService;

    public InventoryAvailabilityWorkspaceResponse workspace(InventoryAvailabilityFilter filter) {
        InventoryAvailabilityFilter normalized = normalize(filter);
        return new InventoryAvailabilityWorkspaceResponse(
            overview(normalized), queues(normalized));
    }

    public InventoryAvailabilityOverview overview(InventoryAvailabilityFilter filter) {
        return repository.overview(normalize(filter));
    }

    public List<InventoryAvailabilityQueueDefinition> queues(InventoryAvailabilityFilter filter) {
        InventoryAvailabilityFilter base = normalize(filter);
        return Arrays.stream(InventoryAvailabilityQueueKey.values())
            .map(key -> queueDefinition(withQueue(base, key)))
            .toList();
    }

    public InventoryAvailabilityQueueDefinition queueDefinition(InventoryAvailabilityFilter filter) {
        InventoryAvailabilityFilter applied = normalize(filter);
        if (applied.queue() == null) {
            throw new BadRequestException("Inventory availability queue is required");
        }
        return new InventoryAvailabilityQueueDefinition(
            applied.queue(),
            "inventoryAvailability.queue." + applied.queue().name(),
            repository.count(applied),
            applied);
    }

    public Page<InventoryAvailabilityRow> positions(InventoryAvailabilityFilter filter, Pageable pageable) {
        return repository.search(normalize(filter), pageable);
    }

    /**
     * TODO item 5: exports every row matching the currently applied cockpit filter
     * (queue, search, warehouse, material group, and the five boolean/urgency
     * filters) to an .xlsx file, not just the first page. Pages through the
     * repository internally so a 3,000-row filtered result exports completely.
     */
    public byte[] exportExcel(InventoryAvailabilityFilter filter) {
        InventoryAvailabilityFilter normalized = normalize(filter);
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XSSFSheet sheet = workbook.createSheet("Inventory availability");
            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_COLUMNS.size(); i++) {
                header.createCell(i).setCellValue(EXPORT_COLUMNS.get(i));
            }

            int rowIndex = 1;
            Pageable pageable = PageRequest.of(0, EXPORT_PAGE_SIZE, Sort.by(Sort.Order.asc("materialCode")));
            Page<InventoryAvailabilityRow> current;
            do {
                current = repository.search(normalized, pageable);
                for (InventoryAvailabilityRow row : current.getContent()) {
                    writeRow(sheet.createRow(rowIndex++), row);
                }
                pageable = pageable.next();
            } while (current.hasNext());

            for (int i = 0; i < EXPORT_COLUMNS.size(); i++) {
                sheet.autoSizeColumn(i);
            }
            workbook.write(out);
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot build inventory availability export", ex);
        }
    }

    private void writeRow(Row row, InventoryAvailabilityRow data) {
        int c = 0;
        row.createCell(c++).setCellValue(text(data.materialCode()));
        row.createCell(c++).setCellValue(text(data.materialName()));
        row.createCell(c++).setCellValue(text(data.materialStatus()));
        row.createCell(c++).setCellValue(text(data.materialGroupName()));
        row.createCell(c++).setCellValue(text(data.replacementMaterialCode()));
        row.createCell(c++).setCellValue(number(data.onHand()));
        row.createCell(c++).setCellValue(number(data.available()));
        row.createCell(c++).setCellValue(number(data.reserved()));
        row.createCell(c++).setCellValue(number(data.quarantine()));
        row.createCell(c++).setCellValue(number(data.openDemand()));
        row.createCell(c++).setCellValue(number(data.openSupply()));
        row.createCell(c++).setCellValue(text(data.nearestDemandDate() == null ? null : data.nearestDemandDate().toString()));
        row.createCell(c++).setCellValue(text(data.nearestSupplyDate() == null ? null : data.nearestSupplyDate().toString()));
        row.createCell(c++).setCellValue(number(data.projectedAvailable()));
        row.createCell(c++).setCellValue(number(data.shortage()));
        row.createCell(c++).setCellValue(number(data.minStock()));
        row.createCell(c++).setCellValue(number(data.safetyStock()));
        row.createCell(c++).setCellValue(number(data.reorderPoint()));
        row.createCell(c++).setCellValue(number(data.maxStock()));
        row.createCell(c++).setCellValue(number(data.totalValue()));
        row.createCell(c++).setCellValue(data.warehouseCount());
        row.createCell(c++).setCellValue(data.locationCount());
        row.createCell(c++).setCellValue(data.batchCount());
        row.createCell(c++).setCellValue(text(data.balanceAt() == null ? null : data.balanceAt().toString()));
        row.createCell(c++).setCellValue(text(data.signal() == null ? null : data.signal().name()));
        row.createCell(c++).setCellValue(text(data.signalReasons() == null ? null : String.join(", ", data.signalReasons())));
        row.createCell(c).setCellValue(text(data.warehouseScopeMode() == null ? null : data.warehouseScopeMode().name()));
    }

    private String text(String value) {
        return value == null ? "" : value;
    }

    private double number(java.math.BigDecimal value) {
        return value == null ? 0d : value.doubleValue();
    }

    private InventoryAvailabilityFilter normalize(InventoryAvailabilityFilter filter) {
        InventoryAvailabilityFilter value = filter == null
            ? new InventoryAvailabilityFilter(
                null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null)
            : filter.normalized();
        var warehouseIds = roleDataScopeService.warehouseReadScope(value.warehouseId(), value.warehouseIds());
        var warehouseId = value.warehouseId() != null ? value.warehouseId()
            : warehouseIds.size() == 1 ? warehouseIds.iterator().next() : null;
        return new InventoryAvailabilityFilter(
            value.queue(),
            value.search(),
            warehouseId,
            value.materialGroupId(),
            value.replacementMissing(),
            value.staleBalance(),
            value.nonMoving(),
            value.negativeProjected(),
            value.urgency(),
            value.materialCode(),
            value.materialName(),
            value.materialId(),
            new java.util.ArrayList<>(warehouseIds),
            value.asOfDate(),
            value.dateFrom(),
            value.dateTo()
        );
    }

    private InventoryAvailabilityFilter withQueue(
        InventoryAvailabilityFilter filter,
        InventoryAvailabilityQueueKey queue
    ) {
        return new InventoryAvailabilityFilter(
            queue,
            filter.search(),
            filter.warehouseId(),
            filter.materialGroupId(),
            filter.replacementMissing(),
            filter.staleBalance(),
            filter.nonMoving(),
            filter.negativeProjected(),
            filter.urgency(),
            filter.materialCode(),
            filter.materialName(),
            filter.materialId(),
            filter.warehouseIds(),
            filter.asOfDate(),
            filter.dateFrom(),
            filter.dateTo()
        );
    }
}
