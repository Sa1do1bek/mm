package com.enterprise.mm.service;

import com.enterprise.mm.common.CompanyContext;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.ServiceExecution;
import com.enterprise.mm.enums.ServiceExecutionStatus;
import com.enterprise.mm.repository.ServiceExecutionRepository;
import com.enterprise.mm.repository.UserRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ServiceExecutionOverdueScheduler {
    private final ServiceExecutionRepository repository;
    private final UserRepository userRepository;
    private final ServiceWorkflowNotificationService notificationService;

    @Scheduled(cron = "${mm.service-execution-overdue.cron:0 0 8 * * *}")
    public void notifyOverdueExecutions() {
        String previousTenant = TenantContext.currentTenant();
        var previousCompany = CompanyContext.currentCompany();
        List<ServiceExecution> executions = repository.findTop100ByIsDeletedFalseAndPlannedEndDateBeforeAndStatusInOrderByPlannedEndDateAsc(
            LocalDate.now(),
            List.of(
                ServiceExecutionStatus.NOT_STARTED.name(),
                ServiceExecutionStatus.IN_PROGRESS.name(),
                ServiceExecutionStatus.PAUSED.name(),
                ServiceExecutionStatus.COMPLETED_BY_CONTRACTOR.name(),
                ServiceExecutionStatus.UNDER_REVIEW.name()
            )
        );
        try {
            executions.forEach(execution -> {
                TenantContext.setTenant(execution.getTenantId());
                CompanyContext.setCompany(execution.getCompanyId());
                notificationService.executionOverdue(recipient(execution), execution.getCode());
            });
        } finally {
            TenantContext.setTenant(previousTenant);
            CompanyContext.setCompany(previousCompany);
        }
    }

    private String recipient(ServiceExecution execution) {
        if (execution.getResponsibleUserId() == null) {
            return "SERVICE_MANAGER";
        }
        return userRepository.findByIdAndIsDeletedFalse(execution.getResponsibleUserId())
            .map(user -> user.getUsername() == null || user.getUsername().isBlank() ? "SERVICE_MANAGER" : user.getUsername())
            .orElse("SERVICE_MANAGER");
    }
}
