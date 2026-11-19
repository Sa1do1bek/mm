package com.enterprise.mm.repository;

import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceFilter;
import com.enterprise.mm.payload.RfqWorkspaceDtos.RfqWorkspaceRow;
import com.enterprise.mm.service.RoleDataScopeService.DataScope;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface RfqWorkspaceQueryRepository {
    Page<RfqWorkspaceRow> search(
        String tenantId,
        UUID companyId,
        RfqWorkspaceFilter filter,
        DataScope dataScope,
        LocalDate today,
        Pageable pageable
    );
}
