package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.LocalizedText;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.DemandPlan;
import com.enterprise.mm.entity.Department;
import com.enterprise.mm.error.BusinessException;
import com.enterprise.mm.repository.DepartmentRepository;
import com.enterprise.mm.util.BusinessNameGenerator;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DemandPlanNamingService {
    private final DepartmentRepository departmentRepository;

    public void applyCanonicalName(DemandPlan plan) {
        if (plan == null) {
            return;
        }
        Integer planYear = plan.getPlanYear();
        if (planYear == null) {
            throw new BusinessException("Demand plan year is required for generated name");
        }
        Department department = requireDepartment(plan.getDepartment());
        plan.setDepartment(department);
        plan.setName(canonicalWorkflowName(department.getName(), planYear));
    }

    public String canonicalWorkflowName(String departmentName, Integer planYear) {
        if (planYear == null) {
            throw new BusinessException("Demand plan year is required for generated name");
        }
        if (departmentName == null || departmentName.isBlank()) {
            throw new BusinessException("Department name is required for generated demand plan name");
        }
        String parentBase = canonicalParentBase(departmentName.trim(), planYear);
        return BusinessNameGenerator.workflowDocumentName("DemandPlan", parentBase);
    }

    private Department requireDepartment(Department candidate) {
        UUID departmentId = candidate == null ? null : candidate.getId();
        if (departmentId == null) {
            throw new BusinessException("Demand plan department is required for generated name");
        }
        UUID companyId = candidate.getCompanyId() == null ? CompanyContext.currentCompany() : candidate.getCompanyId();
        return departmentRepository
            .findByIdAndTenantIdAndCompanyIdAndIsDeletedFalse(
                departmentId,
                TenantContext.currentTenant(),
                companyId
            )
            .orElseThrow(() -> new BusinessException("Demand plan department was not found for generated name"));
    }

    private String canonicalParentBase(String departmentName, Integer planYear) {
        if (!LocalizedText.isMultilingual(departmentName)) {
            return baseFor(departmentName, planYear);
        }
        Map<String, String> translations = LocalizedText.parse(departmentName);
        String fallback = firstNonBlank(translations.get("en"), translations.get("ru"), translations.get("uz"));
        if (fallback == null) {
            throw new BusinessException("Department name is required for generated demand plan name");
        }
        return LocalizedText.format(
            baseFor(firstNonBlank(translations.get("ru"), fallback), planYear),
            baseFor(firstNonBlank(translations.get("en"), fallback), planYear),
            baseFor(firstNonBlank(translations.get("uz"), fallback), planYear)
        );
    }

    private String baseFor(String departmentName, Integer planYear) {
        return departmentName.trim() + "ning " + planYear + "-yil uchun arizasi";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
