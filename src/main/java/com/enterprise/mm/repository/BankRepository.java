package com.enterprise.mm.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import com.enterprise.mm.entity.Bank;
import java.util.List;

public interface BankRepository extends JpaRepository<Bank, UUID> {
    Optional<Bank> findAnyById(UUID id);
    List<Bank> findAllByOrderBySortOrderAsc();
}
