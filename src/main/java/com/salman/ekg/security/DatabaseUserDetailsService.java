package com.salman.ekg.security;

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.repository.AppUserRepository;

/** Loads accounts by email. PENDING accounts are "disabled", REJECTED ones "locked". */
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final AppUserRepository users;

    public DatabaseUserDetailsService(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public UserDetails loadUserByUsername(String email) {
        AppUser user = users.findByEmail(AppUser.normalizeEmail(email))
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user"));
        return User.withUsername(user.getEmail())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(user.getStatus() == AccountStatus.PENDING)
                .accountLocked(user.getStatus() == AccountStatus.REJECTED)
                .build();
    }
}
