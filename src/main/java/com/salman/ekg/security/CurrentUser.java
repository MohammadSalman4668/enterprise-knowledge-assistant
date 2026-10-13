package com.salman.ekg.security;

import java.util.Objects;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import com.salman.ekg.domain.Role;
import com.salman.ekg.exception.ApiException;

/**
 * The authenticated caller. Built only from the server-side security context, so a
 * request body (or a model's tool arguments) can never claim a different role.
 */
public record CurrentUser(String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }

    public static CurrentUser from(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Authentication required");
        }
        Role role = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null && a.startsWith("ROLE_"))
                .map(a -> parseRole(a.substring("ROLE_".length())))
                .filter(Objects::nonNull)
                .findFirst()
                .orElseThrow(() -> ApiException.forbidden("NO_ROLE", "Authenticated user has no application role"));
        return new CurrentUser(auth.getName(), role);
    }

    public static CurrentUser current() {
        return from(SecurityContextHolder.getContext().getAuthentication());
    }

    private static Role parseRole(String name) {
        try {
            return Role.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
