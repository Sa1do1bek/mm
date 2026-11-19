package com.enterprise.mm.repository;

import com.enterprise.mm.entity.UserFeedback;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserFeedbackRepository extends JpaRepository<UserFeedback, UUID> {
    Optional<UserFeedback> findByIdAndIsDeletedFalse(UUID id);
    Page<UserFeedback> findByTenantIdAndCompanyIdAndIsDeletedFalse(String tenantId, UUID companyId, Pageable pageable);
}
