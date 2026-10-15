package com.salman.ekg.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.Role;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByEmail(String email);

    boolean existsByEmail(String email);

    List<AppUser> findByStatusAndRoleInOrderByCreatedAtAsc(AccountStatus status, Collection<Role> roles);

    /**
     * Decides a pending request atomically: only one of two concurrent approvers can win
     * (the loser updates 0 rows and gets a 409), and a decided request cannot be changed again.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update AppUser u set u.status = :decision, u.decidedBy = :decidedBy, u.decidedAt = :now
             where u.id = :id and u.status = com.salman.ekg.domain.AccountStatus.PENDING
            """)
    int decidePending(@Param("id") Long id, @Param("decision") AccountStatus decision,
                      @Param("decidedBy") String decidedBy, @Param("now") Instant now);
}
