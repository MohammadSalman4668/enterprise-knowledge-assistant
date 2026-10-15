package com.salman.ekg.controller;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.dto.AccountDtos.Profile;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.AccountService;

/** The signed-in user's profile (name, email, role, approval) - drives the UI; the server enforces every rule itself. */
@RestController
@RequestMapping("/api/knowledge")
public class MeController {

    private final AccountService accounts;

    public MeController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping("/me")
    public Profile me(Authentication auth) {
        return accounts.profile(CurrentUser.from(auth));
    }
}
