package com.salman.ekg.service;

import java.time.Clock;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.Role;
import com.salman.ekg.dto.AccountDtos.Decision;
import com.salman.ekg.dto.AccountDtos.PendingRequest;
import com.salman.ekg.dto.AccountDtos.Profile;
import com.salman.ekg.dto.AccountDtos.SignupRequest;
import com.salman.ekg.dto.AccountDtos.SignupResponse;
import com.salman.ekg.exception.ApiException;
import com.salman.ekg.repository.AppUserRepository;
import com.salman.ekg.security.CurrentUser;

/**
 * Sign-up and approval rules (enforced here, not in the UI):
 * <ul>
 *   <li>Every sign-up starts PENDING and cannot sign in until approved.</li>
 *   <li>Employee requests are decided by HR; HR and Admin requests by an Admin.</li>
 *   <li>A request is decided once: a conditional update makes concurrent approvals safe.</li>
 * </ul>
 */
@Service
public class AccountService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final AccessPolicyService accessPolicy;
    private final Clock clock;

    public AccountService(AppUserRepository users, PasswordEncoder encoder, AccessPolicyService accessPolicy, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.accessPolicy = accessPolicy;
        this.clock = clock;
    }

    /** Which role approves a sign-up for {@code requested}. */
    public static Role approverFor(Role requested) {
        return requested == Role.EMPLOYEE ? Role.HR : Role.ADMIN;
    }

    /** Which requested roles {@code approver} may decide. */
    public static Set<Role> decidableBy(Role approver) {
        return switch (approver) {
            case HR -> EnumSet.of(Role.EMPLOYEE);
            case ADMIN -> EnumSet.of(Role.HR, Role.ADMIN);
            case EMPLOYEE -> EnumSet.noneOf(Role.class);
        };
    }

    @Transactional
    public SignupResponse signUp(SignupRequest req) {
        String email = AppUser.normalizeEmail(req.email());
        if (users.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        AppUser saved;
        try {
            saved = users.saveAndFlush(new AppUser(email, req.name().strip(), encoder.encode(req.password()), req.role(),
                    AccountStatus.PENDING, clock.instant()));
        } catch (DataIntegrityViolationException e) {
            // Two sign-ups with the same email at the same moment: the unique index decides.
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists");
        }
        return new SignupResponse(saved.getId(), saved.getDisplayName(), saved.getEmail(), saved.getRole(), saved.getStatus(),
                approverFor(saved.getRole()) == Role.HR ? "HR" : "Admin");
    }

    @Transactional(readOnly = true)
    public List<PendingRequest> pendingFor(CurrentUser approver) {
        Set<Role> roles = decidableBy(approver.role());
        if (roles.isEmpty()) {
            throw ApiException.forbidden("FORBIDDEN", "Your role cannot approve sign-up requests");
        }
        return users.findByStatusAndRoleInOrderByCreatedAtAsc(AccountStatus.PENDING, roles).stream()
                .map(PendingRequest::of).toList();
    }

    @Transactional
    public Decision decide(Long id, boolean approve, CurrentUser approver) {
        AppUser target = users.findById(id).orElseThrow(() -> ApiException.notFound("Sign-up request"));
        if (!decidableBy(approver.role()).contains(target.getRole())) {
            throw ApiException.forbidden("NOT_YOUR_APPROVAL", target.getRole() + " requests are decided by "
                    + (approverFor(target.getRole()) == Role.HR ? "HR" : "an Admin"));
        }
        int updated = users.decidePending(id, approve ? AccountStatus.APPROVED : AccountStatus.REJECTED,
                approver.username(), clock.instant());
        if (updated == 0) {
            throw ApiException.conflict("ALREADY_DECIDED", "This request has already been decided");
        }
        return Decision.of(users.findById(id).orElseThrow());
    }

    @Transactional(readOnly = true)
    public Profile profile(CurrentUser user) {
        AppUser u = users.findByEmail(AppUser.normalizeEmail(user.username()))
                .orElseThrow(() -> ApiException.notFound("Account"));
        String approverName = u.getDecidedBy() == null || "system".equals(u.getDecidedBy()) ? null
                : users.findByEmail(u.getDecidedBy()).map(AppUser::getDisplayName).orElse(null);
        return new Profile(u.getEmail(), u.getDisplayName(), u.getRole(), u.getStatus(), u.getCreatedAt(), u.getDecidedBy(),
                approverName, u.getDecidedAt(), u.getRole().canContribute(), !decidableBy(u.getRole()).isEmpty(),
                u.getRole() == Role.ADMIN, accessPolicy.allowedLevels(u.getRole()));
    }
}
