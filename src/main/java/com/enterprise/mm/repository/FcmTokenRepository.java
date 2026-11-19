package com.enterprise.mm.repository;

import com.enterprise.mm.entity.FcmToken;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface FcmTokenRepository extends JpaRepository<FcmToken, UUID> {

    Optional<FcmToken> findByTokenAndIsDeletedFalse(String token);

    List<FcmToken> findAllByUserIdAndActiveTrueAndIsDeletedFalseOrderByLastSeenAtDesc(UUID userId);

    List<FcmToken> findAllByUserIdAndIsDeletedFalseOrderByLastSeenAtDesc(UUID userId);

    @Query("""
            select t
            from FcmToken t
            where t.userId = :userId
              and t.token = :token
              and t.isDeleted = false
            """)
    Optional<FcmToken> findByUserIdAndTokenAndIsDeletedFalse(@Param("userId") UUID userId,
                                                                 @Param("token") String token);
}
