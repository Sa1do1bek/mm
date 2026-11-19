package com.enterprise.mm.repository;

import com.enterprise.mm.entity.MaterialReturnDocument;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface MaterialReturnDocumentRepository extends JpaRepository<MaterialReturnDocument, UUID>, JpaSpecificationExecutor<MaterialReturnDocument> {
    Optional<MaterialReturnDocument> findByIdAndIsDeletedFalse(UUID id);
}
