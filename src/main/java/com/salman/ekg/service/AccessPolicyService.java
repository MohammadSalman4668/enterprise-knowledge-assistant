package com.salman.ekg.service;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AccessPolicy;
import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AccessPolicyRepository;

/**
 * Single source of truth for "which access levels can this role read". Backed by the
 * {@code access_policy} table; the keyword retriever applies the same table directly in SQL.
 */
@Service
public class AccessPolicyService {

    private final AccessPolicyRepository policies;

    public AccessPolicyService(AccessPolicyRepository policies) {
        this.policies = policies;
    }

    @Transactional(readOnly = true)
    public Set<AccessLevel> allowedLevels(Role role) {
        Set<AccessLevel> levels = EnumSet.noneOf(AccessLevel.class);
        policies.findByUserRole(role).stream().map(AccessPolicy::getDocumentAccessLevel).forEach(levels::add);
        return levels;
    }

    public boolean canRead(Role role, AccessLevel level) {
        return allowedLevels(role).contains(level);
    }
}
