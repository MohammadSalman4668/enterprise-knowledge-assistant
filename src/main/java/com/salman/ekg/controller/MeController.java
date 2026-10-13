package com.salman.ekg.controller;

import java.util.Set;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AppUserRepository;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.AccessPolicyService;

/** Who am I and what may I do: drives the UI. The server still enforces every rule itself. */
@RestController
@RequestMapping("/api/knowledge")
public class MeController {

    public record Me(String username, String displayName, Role role, boolean canContribute, boolean admin,
                     Set<AccessLevel> readableLevels) {}

    private final AppUserRepository users;
    private final AccessPolicyService accessPolicy;

    public MeController(AppUserRepository users, AccessPolicyService accessPolicy) {
        this.users = users;
        this.accessPolicy = accessPolicy;
    }

    @GetMapping("/me")
    public Me me(Authentication auth) {
        CurrentUser user = CurrentUser.from(auth);
        String displayName = users.findByUsername(user.username()).map(AppUser::getDisplayName).orElse(user.username());
        return new Me(user.username(), displayName, user.role(), user.role().canContribute(), user.isAdmin(),
                accessPolicy.allowedLevels(user.role()));
    }
}
