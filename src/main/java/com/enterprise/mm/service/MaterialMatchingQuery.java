package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.error.BadRequestException;
import com.enterprise.mm.payload.PageResponse;
import com.enterprise.mm.entity.AiAnalysisJob;
import com.enterprise.mm.enums.AiAnalysisJobStatus;
import com.enterprise.mm.enums.AiAnalysisType;
import com.enterprise.mm.repository.AiAnalysisJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Paged material status query; 1C candidates are grouped once per company snapshot. */
@Repository
@RequiredArgsConstructor
public class MaterialMatchingQuery {
    private final NamedParameterJdbcTemplate jdbc;
    private final AiAnalysisJobRepository aiJobs;
    private final ObjectMapper objectMapper;

    public record Row(UUID id, String code, String name, String materialType, String groupName,
                      String mxikCode, String mxikName, long version, boolean accountApplicable,
                      String accountStatus, MaterialStockAccountResolutionService.Account configuredAccount,
                      MaterialStockAccountResolutionService.Account effectiveAccount,
                      String effectiveSource, int candidateCount,
                      String suggestedMxikCode, UUID suggestionJobId,
                      String suggestionConfidence, String suggestionReason) {}

    private static final Set<String> FILTERS = Set.of("ALL", "MISSING_MXIK", "MISSING_STOCK_ACCOUNT", "BOTH_SET");
    private static final Set<String> MXIK_STATES = Set.of("SET", "MISSING");
    private static final Set<String> ACCOUNT_STATES = Set.of(
        "AUTO_1C", "CHOICE_REQUIRED", "CONFIGURED_FALLBACK", "ACCOUNT_REQUIRED", "NOT_APPLICABLE");
    private static final String BASE = """
        with latest as (
          select max(snapshot_date) snapshot_date from warehouse_account_imports
          where tenant_id=:tenant and company_id=:company and status='COMPLETED'
        ), candidates as (
          select distinct coalesce(e.material_id,lm.master_id) material_id, c.id account_id,
                 trim(c.account_code) account_code, c.name account_name
          from warehouse_account_entries e
          join warehouse_account_imports r on r.id=e.import_id and r.tenant_id=e.tenant_id
            and r.company_id=e.company_id and r.status='COMPLETED'
          join latest on latest.snapshot_date=r.snapshot_date
          left join warehouse_source_master_links lm on lm.tenant_id=e.tenant_id
            and lm.company_id=e.company_id and lm.reference_kind='MATERIAL'
            and lm.reference_type=e.material_reference_type and lm.source_id=e.material_source_id
          join chart_of_accounts c on c.tenant_id=e.tenant_id and c.company_id=e.company_id
            and c.account_code=e.account_code and c.is_delete=false and c.status='ACTIVE'
            and c.is_group=false and trim(c.account_code)<>'' and trim(c.account_code) not like '002%'
          where e.tenant_id=:tenant and e.company_id=:company and e.kind='BALANCE'
            and coalesce(e.material_id,lm.master_id) is not null
        ), candidate_summary as (
          select material_id, count(*) candidate_count, min(account_id::text) account_id,
                 min(account_code) account_code, min(account_name) account_name
          from candidates group by material_id
        ), material_status as (
          select m.id, m.code, m.name, m.material_type, m.version,
                 g.name group_name, m.ikpu_code mxik_code, x.name mxik_name,
                 cfg.id configured_id, cfg.account_code configured_code, cfg.name configured_name,
                 coalesce(cs.candidate_count,0) candidate_count,
                 case when m.material_type<>'TMB' or m.status<>'ACTIVE' then 'NOT_APPLICABLE'
                      when coalesce(cs.candidate_count,0)>1 then 'CHOICE_REQUIRED'
                      when coalesce(cs.candidate_count,0)=1 then 'AUTO_1C'
                      when cfg.id is not null and cfg.status='ACTIVE' and cfg.is_group=false
                        and trim(cfg.account_code)<>'' and trim(cfg.account_code) not like '002%'
                        then 'CONFIGURED_FALLBACK'
                      else 'ACCOUNT_REQUIRED' end account_status,
                 case when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=1 then cs.account_id::uuid
                      when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=0 and cfg.status='ACTIVE'
                        and cfg.is_group=false and trim(cfg.account_code)<>''
                        and trim(cfg.account_code) not like '002%' then cfg.id end effective_id,
                 case when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=1 then cs.account_code
                      when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=0 and cfg.status='ACTIVE'
                        and cfg.is_group=false and trim(cfg.account_code)<>''
                        and trim(cfg.account_code) not like '002%' then cfg.account_code end effective_code,
                 case when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=1 then cs.account_name
                      when m.material_type='TMB' and m.status='ACTIVE'
                        and coalesce(cs.candidate_count,0)=0 and cfg.status='ACTIVE'
                        and cfg.is_group=false and trim(cfg.account_code)<>''
                        and trim(cfg.account_code) not like '002%' then cfg.name end effective_name
          from materials m
          left join candidate_summary cs on cs.material_id=m.id
          left join chart_of_accounts cfg on cfg.id=m.manual_receipt_stock_account_id
            and cfg.tenant_id=m.tenant_id and cfg.company_id=m.company_id
            and cfg.is_delete=false
          left join material_groups g on g.id=m.material_group_id and g.tenant_id=m.tenant_id
            and g.company_id=m.company_id and g.is_delete=false
          left join mxik x on x.kod=m.ikpu_code
          where m.tenant_id=:tenant and m.company_id=:company and m.is_delete=false
            and (cast(:search as text) is null or lower(m.name) like :search or lower(m.code) like :search)
        )
        """;
    private static final String FILTER = """
        from material_status s
        where (:filter='ALL'
          or (:filter='MISSING_MXIK' and (s.mxik_code is null or trim(s.mxik_code)=''))
          or (:filter='MISSING_STOCK_ACCOUNT' and s.account_status in ('CHOICE_REQUIRED','ACCOUNT_REQUIRED'))
          or (:filter='BOTH_SET' and s.mxik_code is not null and trim(s.mxik_code)<>''
              and s.account_status in ('AUTO_1C','CONFIGURED_FALLBACK')))
          and (cast(:mxikState as text) is null or (:mxikState='SET' and s.mxik_code is not null and trim(s.mxik_code)<>'')
            or (:mxikState='MISSING' and (s.mxik_code is null or trim(s.mxik_code)='')))
          and (cast(:accountState as text) is null or s.account_status=:accountState)
        """;

