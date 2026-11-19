package com.enterprise.mm.repository;

import java.util.Optional;
import com.enterprise.mm.entity.Notification;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.jpa.repository.JpaRepository;
import java.time.LocalDateTime;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    Optional<Notification> findAnyById(UUID id);
    Optional<Notification> findByIdAndIsDeletedFalse(UUID id);
    List<Notification> findByTenantIdAndRecipientUsernameAndReadFalseAndIsDeletedFalse(String tenantId, String recipientUsername);

    List<Notification> findByTenantIdAndCompanyIdAndReadFalseAndIsDeletedFalse(String tenantId, UUID companyId);

    @Query("""
        select n from Notification n
        where n.tenantId = :tenantId
          and n.companyId = :companyId
          and n.isDeleted = false
          and n.read = false
          and (n.recipientUsername is null or upper(n.recipientUsername) in :principals)
        """)
    List<Notification> findUnreadForPrincipals(@Param("tenantId") String tenantId, @Param("companyId") UUID companyId, @Param("principals") List<String> principals);
    @Query("""
        select e from Notification e
        where e.tenantId = :tenantId
          and e.companyId = :companyId
          and (:includeDeleted = true or e.isDeleted = false)
          and (:search is null or
               lower(coalesce(e.code, '')) like lower(concat('%', :search, '%'))
               or lower(coalesce(e.name, '')) like lower(concat('%', :search, '%')))
          and (:statusEmpty = true or e.status in :status)
          and (CAST(:dateFrom AS timestamp) IS NULL or e.createdAt >= :dateFrom)
          and (CAST(:dateTo AS timestamp) IS NULL or e.createdAt <= :dateTo)
          and (:admin = true or e.recipientUsername is null or upper(trim(e.recipientUsername)) in :principals)
        """)
    Page<Notification> search(
        @Param("tenantId") String tenantId,
        @Param("companyId") UUID companyId,
        @Param("includeDeleted") boolean includeDeleted,
        @Param("search") String search,
        @Param("status") List<String> status,
        @Param("statusEmpty") boolean statusEmpty,
        @Param("dateFrom") LocalDateTime dateFrom,
        @Param("dateTo") LocalDateTime dateTo,
        @Param("admin") boolean admin,
        @Param("principals") List<String> principals,
        Pageable pageable
    );

}
