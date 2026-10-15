package com.salman.ekg.security;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.salman.ekg.config.KnowledgeProperties;
import com.salman.ekg.domain.AccountStatus;
import com.salman.ekg.domain.AppUser;
import com.salman.ekg.domain.Role;
import com.salman.ekg.repository.AppUserRepository;

/**
 * Creates (or refreshes) the bootstrap Admin at startup from configuration. It is the root
 * approver: it approves HR and Admin sign-ups, and approved HR users approve Employees.
 * Name, email and password come from the git-ignored local.properties, never from the code.
 */
@Component
@Order(1)
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final KnowledgeProperties props;
    private final Clock clock;

    public AdminBootstrap(AppUserRepository users, PasswordEncoder encoder, KnowledgeProperties props, Clock clock) {
        this.users = users;
        this.encoder = encoder;
        this.props = props;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        KnowledgeProperties.Admin admin = props.admin();
        if (admin == null || isBlank(admin.email()) || isBlank(admin.name())) {
            throw new IllegalStateException("No bootstrap admin configured. Set ekg.admin.name, ekg.admin.email and "
                    + "ekg.admin.password in local.properties.");
        }
        String password = admin.password();
        if (isBlank(password)) {
            byte[] bytes = new byte[12];
            new SecureRandom().nextBytes(bytes);
            password = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            log.warn("ekg.admin.password is not set. Generated password for {}: {}", admin.email(), password);
        }
        String email = AppUser.normalizeEmail(admin.email());
        AppUser user = users.findByEmail(email)
                .orElseGet(() -> new AppUser(email, admin.name(), "", Role.ADMIN, AccountStatus.PENDING, clock.instant()));
        user.resetAsBootstrapAdmin(admin.name().strip(), encoder.encode(password), clock.instant());
        users.save(user);
        log.info("Bootstrap admin ready: {}", email);
    }

    /** Email of the bootstrap admin (owner of the seed documents). */
    public String adminEmail() {
        return AppUser.normalizeEmail(props.admin().email());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
