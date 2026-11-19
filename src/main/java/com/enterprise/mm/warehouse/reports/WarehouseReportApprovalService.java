package com.enterprise.mm.warehouse.reports;

import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.service.RoleDataScopeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseReportApprovalService {
    private final WarehouseOverviewService overview;
    private final WarehouseReportApprovalStore store;
    private final WarehouseReportQuery reportQuery;
    private final NamedParameterJdbcTemplate jdbc;
    private final RoleDataScopeService access;
    private final ObjectMapper mapper;

    public WarehouseReportApprovalService(WarehouseOverviewService overview, WarehouseReportApprovalStore store, WarehouseReportQuery reportQuery, NamedParameterJdbcTemplate jdbc, RoleDataScopeService access, ObjectMapper mapper) {
        this.overview = overview;
        this.store = store;
        this.reportQuery = reportQuery;
        this.jdbc = jdbc;
        this.access = access;
        this.mapper = mapper;
    }

    public record ApprovedReport(WarehouseOverview.Approval approval, WarehouseReport report) {}
    public record BatchResult(int warehouseCount, List<WarehouseOverview.Approval> approvals) {}

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public ApprovedReport approve(UUID warehouseId, LocalDate from, LocalDate to) {
        access.availabilityWarehouseId(warehouseId);
        return approveOne(warehouseId,from,to);
    }

    @Transactional(isolation=Isolation.REPEATABLE_READ)
    public BatchResult approveAll(LocalDate from, LocalDate to) {
        var warehouses=overview.overview(from,to,null).warehouses();
        List<WarehouseOverview.Approval> saved=warehouses.stream()
            .map(line -> approveOne(line.warehouseId(),from,to).approval()).toList();
        return new BatchResult(saved.size(),saved);
    }

    @Transactional(readOnly=true)
    public ApprovedReport get(UUID approvalId) {
        var saved=store.byId(approvalId);
        if(saved==null) throw new BusinessException("Approved warehouse report not found","WAREHOUSE_REPORT_APPROVAL_NOT_FOUND");
        access.availabilityWarehouseId(saved.warehouseId());
        return new ApprovedReport(saved.summary(),saved.report());
    }

    @Transactional(readOnly=true)
    public List<WarehouseOverview.Approval> history(UUID warehouseId, LocalDate from, LocalDate to) {
        access.availabilityWarehouseId(warehouseId);
        return store.history(warehouseId,from,to);
    }

    private ApprovedReport approveOne(UUID warehouseId, LocalDate from, LocalDate to) {
        if (warehouseId==null) throw new BusinessException("Warehouse is required","WAREHOUSE_REPORT_WAREHOUSE_REQUIRED");
        String key=reportQuery.scope().getValue("tenant")+":"+reportQuery.scope().getValue("company")+":"+warehouseId+":"+from+":"+to;
        jdbc.query("select pg_advisory_xact_lock(hashtextextended(:key,0))",
            reportQuery.scope().addValue("key",key),(RowCallbackHandler) rs -> {});
        WarehouseReport report=overview.liveDetail(from,to,warehouseId);
        if (report.content().isEmpty())
            throw new BusinessException("No report data for this warehouse and period","WAREHOUSE_REPORT_APPROVAL_EMPTY");
        String hash=hash(report);
        var previous=store.latest(warehouseId,from,to);
        if(previous!=null && previous.hash().equals(hash))
            return new ApprovedReport(previous.summary(),previous.report());
        String approver=SecurityContextHolder.getContext().getAuthentication().getName();
        var saved=store.save(warehouseId,from,to,previous==null?1:previous.revision()+1,approver,hash,report);
        return new ApprovedReport(saved.summary(),saved.report());
    }

    private String hash(WarehouseReport report) {
        try {
            byte[] canonical=mapper.writeValueAsBytes(new Object[]{report.from(),report.to(),report.currencyCode(),
                report.warehouseId(),report.accountSnapshotDate(),report.content(),report.totals()});
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not fingerprint warehouse report",e);
        }
    }
}