    @Transactional(readOnly = true)
    public PageResponse<Row> list(String filter, String mxikStatus, String accountStatus,
                                  String search, int page, int size) {
        String selectedFilter = filter == null ? "ALL" : filter.trim().toUpperCase(java.util.Locale.ROOT);
        if (!FILTERS.contains(selectedFilter)) throw new BadRequestException("Unsupported material matching filter");
        String mxikState = mxikStatus == null || mxikStatus.isBlank() ? null
            : mxikStatus.trim().toUpperCase(java.util.Locale.ROOT);
        String accountState = accountStatus == null || accountStatus.isBlank() ? null
            : accountStatus.trim().toUpperCase(java.util.Locale.ROOT);
        if (mxikState != null && !MXIK_STATES.contains(mxikState))
            throw new BadRequestException("Unsupported MXIK status filter");
        if (accountState != null && !ACCOUNT_STATES.contains(accountState))
            throw new BadRequestException("Unsupported stock account status filter");
        if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid page or size");
        String term = search == null || search.isBlank() ? null : "%" + search.trim().toLowerCase(java.util.Locale.ROOT)
            .replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        var parameters = new MapSqlParameterSource("tenant", TenantContext.currentTenant())
            .addValue("company", CompanyContext.currentCompany()).addValue("search", term)
            .addValue("filter", selectedFilter).addValue("mxikState", mxikState)
            .addValue("accountState", accountState).addValue("limit", size).addValue("offset", (long) page * size);
        long total = jdbc.queryForObject(BASE + " select count(*) " + FILTER, parameters, Long.class);
        List<Row> rows = jdbc.query(BASE + " select s.* " + FILTER + " order by lower(s.name),s.id limit :limit offset :offset",
            parameters, (result, index) -> {
                UUID configuredId = result.getObject("configured_id", UUID.class);
                UUID effectiveId = result.getObject("effective_id", UUID.class);
                var configured = configuredId == null ? null : new MaterialStockAccountResolutionService.Account(
                    configuredId, result.getString("configured_code"), result.getString("configured_name"));
                var effective = effectiveId == null ? null : new MaterialStockAccountResolutionService.Account(
                    effectiveId, result.getString("effective_code"), result.getString("effective_name"));
                String status = result.getString("account_status");
                return new Row(result.getObject("id", UUID.class), result.getString("code"),
                    result.getString("name"), result.getString("material_type"), result.getString("group_name"),
                    result.getString("mxik_code"), result.getString("mxik_name"), result.getLong("version"),
                    !"NOT_APPLICABLE".equals(status), status, configured, effective,
                    "AUTO_1C".equals(status) ? "ONE_C_BALANCE" : "CONFIGURED_FALLBACK".equals(status) ? "MATERIAL" : null,
                    result.getInt("candidate_count"), null, null, null, null);
            });
        if (!rows.isEmpty()) {
            Map<UUID, AiAnalysisJob> suggestions = aiJobs.findForMaterials(
                TenantContext.currentTenant(), CompanyContext.currentCompany(),
                AiAnalysisType.MATERIAL_MXIK_MATCH, rows.stream().map(Row::id).toList()).stream()
                .collect(Collectors.toMap(AiAnalysisJob::getEntityId, Function.identity(),
                    (first, second) -> first.getRequestedAt().isAfter(second.getRequestedAt()) ? first : second));
            rows = rows.stream().map(row -> withSuggestion(row, suggestions.get(row.id()))).toList();
        }
        return new PageResponse<>(rows, page, size, total, (int) Math.ceil((double) total / size));
    }

    private Row withSuggestion(Row row, AiAnalysisJob job) {
        String code = null;
        UUID jobId = null;
        String confidence = null;
        String reason = null;
        if (job != null && job.getStatus() == AiAnalysisJobStatus.COMPLETED && job.getResultPayload() != null) {
            try {
                var result = objectMapper.readTree(job.getResultPayload());
                if ("RECOMMENDED".equals(result.path("status").asText())
                    && row.id().toString().equals(result.path("materialId").asText())
                    && row.version() == result.path("materialVersion").asLong(-1L)) {
                    String candidate = result.path("recommendedMxik").path("code").asText("");
                    if (!candidate.isBlank()) {
                        code = candidate;
                        jobId = job.getId();
                        confidence = result.path("confidence").asText(null);
                        reason = result.path("reason").asText(null);
                    }
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException ignored) {
                // Invalid AI output is not offered for approval.
            }
        }
        return new Row(row.id(), row.code(), row.name(), row.materialType(), row.groupName(),
            row.mxikCode(), row.mxikName(), row.version(), row.accountApplicable(), row.accountStatus(),
            row.configuredAccount(), row.effectiveAccount(), row.effectiveSource(), row.candidateCount(),
            code, jobId, confidence, reason);
    }
}
