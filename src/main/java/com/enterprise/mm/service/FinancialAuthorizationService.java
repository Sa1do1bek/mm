package com.enterprise.mm.service;

import com.enterprise.mm.error.BusinessException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service
public class FinancialAuthorizationService {
    private static final Set<String> FINANCE_ROLES = Set.of("ROLE_ADMIN", "ROLE_FINANCE_CONTROLLER", "ROLE_ACCOUNTANT");

    public void requireFinance(String action) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BusinessException("Finance authorization is required to " + action);
        }
        Set<String> authorities = authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .collect(Collectors.toSet());
        if (authorities.stream().noneMatch(FINANCE_ROLES::contains)) {
            throw new BusinessException("Finance role is required to " + action);
        }
    }

    public boolean hasAnyRole(String... roles) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return false;
        }
        Set<String> expected = Arrays.stream(roles)
            .map(role -> role.startsWith("ROLE_") ? role : "ROLE_" + role)
            .collect(Collectors.toSet());
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .anyMatch(expected::contains);
    }
}
