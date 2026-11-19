package com.enterprise.mm.repository;

import com.enterprise.mm.entity.IssueRequest;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface IssueRequestRepository extends JpaRepository<IssueRequest, UUID>, JpaSpecificationExecutor<IssueRequest> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select e from IssueRequest e where e.id = :id and e.tenantId = :tenant and e.companyId = :company and e.isDeleted = false")
    Optional<IssueRequest> lockExecution(@org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("tenant") String tenant,
        @org.springframework.data.repository.query.Param("company") UUID company);
    Optional<IssueRequest> findAnyById(UUID id);
    Optional<IssueRequest> findByIdAndIsDeletedFalse(UUID id);
}
