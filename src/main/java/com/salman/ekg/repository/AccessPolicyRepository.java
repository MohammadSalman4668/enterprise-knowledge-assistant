package com.salman.ekg.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.salman.ekg.domain.AccessPolicy;
import com.salman.ekg.domain.Role;

/**
 * Reads the role-to-access-level matrix ({@code access_policy} table).
 */
public interface AccessPolicyRepository extends JpaRepository<AccessPolicy, Long> {

    List<AccessPolicy> findByUserRole(Role userRole);
}
