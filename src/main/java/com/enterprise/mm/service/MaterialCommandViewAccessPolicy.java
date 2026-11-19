package com.enterprise.mm.service;

import com.enterprise.mm.payload.MaterialCommandViewDtos.SectionKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.TabKey;
import com.enterprise.mm.payload.MaterialCommandViewDtos.Visibility;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class MaterialCommandViewAccessPolicy {
    private static final Set<String> MATERIAL_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR",
        "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> PROCUREMENT_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "FINANCE_CONTROLLER",
        "BUDGET_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> PURCHASE_ORDER_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "AUDITOR", "VIEWER",
        "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> WAREHOUSE_READ = roles(
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_OPERATOR", "WAREHOUSE_HEAD",
        "WAREHOUSE_KEEPER", "FINANCE_CONTROLLER", "ACCOUNTANT", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD",
        "DEPARTMENT_WORKER");
    private static final Set<String> SUPPLIER_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "SUPPLIER_MANAGER",
        "AUDITOR", "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> QUALITY_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR", "SUPPLIER_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER",
        "BUDGET_CONTROLLER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> FINANCE_READ = roles(
        "ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER",
        "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> STOCK_HEALTH_READ = roles(
        "ADMIN", "MM_MANAGER", "WAREHOUSE_MANAGER", "WAREHOUSE_HEAD", "PROCUREMENT_MANAGER",
        "FINANCE_CONTROLLER", "ACCOUNTANT", "EXECUTIVE", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD",
        "DEPARTMENT_WORKER");
    private static final Set<String> DOCUMENT_READ = roles(
        "ADMIN", "MM_MANAGER", "PROCUREMENT_MANAGER", "PROCUREMENT_SPECIALIST", "WAREHOUSE_MANAGER",
        "WAREHOUSE_OPERATOR", "FINANCE_CONTROLLER", "BUDGET_CONTROLLER", "SUPPLIER_MANAGER", "AUDITOR",
        "VIEWER", "DEPARTMENT_HEAD", "DEPARTMENT_WORKER");
    private static final Set<String> AUDIT_READ = roles("ADMIN", "AUDITOR");
    private static final Set<String> INTEGRATION_READ = roles(
        "ADMIN", "MM_MANAGER", "FINANCE_CONTROLLER", "AUDITOR", "VIEWER", "DEPARTMENT_HEAD",
        "DEPARTMENT_WORKER");

    public Visibility visibility(TabKey tab) {
        return switch (tab) {
            case OVERVIEW -> permitted(MATERIAL_READ);
            case AVAILABILITY, MOVEMENTS -> sectionVisibility(SectionKey.INVENTORY);
            case DEMAND_SUPPLY -> anyReadable(
                SectionKey.PURCHASE_REQUESTS, SectionKey.PURCHASE_ORDERS, SectionKey.RESERVATIONS);
            case VALUATION -> anyReadable(SectionKey.VALUATION, SectionKey.MARKET_PRICE);
            case SUPPLIERS -> sectionVisibility(SectionKey.SUPPLIERS);
            case QUALITY_COMPATIBILITY -> sectionVisibility(SectionKey.QUALITY);
            case STOCK_HEALTH -> sectionVisibility(SectionKey.STOCK_HEALTH);
            case RELATIONSHIPS -> anyReadable(SectionKey.DOCUMENTS, SectionKey.AUDIT, SectionKey.INTEGRATIONS);
            case FIXED_ASSETS -> sectionVisibility(SectionKey.FIXED_ASSETS);
        };
    }

    public Visibility sectionVisibility(SectionKey section) {
        return permitted(switch (section) {
            case INVENTORY, RESERVATIONS -> WAREHOUSE_READ;
            case PURCHASE_REQUESTS -> PROCUREMENT_READ;
            case PURCHASE_ORDERS -> PURCHASE_ORDER_READ;
            case VALUATION, MARKET_PRICE -> FINANCE_READ;
            case SUPPLIERS -> SUPPLIER_READ;
            case QUALITY -> QUALITY_READ;
            case STOCK_HEALTH -> STOCK_HEALTH_READ;
            case DOCUMENTS -> DOCUMENT_READ;
            case AUDIT -> AUDIT_READ;
            case INTEGRATIONS -> INTEGRATION_READ;
            case FIXED_ASSETS -> WAREHOUSE_READ;
        });
    }

    public Map<TabKey, Visibility> tabVisibilities() {
        Map<TabKey, Visibility> visibilities = new EnumMap<>(TabKey.class);
        Arrays.stream(TabKey.values()).forEach(tab -> visibilities.put(tab, visibility(tab)));
        return Map.copyOf(visibilities);
    }

    public Map<SectionKey, Visibility> sectionVisibilities() {
        Map<SectionKey, Visibility> visibilities = new EnumMap<>(SectionKey.class);
        Arrays.stream(SectionKey.values()).forEach(section -> visibilities.put(section, sectionVisibility(section)));
        return Map.copyOf(visibilities);
    }

    public void requireReadable(TabKey tab) {
        if (visibility(tab) != Visibility.READ) {
            throw new AccessDeniedException("material360.domain.denied");
        }
    }

    public void requireReadable(SectionKey section) {
        if (sectionVisibility(section) != Visibility.READ) {
            throw new AccessDeniedException("material360.domain.denied");
        }
    }

    private Visibility anyReadable(SectionKey... sections) {
        return Arrays.stream(sections).anyMatch(section -> sectionVisibility(section) == Visibility.READ)
            ? Visibility.READ
            : Visibility.DENIED;
    }

    private Visibility permitted(Set<String> allowedRoles) {
        Set<String> currentRoles = currentRoles();
        return currentRoles.stream().anyMatch(allowedRoles::contains) ? Visibility.READ : Visibility.DENIED;
    }

    private Set<String> currentRoles() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return Set.of();
        }
        return authentication.getAuthorities().stream()
            .map(authority -> authority.getAuthority())
            .map(authority -> authority.startsWith("ROLE_") ? authority.substring(5) : authority)
            .collect(Collectors.toUnmodifiableSet());
    }

    private static Set<String> roles(String... roles) {
        return Set.of(roles);
    }
}
