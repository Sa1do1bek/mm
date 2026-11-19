package com.enterprise.mm.security;

import com.enterprise.mm.enums.RoleName;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Warehouse leadership capabilities are inherited without changing stored role assignments. */
public final class WarehouseRoleAuthorities {
    private WarehouseRoleAuthorities() {
    }

    public static List<SimpleGrantedAuthority> expand(Collection<String> authorities) {
        Set<String> expanded = new LinkedHashSet<>(authorities);
        if (expanded.contains("ROLE_DEPARTMENT_HEAD")) {
            expanded.add("ROLE_WAREHOUSE_MANAGER");
        }
        if (expanded.contains("ROLE_WAREHOUSE_HEAD") || expanded.contains("ROLE_WAREHOUSE_MANAGER")) {
            expanded.add("ROLE_WAREHOUSE_MANAGER");
            expanded.add("ROLE_WAREHOUSE_HEAD");
        }
        if (expanded.contains("ROLE_WAREHOUSE_KEEPER") || expanded.contains("ROLE_WAREHOUSE_OPERATOR")) {
            expanded.add("ROLE_WAREHOUSE_OPERATOR");
            expanded.add("ROLE_WAREHOUSE_KEEPER");
        }
        return expanded.stream().map(SimpleGrantedAuthority::new).toList();
    }

    public static boolean hasRole(Collection<RoleName> storedRoles, RoleName requiredRole) {
        return expand(storedRoles.stream().map(role -> "ROLE_" + role.name()).toList()).stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_" + requiredRole.name()));
    }
}
