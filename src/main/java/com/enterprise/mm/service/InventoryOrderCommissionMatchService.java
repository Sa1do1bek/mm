package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.Commission;
import com.enterprise.mm.entity.CommissionMember;
import com.enterprise.mm.enums.CommissionType;
import com.enterprise.mm.repository.CommissionMemberRepository;
import com.enterprise.mm.repository.CommissionRepository;
import com.enterprise.mm.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryOrderCommissionMatchService {
    private final CommissionRepository commissions;
    private final CommissionMemberRepository members;
    private final UserRepository users;

    public record Match(UUID commissionId, String commissionName, int percentage, boolean active,
        int matchedMembers, int documentedMembers) {}
    public record MemberDraft(String fullName, UUID userId, String role, boolean resolved) {}
    public record Recommendation(String sourceKey, String name, String rosterCompleteness,
        List<Match> matches, List<MemberDraft> createMembers) {}

    @Transactional(readOnly = true)
    public List<Recommendation> match(JsonNode analysis) {
        var erp = commissions.findByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(c -> c.getType() == CommissionType.INVENTORY).toList();
        Map<UUID, List<String>> erpRosters = new HashMap<>();
        for (Commission commission : erp) erpRosters.put(commission.getId(),
            members.findByTenantIdAndCommissionIdAndIsDeletedFalseOrderByCreatedAtAsc(TenantContext.currentTenant(), commission.getId())
                .stream().filter(CommissionMember::isActive).map(member -> normalize(member.getMemberName())).toList());
        Map<String, List<com.enterprise.mm.entity.User>> userMatches = new HashMap<>();
        Map<String, List<com.enterprise.mm.entity.User>> normalizedUsers = new HashMap<>();
        users.findAllByTenantIdAndCompanyIdAndIsDeletedFalse(TenantContext.currentTenant(), CompanyContext.currentCompany())
            .stream().filter(com.enterprise.mm.entity.User::isEnabled).forEach(user ->
                normalizedUsers.computeIfAbsent(normalize(user.getFullName()), ignored -> new ArrayList<>()).add(user));
        var result = new ArrayList<Recommendation>();
        JsonNode sources = analysis.path("orderAnalysis").path("schemaVersion").asInt() == 2
            ? analysis.path("orderAnalysis").path("commissionRosters")
            : analysis.path("commissionAnalysis").path("commissions");
        for (JsonNode source : sources) {
            if (!"COMPLETE".equals(source.path("rosterCompleteness").asText())
                || source.path("members").size() < 2
                || !List.of("CENTRAL", "LOCAL", "SPECIALIZED", "REGIONAL").contains(source.path("kind").asText())
                || (analysis.path("orderAnalysis").path("schemaVersion").asInt() == 2
                    && !source.path("createEligible").asBoolean(false))) continue;
            String sourceName = source.path("name").asText("");
            List<String> sourceMembers = new ArrayList<>();
            List<MemberDraft> createMembers = new ArrayList<>();
            for (JsonNode person : source.path("members")) {
                String name = person.path("fullName").asText("");
                sourceMembers.add(normalize(name));
                var candidates = userMatches.computeIfAbsent(normalize(name), ignored -> {
                    var exact = users.findAllByTenantIdAndFullNameIgnoreCaseAndIsDeletedFalse(TenantContext.currentTenant(), name)
                        .stream().filter(user -> CompanyContext.currentCompany().equals(user.getCompanyId()) && user.isEnabled()).toList();
                    return exact.isEmpty() && !normalize(name).isEmpty()
                        ? normalizedUsers.getOrDefault(normalize(name), List.of()) : exact;
                });
                String role = person.path("erpRoleSuggestion").asText("MEMBER");
                createMembers.add(new MemberDraft(name, candidates.size() == 1 ? candidates.get(0).getId() : null,
                    role.isBlank() || "null".equals(role) ? "MEMBER" : role, candidates.size() == 1));
            }
            List<Match> scores = erp.stream().map(commission -> score(commission, sourceName, sourceMembers, erpRosters.get(commission.getId())))
                .filter(match -> match.percentage() > 0)
                .sorted(Comparator.comparingInt(Match::percentage).reversed()).limit(5).toList();
            result.add(new Recommendation(source.path("sourceKey").asText(), sourceName,
                source.path("rosterCompleteness").asText("UNKNOWN"), scores, createMembers));
        }
        return result;
    }

    private Match score(Commission commission, String name, List<String> documented, List<String> actual) {
        int overlap = (int) documented.stream().filter(actual::contains).distinct().count();
        int memberScore = documented.isEmpty() ? 0 : Math.round(100f * overlap / Math.max(documented.size(), actual.size()));
        int nameScore = normalize(name).equals(normalize(commission.getName())) ? 100 : 0;
        int percentage = documented.isEmpty() ? Math.round(.25f * nameScore) : Math.round(.25f * nameScore + .75f * memberScore);
        LocalDate today = LocalDate.now();
        boolean active = commission.isActive() && (commission.getValidFrom() == null || !commission.getValidFrom().isAfter(today))
            && (commission.getValidTo() == null || !commission.getValidTo().isBefore(today));
        return new Match(commission.getId(), commission.getName(), percentage, active, overlap, documented.size());
    }
    private String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", "");
    }
}
