package com.enterprise.mm.warehouse.reports;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.enterprise.mm.warehouse.reports.WarehouseReport.*;

@Service
public class WarehouseOverviewService {
    private final WarehouseReportService reports;
    private final WarehouseReportQuery operational;
    private final WarehouseAccountReportQuery accounts;
    private final WarehouseReportApprovalStore approvals;

    public WarehouseOverviewService(WarehouseReportService reports, WarehouseReportQuery operational, WarehouseAccountReportQuery accounts, WarehouseReportApprovalStore approvals) {
        this.reports = reports;
        this.operational = operational;
        this.accounts = accounts;
        this.approvals = approvals;
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public WarehouseOverview overview(LocalDate from, LocalDate to, UUID warehouseId) {
        var context=reports.overviewContext(filter(from,to,warehouseId));
        List<WarehouseOverview.WarehouseUnitTotal> totals=context.accountReporting()
            ? accounts.warehouseTotals(context.filter()) : operational.warehouseTotals(context.filter());
        var grouped=new LinkedHashMap<UUID,LineBuilder>();
        for (var total:totals) grouped.computeIfAbsent(total.warehouseId(),id ->
            new LineBuilder(total.warehouseId(),total.warehouseCode(),total.warehouseName())).add(total);
        var approvalMap=approvals.latest(from,to,grouped.keySet());
        List<WarehouseOverview.WarehouseLine> lines=grouped.values().stream()
            .map(line -> line.build(approvalMap.get(line.warehouseId))).toList();
        return new WarehouseOverview(from,to,context.currencyCode(),context.warnings(),lines);
    }

    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public WarehouseReport liveDetail(LocalDate from, LocalDate to, UUID warehouseId) {
        return reports.generate(filter(from,to,warehouseId),true);
    }

    private static Filter filter(LocalDate from, LocalDate to, UUID warehouseId) {
        return new Filter(Type.MATERIAL_STATEMENT,from,to,warehouseId,null,null,null,0,1);
    }

    private static final class LineBuilder {
        private final UUID warehouseId;
        private final String code,name;
        private final List<WarehouseOverview.UnitTotal> quantities=new ArrayList<>();
        private BigDecimal opening=BigDecimal.ZERO,receipt=BigDecimal.ZERO,
            issue=BigDecimal.ZERO,closing=BigDecimal.ZERO;

        private LineBuilder(UUID warehouseId,String code,String name) {
            this.warehouseId=warehouseId;this.code=code;this.name=name;
        }
        private void add(WarehouseOverview.WarehouseUnitTotal value) {
            quantities.add(new WarehouseOverview.UnitTotal(value.uom(),value.unitOfMeasureShortName(),
                value.unitOfMeasureShortNameRu(),value.unitOfMeasureShortNameUz(),value.openingQuantity(),
                value.receiptQuantity(),value.issueQuantity(),value.closingQuantity()));
            opening=opening.add(new BigDecimal(value.openingAmount()));
            receipt=receipt.add(new BigDecimal(value.receiptAmount()));
            issue=issue.add(new BigDecimal(value.issueAmount()));
            closing=closing.add(new BigDecimal(value.closingAmount()));
        }
        private WarehouseOverview.WarehouseLine build(WarehouseOverview.Approval approval) {
            return new WarehouseOverview.WarehouseLine(warehouseId,code,name,opening.toPlainString(),
                receipt.toPlainString(),issue.toPlainString(),closing.toPlainString(),
                List.copyOf(quantities),approval);
        }
    }
}
