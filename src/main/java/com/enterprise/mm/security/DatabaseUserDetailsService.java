package com.enterprise.mm.security;

import lombok.RequiredArgsConstructor;
import com.enterprise.mm.repository.UserRepository;
import com.enterprise.mm.common.TenantContext;
import com.enterprise.mm.entity.User;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DatabaseUserDetailsService implements UserDetailsService {
    private final UserRepository userRepository;

    
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        User user = userRepository.findAllByTenantIdAndUsernameAndIsDeletedFalse(TenantContext.currentTenant(), username).stream()
            .findFirst()
            .or(() -> userRepository.findByUsernameAndIsDeletedFalse(username))
            .orElseThrow(() -> new UsernameNotFoundException(username));

        if (!user.isEnabled()) {
            throw new DisabledException(username);
        }
        return org.springframework.security.core.userdetails.User
            .withUsername(user.getUsername())
            .password(user.getPasswordHash())
            .authorities(authorities(user))
            .accountExpired(false)
            .accountLocked(false)
            .credentialsExpired(false)
            .disabled(false)
            .build();
    }

    private String[] authorities(User user) {
        Set<String> authorities = new LinkedHashSet<>();
        user.getRoles().forEach(role -> authorities.add("ROLE_" + role));
        if (isSuperAdmin(user)) {
            authorities.add("ROLE_ADMIN");
        }
        return WarehouseRoleAuthorities.expand(authorities).stream()
            .map(org.springframework.security.core.GrantedAuthority::getAuthority)
            .toArray(String[]::new);
    }

    private boolean isSuperAdmin(User user) {
        return user.getCompanyId() == null && user.getRoles().contains(com.enterprise.mm.enums.RoleName.ADMIN);
    }
}
