package com.salman.ekg.dto;

import java.time.Instant;
import java.util.Set;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.salman.ekg.domain.AccessLevel;
import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.Role;

public final class AccountDtos {

    private AccountDtos() {
    }

    /** BCrypt only uses the first 72 bytes, hence the upper bound. */
    public record SignupRequest(
            @NotBlank @Size(max = 100) String name,
            @NotBlank @Email @Size(max = 254) String email,
            @NotBlank @Size(min = 8, max = 72) String password,
            @NotNull Role role) {
    }

    public record SignupResponse(Long id, String name, String email, Role role, AccountStatus status, String approvedBy) {
    }

    /** A pending request as shown to the approver. */
    public record PendingRequest(Long id, String name, String email, Role role, Instant requestedAt) {

        public static PendingRequest of(AppUser u) {
            return new PendingRequest(u.getId(), u.getDisplayName(), u.getEmail(), u.getRole(), u.getCreatedAt());
        }
    }

    public record Decision(Long id, String name, String email, Role role, AccountStatus status, String decidedBy,
                           Instant decidedAt) {

        public static Decision of(AppUser u) {
            return new Decision(u.getId(), u.getDisplayName(), u.getEmail(), u.getRole(), u.getStatus(), u.getDecidedBy(),
                    u.getDecidedAt());
        }
    }

    /** The signed-in user's profile; also drives which tabs the UI shows. */
    public record Profile(String email, String displayName, Role role, AccountStatus status, Instant memberSince,
                          String approvedBy, String approvedByName, Instant approvedAt, boolean canContribute,
                          boolean canApprove, boolean admin, Set<AccessLevel> readableLevels) {
    }
}
