package com.salman.ekg.domain;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A user account. Users sign in with their (lower-case) email. New sign-ups are PENDING
 * until approved: Employee requests by HR, HR and Admin requests by an Admin.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 254)
    private String email;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private Role role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_by", length = 254)
    private String decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    protected AppUser() {
    }

    public AppUser(String email, String displayName, String passwordHash, Role role, AccountStatus status, Instant now) {
        this.email = normalizeEmail(email);
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = role;
        this.status = status;
        this.createdAt = now;
    }

    public static String normalizeEmail(String email) {
        return email == null ? null : email.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public Long getId() { return id; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getRole() { return role; }
    public AccountStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public String getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }

    /** Used only for the configured bootstrap admin, which is always an approved Admin. */
    public void resetAsBootstrapAdmin(String displayName, String passwordHash, Instant now) {
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.role = Role.ADMIN;
        if (this.status != AccountStatus.APPROVED) {
            this.status = AccountStatus.APPROVED;
            this.decidedBy = "system";
            this.decidedAt = now;
        }
    }
}
