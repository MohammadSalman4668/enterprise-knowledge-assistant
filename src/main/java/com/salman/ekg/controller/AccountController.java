package com.salman.ekg.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.salman.ekg.dto.AccountDtos.Decision;
import com.salman.ekg.dto.AccountDtos.PendingRequest;
import com.salman.ekg.dto.AccountDtos.SignupRequest;
import com.salman.ekg.dto.AccountDtos.SignupResponse;
import com.salman.ekg.security.CurrentUser;
import com.salman.ekg.service.AccountService;

/**
 * Account sign-up and approval endpoints. Anyone may request an account; HR decides Employee requests
 * and Admins decide HR and Admin requests (rules enforced in {@link com.salman.ekg.service.AccountService}).
 */
@RestController
public class AccountController {

    private final AccountService accounts;

    public AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** Public: creates a PENDING account that the responsible approver must approve. */
    @PostMapping("/api/auth/signup")
    public ResponseEntity<SignupResponse> signUp(@Valid @RequestBody SignupRequest request) {
        SignupResponse created = accounts.signUp(request);
        return ResponseEntity.created(URI.create("/api/users/" + created.id())).body(created);
    }

    /** HR: pending Employee requests. Admin: pending HR and Admin requests. */
    @GetMapping("/api/users/pending")
    public List<PendingRequest> pending(Authentication auth) {
        return accounts.pendingFor(CurrentUser.from(auth));
    }

    @PostMapping("/api/users/{id}/approve")
    public Decision approve(@PathVariable Long id, Authentication auth) {
        return accounts.decide(id, true, CurrentUser.from(auth));
    }

    @PostMapping("/api/users/{id}/reject")
    public Decision reject(@PathVariable Long id, Authentication auth) {
        return accounts.decide(id, false, CurrentUser.from(auth));
    }
}
