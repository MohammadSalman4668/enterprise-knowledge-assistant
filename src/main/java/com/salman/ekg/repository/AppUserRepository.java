package com.salman.ekg.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.salman.ekg.domain.AppUser;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUsername(String username);
}
