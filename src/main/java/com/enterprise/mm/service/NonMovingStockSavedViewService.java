package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.NonMovingStockSavedView;
import com.enterprise.mm.error.NotFoundException;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewFilter;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewRequest;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.SavedViewResponse;
import com.enterprise.mm.payload.NonMovingStockWorkspaceDtos.WorkspaceMode;
import com.enterprise.mm.repository.NonMovingStockSavedViewRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NonMovingStockSavedViewService {
    private static final String DELIMITER = ",";
    private final NonMovingStockSavedViewRepository repository;



    @Transactional(readOnly = true)
    public List<SavedViewResponse> list(WorkspaceMode mode) {
        List<NonMovingStockSavedView> views = mode == null
            ? repository.findByTenantIdAndCompanyIdAndOwnerUsernameAndIsDeletedFalseOrderByNameAsc(
                tenant(), company(), username())
            : repository.findByTenantIdAndCompanyIdAndOwnerUsernameAndWorkspaceModeAndIsDeletedFalseOrderByNameAsc(
                tenant(), company(), username(), mode.name());
        return views.stream().map(this::response).toList();
    }

    @Transactional
    public SavedViewResponse save(SavedViewRequest request) {
        SavedViewFilter filters = request.filters() == null ? SavedViewFilter.empty() : request.filters().normalized();
        String name = request.name().trim();
        List<NonMovingStockSavedView> existing = repository
            .findByTenantIdAndCompanyIdAndOwnerUsernameAndWorkspaceModeAndIsDeletedFalseOrderByNameAsc(
                tenant(), company(), username(), request.mode().name());
        NonMovingStockSavedView view = existing.stream()
            .filter(item -> item.getName().equalsIgnoreCase(name))
            .findFirst()
            .orElseGet(NonMovingStockSavedView::new);
        if (request.defaultView()) {
            existing.stream().filter(item -> !item.equals(view) && item.isDefaultView()).forEach(item -> item.setDefaultView(false));
            repository.saveAll(existing);
        }
        view.setTenantId(tenant());
        view.setCompanyId(company());
        view.setOwnerUsername(username());
        view.setWorkspaceMode(request.mode().name());
        view.setCode(view.getCode() == null ? "NMS-VIEW-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT) : view.getCode());
        view.setName(name);
        view.setStatus("ACTIVE");
        view.setSearchText(filters.search());
        view.setCaseStatuses(join(filters.statuses()));
        view.setWarehouseId(filters.warehouseId());
        view.setResponsibleUserId(filters.responsibleUserId());
        view.setDecisionTypes(join(filters.decisionTypes()));
        view.setOverdueOnly(Boolean.TRUE.equals(filters.overdueOnly()));
        view.setDateFrom(filters.dateFrom());
        view.setDateTo(filters.dateTo());
        view.setMaterialGroupId(filters.materialGroupId());
        view.setMinBookValue(filters.minBookValue());
        view.setMaxBookValue(filters.maxBookValue());
        view.setExecutionStatuses(join(filters.executionStatuses()));
        view.setBlockerTypes(join(filters.blockerTypes()));
        view.setIntegrationStatuses(join(filters.integrationStatuses()));
        view.setExclusionResults(join(filters.exclusionResults()));
        view.setActiveCaseOnly(Boolean.TRUE.equals(filters.activeCaseOnly()));
        view.setDefaultView(request.defaultView());
        return response(repository.save(view));
    }

    @Transactional
    public void delete(UUID viewId) {
        NonMovingStockSavedView view = repository
            .findByIdAndTenantIdAndCompanyIdAndOwnerUsernameAndIsDeletedFalse(
                viewId, tenant(), company(), username())
            .orElseThrow(() -> new NotFoundException("Saved view not found: " + viewId));
        view.softDelete();
        repository.save(view);
    }

    private SavedViewResponse response(NonMovingStockSavedView view) {
        return new SavedViewResponse(
            view.getId(), view.getName(), WorkspaceMode.valueOf(view.getWorkspaceMode()),
            new SavedViewFilter(
                view.getSearchText(), split(view.getCaseStatuses()), view.getWarehouseId(),
                view.getResponsibleUserId(), split(view.getDecisionTypes()), view.isOverdueOnly(),
                view.getDateFrom(), view.getDateTo(), view.getMaterialGroupId(), view.getMinBookValue(),
                view.getMaxBookValue(), split(view.getExecutionStatuses()), split(view.getBlockerTypes()),
                split(view.getIntegrationStatuses()), split(view.getExclusionResults()), view.isActiveCaseOnly()),
            view.isDefaultView(), view.getVersion() == null ? 0L : view.getVersion()
        );
    }

    private String join(List<String> values) {
        return values == null || values.isEmpty() ? null : String.join(DELIMITER, values);
    }

    private List<String> split(String values) {
        return values == null || values.isBlank() ? List.of() : Arrays.stream(values.split(DELIMITER)).toList();
    }

    private String tenant() {
        return TenantContext.currentTenant();
    }

    private UUID company() {
        return CompanyContext.currentCompany();
    }

    private String username() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getName() == null || authentication.getName().isBlank()) {
            throw new IllegalStateException("Authenticated user is required for saved views");
        }
        return authentication.getName();
    }
}
