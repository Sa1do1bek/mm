package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.repository.DepartmentWarehouseAccessRepository;
import com.enterprise.mm.repository.WarehouseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryOrderObjectMatchService {
    private final DepartmentRepository departments;
    private final WarehouseRepository warehouses;
    private final DepartmentWarehouseAccessRepository departmentAccess;
    private final RoleDataScopeService authority;

    public record Candidate(UUID id, String code, String name, String kind, int percentage) {}
    public record ObjectMatch(String kind, String documentName, List<Candidate> candidates) {}
    public record BandMatch(String sourceKey, List<ObjectMatch> objects) {}

    @Transactional(readOnly = true)
    public List<BandMatch> match(JsonNode analysis) {
        var departmentOptions = departments.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(department -> {
                var mapped = departmentAccess.findActiveWarehouseIds(TenantContext.currentTenant(), CompanyContext.currentCompany(), department.getId());
                return "ACTIVE".equals(department.getStatus()) && !mapped.isEmpty() && mapped.stream().allMatch(authority::canAccessWarehouse);
            })
            .map(department -> new Candidate(department.getId(), department.getCode(), department.getName(), "DEPARTMENT", 0)).toList();
        var warehouseOptions = warehouses.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(warehouse -> "ACTIVE".equals(warehouse.getStatus()) && authority.canAccessWarehouse(warehouse.getId()))
            .map(warehouse -> new Candidate(warehouse.getId(), warehouse.getCode(), warehouse.getName(), "WAREHOUSE", 0)).toList();
        var bands = new ArrayList<BandMatch>();
        for (JsonNode band : analysis.path("orderAnalysis").path("workItems")) {
            var objects = new ArrayList<ObjectMatch>();
            for (JsonNode mention : band.path("objectMentions")) {
                String kind = mention.path("kind").asText();
                String name = mention.path("name").asText();
                List<Candidate> options = switch (kind) {
                    case "DEPARTMENT", "ORG_UNIT" -> departmentOptions;
                    case "WAREHOUSE" -> warehouseOptions;
                    default -> List.of();
                };
                var candidates = options.stream().map(option -> new Candidate(option.id(), option.code(), option.name(), option.kind(),
                    score(name, option))).filter(candidate -> candidate.percentage() >= 40)
                    .sorted(Comparator.comparingInt(Candidate::percentage).reversed()).limit(5).toList();
                objects.add(new ObjectMatch(kind, name, candidates));
            }
            bands.add(new BandMatch(band.path("sourceKey").asText(), objects));
        }
        return bands;
    }

    private int score(String name, Candidate candidate) {
        String normalized = normalize(name);
        String code = normalize(candidate.code());
        String label = normalize(candidate.name());
        if (normalized.isEmpty()) return 0;
        if (normalized.equals(code) || normalized.equals(label)) return 100;
        if (!code.isEmpty() && normalized.contains(code)) return 85;
        if (!label.isEmpty() && (normalized.contains(label) || label.contains(normalized))) return 70;
        return 0;
    }
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
